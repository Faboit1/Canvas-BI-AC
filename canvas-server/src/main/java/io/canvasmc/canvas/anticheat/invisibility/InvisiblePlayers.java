package io.canvasmc.canvas.anticheat.invisibility;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.authlib.GameProfile;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Invisible players as one player gets them, see {@link WorldConfig.Anticheat.InvisiblePlayers}.
 * <p>
 * Vanilla sends invisible players like any other player, invisible only by an entity flag, so ESP mods draw them and
 * name tag mods tell who they are. An invisible player nothing of which is drawn isn't sent at all ({@link Mode#HIDE}):
 * the viewer gets the particles of their effects, spawned the way the client spawns them, and a swing that would have
 * hit them on a client that had them hits them on the server ({@link #onMissedSwing}, {@link #interceptsDestroy}).
 * An invisible player something of which is drawn (armor, a held item, fire, ...) is sent under a random identity
 * ({@link Mode#ANONYMOUS}): a random UUID and name without skin, announced as an unlisted player list entry, so the
 * client draws what it would draw and nothing tells whose it is.
 * <p>
 * Runs on the thread owning the viewer. The pairing hooks run on the thread owning the paired player, which the
 * entity tracker only pairs with players of the same region.
 */
public final class InvisiblePlayers {

    public enum Mode {
        /**
         * Sent as it is.
         */
        VISIBLE,
        /**
         * Not sent.
         */
        HIDE,
        /**
         * Sent under a random identity.
         */
        ANONYMOUS
    }

    /**
     * The distance within which the client spawns the particles of effects.
     */
    private static final double PARTICLE_DISTANCE_SQUARED = 32.0 * 32.0;
    /**
     * Ticks between two attempts to break a block that still count as holding the button down: the client starts
     * breaking the next block 5 ticks after the last one.
     */
    private static final int HOLD_TICKS = 6;
    private static final char[] NAME_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789_".toCharArray();

    private final PlayerVisibility owner;
    /**
     * The players paired with the viewer, by entity id, with the UUID the client knows them by.
     */
    private final Map<Integer, Pairing> paired = new ConcurrentHashMap<>();
    /**
     * The tick a press of the attack button last hit a hidden player, far enough in the past to begin with.
     */
    private int lastInterceptTick = -HOLD_TICKS - 1;
    /**
     * The tick the client last attacked or used something.
     */
    private int lastInteractionTick = -2;

    private record Pairing(UUID sent, boolean anonymous) {
    }

    public InvisiblePlayers(final PlayerVisibility owner) {
        this.owner = owner;
    }

    private WorldConfig.Anticheat.InvisiblePlayers config() {
        return this.owner.config().invisiblePlayers;
    }

    /**
     * @return how the entity is sent to the viewer
     */
    public Mode mode(final Entity entity) {
        if (!(entity instanceof final ServerPlayer target) || target == this.owner.player) {
            return Mode.VISIBLE;
        }
        final WorldConfig.Anticheat.InvisiblePlayers config = this.config();
        // spectators and teammates who see invisible teammates see them, like vanilla
        if (!config.enabled || this.owner.bypassesInvisiblePlayers() || !target.isInvisibleTo(this.owner.player)) {
            return Mode.VISIBLE;
        }
        if (isAnythingDrawn(target)) {
            return config.anonymize ? Mode.ANONYMOUS : Mode.VISIBLE;
        }
        return Mode.HIDE;
    }

    /**
     * Mirrors what clients draw of an invisible player: equipment, fire, the glowing outline, stuck arrows and bee
     * stingers, parrots on the shoulders, the riptide attack, and the vehicle or the passengers they ride with.
     */
    public static boolean isAnythingDrawn(final ServerPlayer player) {
        for (final EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (!player.getItemBySlot(slot).isEmpty()) {
                return true;
            }
        }
        return player.displayFireAnimation() || player.isCurrentlyGlowing() || player.getArrowCount() > 0 || player.getStingerCount() > 0
            || player.getShoulderParrotLeft().isPresent() || player.getShoulderParrotRight().isPresent()
            || player.isAutoSpinAttack() || player.isPassenger() || player.isVehicle();
    }

    /**
     * Entity tracker check.
     *
     * @return whether the entity may be paired with the viewer: it isn't hidden, and if it is paired already, it is
     * paired with the identity it needs now. It is paired again with the right one otherwise
     */
    public boolean maySee(final Entity entity) {
        final Mode mode = this.mode(entity);
        if (mode == Mode.HIDE) {
            return false;
        }
        final Pairing pairing = this.paired.get(entity.getId());
        return pairing == null || pairing.anonymous() == (mode == Mode.ANONYMOUS);
    }

    /**
     * Called when the entity is paired with the viewer, with the packet that adds it.
     *
     * @return the packet that adds it to the viewer's client, after the player list entry its random identity needs
     */
    public Packet<ClientGamePacketListener> onPairing(final Entity entity, final Packet<ClientGamePacketListener> addEntity,
                                                      final Consumer<Packet<ClientGamePacketListener>> out) {
        if (!(entity instanceof ServerPlayer) || !(addEntity instanceof final ClientboundAddEntityPacket add)) {
            return addEntity;
        }
        if (this.mode(entity) != Mode.ANONYMOUS) {
            this.paired.put(entity.getId(), new Pairing(entity.getUUID(), false));
            return addEntity;
        }
        final UUID id = UUID.randomUUID();
        out.accept(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
            new ClientboundPlayerInfoUpdatePacket.Entry(id, new GameProfile(id, randomName()), false, 0, GameType.SURVIVAL, null, true, 0, null)));
        this.paired.put(entity.getId(), new Pairing(id, true));
        return new ClientboundAddEntityPacket(add.getId(), id, add.getX(), add.getY(), add.getZ(), add.getXRot(), add.getYRot(),
            add.getType(), add.getData(), add.getMovement(), add.getYHeadRot());
    }

    /**
     * A valid player name no real player has: real names are shorter or use other characters too.
     */
    private static String randomName() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final char[] ret = new char[16];
        for (int i = 0; i < ret.length; ++i) {
            ret[i] = NAME_CHARACTERS[random.nextInt(NAME_CHARACTERS.length)];
        }
        return new String(ret);
    }

    /**
     * Called when the entity was removed from the viewer's client.
     */
    public void onUnpaired(final Entity entity) {
        final Pairing pairing = this.paired.remove(entity.getId());
        if (pairing != null && pairing.anonymous()) {
            this.owner.player.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(pairing.sent())));
        }
    }

    /**
     * The client forgot all entities, like on a world change: the player list entries of random identities go too.
     */
    public void reset() {
        final List<UUID> anonymous = new ArrayList<>();
        for (final Iterator<Pairing> iterator = this.paired.values().iterator(); iterator.hasNext(); ) {
            final Pairing pairing = iterator.next();
            if (pairing.anonymous()) {
                anonymous.add(pairing.sent());
            }
            iterator.remove();
        }
        if (!anonymous.isEmpty()) {
            this.owner.player.connection.send(new ClientboundPlayerInfoRemovePacket(anonymous));
        }
    }

    /**
     * Spawns the particles of the effects of the hidden players around the viewer, which its client can't spawn.
     */
    public void tick() {
        if (!this.config().enabled) {
            return;
        }
        final ServerPlayer viewer = this.owner.player;
        final ServerLevel level = viewer.level();
        for (final ServerPlayer target : level.getLocalPlayers()) {
            if (target == viewer || target.distanceToSqr(viewer) > PARTICLE_DISTANCE_SQUARED || this.mode(target) != Mode.HIDE
                // behind walls their particles stay hidden like they would
                || this.owner.isEntityHidden(target)) {
                continue;
            }
            this.spawnEffectParticle(target);
        }
    }

    /**
     * Mirrors the client: an invisible entity spawns one of the particles of its visible effects every 15 ticks on
     * average, five times less often if all of them are ambient, somewhere in its bounding box.
     */
    private void spawnEffectParticle(final ServerPlayer target) {
        final Collection<MobEffectInstance> effects = target.getActiveEffects();
        if (effects.isEmpty()) {
            return;
        }
        List<ParticleOptions> particles = null;
        for (final MobEffectInstance effect : effects) {
            if (effect.isVisible()) {
                if (particles == null) {
                    particles = new ArrayList<>(effects.size());
                }
                particles.add(effect.getParticleOptions());
            }
        }
        final ServerPlayer viewer = this.owner.player;
        final RandomSource random = viewer.getRandom();
        if (particles == null || random.nextInt(15 * (LivingEntity.areAllEffectsAmbient(effects) ? 5 : 1)) != 0) {
            return;
        }
        final double x = target.getRandomX(0.5);
        final double y = target.getRandomY();
        final double z = target.getRandomZ(0.5);
        if (this.owner.hidesParticlesAt(Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
            return;
        }
        viewer.connection.send(new ClientboundLevelParticlesPacket(particles.get(random.nextInt(particles.size())), false, false,
            x, y, z, 1.0F, 1.0F, 1.0F, 1.0F, 0));
    }

    // hit detection

    /**
     * Called when the client attacked or used an item, a block or an entity, which it swings its hand for.
     */
    public void onInteraction() {
        this.lastInteractionTick = this.owner.player.tickCount;
    }

    /**
     * Called when the client swung its main hand. It swings after attacking or using something, and every tick while
     * breaking a block, which needs a block in reach. Any other swing is a press of the attack button that hit nothing.
     */
    public void onSwing() {
        final ServerPlayer viewer = this.owner.player;
        if (!this.config().enabled || viewer.tickCount - this.lastInteractionTick <= 1) {
            return;
        }
        final Vec3 eye = viewer.getEyePosition();
        final Vec3 end = eye.add(viewer.getViewVector(1.0F).scale(viewer.blockInteractionRange()));
        if (viewer.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer)).getType() == HitResult.Type.MISS) {
            this.onMissedSwing();
        }
    }

    /**
     * Called when the client swung at nothing: it hits the hidden player a client that had them would have hit.
     */
    private void onMissedSwing() {
        final ServerPlayer target = this.targetOfSwing();
        if (target != null) {
            this.attack(target);
        }
    }

    /**
     * Called when the client starts breaking a block. A client that had the hidden player standing in front of the
     * block would have attacked them instead.
     *
     * @return whether the hidden player was hit, or a press of the attack button that hit them is still held: the
     * block must not be broken then
     */
    public boolean interceptsDestroy() {
        final ServerPlayer target = this.targetOfSwing();
        if (target == null) {
            return false;
        }
        final int tick = this.owner.player.tickCount;
        // holding the button down attacks once, like vanilla, but breaks nothing behind the target either
        if (tick - this.lastInterceptTick > HOLD_TICKS) {
            this.attack(target);
        }
        this.lastInterceptTick = tick;
        return true;
    }

    /**
     * @return the hidden player the viewer's line of sight hits first within reach, before any block
     */
    private @Nullable ServerPlayer targetOfSwing() {
        final ServerPlayer viewer = this.owner.player;
        if (viewer.isSpectator() || !this.config().enabled) {
            return null;
        }
        final double range = viewer.entityInteractionRange();
        final Vec3 eye = viewer.getEyePosition();
        final Vec3 end = eye.add(viewer.getViewVector(1.0F).scale(range));
        final ServerLevel level = viewer.level();
        final BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        double nearestSquared = block.getType() == HitResult.Type.MISS ? range * range : eye.distanceToSqr(block.getLocation());
        ServerPlayer ret = null;
        for (final ServerPlayer target : level.getLocalPlayers()) {
            if (target == viewer || !target.isAlive() || target.isSpectator() || this.mode(target) != Mode.HIDE) {
                continue;
            }
            final AABB box = target.getBoundingBox().inflate(target.getPickRadius());
            final Optional<Vec3> hit = box.contains(eye) ? Optional.of(eye) : box.clip(eye, end);
            if (hit.isPresent()) {
                final double distanceSquared = eye.distanceToSqr(hit.get());
                if (distanceSquared < nearestSquared) {
                    nearestSquared = distanceSquared;
                    ret = target;
                }
            }
        }
        return ret;
    }

    /**
     * Attacks like the attack packet does.
     */
    private void attack(final ServerPlayer target) {
        final ServerPlayer viewer = this.owner.player;
        if (!TickThread.isTickThreadFor(target) || !viewer.level().getWorldBorder().isWithinBounds(target.blockPosition())) {
            return;
        }
        final ItemStack weapon = viewer.getMainHandItem();
        if (!viewer.isWithinAttackRange(weapon, target.getBoundingBox(), 0.0) || weapon.has(DataComponents.PIERCING_WEAPON)
            || !weapon.isItemEnabled(viewer.level().enabledFeatures()) || viewer.cannotAttackWithItem(weapon, 5)) {
            return;
        }
        viewer.attack(target);
    }
}
