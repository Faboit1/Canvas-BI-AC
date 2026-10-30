package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.antibud.AntiBud;
import io.canvasmc.canvas.anticheat.antiesp.EntityVisibility;
import io.canvasmc.canvas.anticheat.antixray.RayTraceAntiXray;
import io.canvasmc.canvas.anticheat.antixray.RayTraceObfuscation;
import io.canvasmc.canvas.anticheat.disguise.BlockDisguises;
import io.canvasmc.canvas.anticheat.freecam.ChunkMasking;
import io.canvasmc.canvas.anticheat.freecam.FreecamGuard;
import io.canvasmc.canvas.anticheat.invisibility.InvisiblePlayers;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionCache;
import io.canvasmc.canvas.anticheat.storage.StorageGuard;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Per-player state of Canvas' native visibility anticheat, owned by {@link ServerPlayer}.
 * <p>
 * Everything here runs on the region thread owning the player, except the jobs submitted to
 * {@link AnticheatExecutor}. Every module has at most one job in flight per player: each tick the results of finished
 * jobs are applied first, then every active module without a running job is asked whether it needs asynchronous work,
 * and a job is submitted for each that does, all sharing a fresh {@link CameraSnapshot}.
 * <p>
 * All entry points used by the server internals live here, so the patched vanilla code only ever talks to this
 * class.
 */
public final class PlayerVisibility {

    public static final String BYPASS_ALL = "canvas.anticheat.bypass";
    public static final String BYPASS_ANTI_XRAY = "canvas.anticheat.bypass.antixray";
    public static final String BYPASS_ANTI_ESP = "canvas.anticheat.bypass.antiesp";
    public static final String BYPASS_ANTI_FREECAM = "canvas.anticheat.bypass.antifreecam";
    public static final String BYPASS_UNDERGROUND_VIEW_DISTANCE = "canvas.anticheat.bypass.undergroundviewdistance";
    public static final String BYPASS_ANTI_BUD = "canvas.anticheat.bypass.antibud";
    public static final String BYPASS_ANTI_STORAGE_ESP = "canvas.anticheat.bypass.antistorageesp";
    public static final String BYPASS_INVISIBLE_PLAYERS = "canvas.anticheat.bypass.invisibleplayers";

    private static final int PERMISSION_REFRESH_INTERVAL = 100;

    public final ServerPlayer player;

    public final RayTraceAntiXray antiXray;
    public final EntityVisibility antiEsp;
    public final FreecamGuard antiFreecam;
    public final UndergroundViewDistance undergroundViewDistance;
    public final AntiBud antiBud;
    public final StorageGuard storage;
    public final BlockDisguises disguises;
    public final InvisiblePlayers invisiblePlayers;

    private final VisibilityModule[] modules;
    private final boolean[] moduleActive;

    // region thread state
    private long epoch;
    private @Nullable ServerLevel lastLevel;
    private final Job[] jobs;
    private double lastEyeX = Double.NaN;
    private double lastEyeY;
    private double lastEyeZ;

    // bypass permissions, refreshed periodically since permission checks aren't free
    private int nextPermissionRefresh = Integer.MIN_VALUE;
    private volatile boolean reportedFailure;
    private boolean bypassAntiXray;
    private boolean bypassAntiEsp;
    private boolean bypassAntiFreecam;
    private boolean bypassUndergroundViewDistance;
    private boolean bypassAntiBud;
    private boolean bypassAntiStorageEsp;
    private boolean bypassInvisiblePlayers;

    public PlayerVisibility(final ServerPlayer player) {
        this.player = player;
        this.antiXray = new RayTraceAntiXray(this);
        this.antiEsp = new EntityVisibility(this);
        this.antiFreecam = new FreecamGuard(this);
        this.undergroundViewDistance = new UndergroundViewDistance(this);
        this.antiBud = new AntiBud(this);
        this.storage = new StorageGuard(this);
        this.disguises = new BlockDisguises(this, this.antiBud, this.storage);
        this.invisiblePlayers = new InvisiblePlayers(this);
        this.modules = new VisibilityModule[] {this.antiXray, this.antiEsp, this.antiFreecam, this.storage};
        this.moduleActive = new boolean[this.modules.length];
        this.jobs = new Job[this.modules.length];
        for (int i = 0; i < this.jobs.length; ++i) {
            this.jobs[i] = new Job(this.modules[i]);
        }
    }

    /**
     * Registers the bypass permissions with a default of false. Unregistered permissions default to operators, which
     * would silently exempt every operator from all checks.
     */
    public static void registerPermissions() {
        final org.bukkit.plugin.PluginManager pluginManager = org.bukkit.Bukkit.getPluginManager();
        final java.util.Map<String, Boolean> children = new java.util.LinkedHashMap<>();
        registerPermission(pluginManager, BYPASS_ANTI_XRAY, "Bypasses Canvas' anti-xray, including Paper's obfuscation", children);
        registerPermission(pluginManager, BYPASS_ANTI_ESP, "Bypasses Canvas' anti-ESP", children);
        registerPermission(pluginManager, BYPASS_ANTI_FREECAM, "Bypasses Canvas' anti-freecam", children);
        registerPermission(pluginManager, BYPASS_UNDERGROUND_VIEW_DISTANCE, "Bypasses Canvas' underground view distance", children);
        registerPermission(pluginManager, BYPASS_ANTI_BUD, "Bypasses Canvas' anti-bud", children);
        registerPermission(pluginManager, BYPASS_ANTI_STORAGE_ESP, "Bypasses Canvas' storage hiding", children);
        registerPermission(pluginManager, BYPASS_INVISIBLE_PLAYERS, "Sees invisible players like vanilla does", children);
        if (pluginManager.getPermission(BYPASS_ALL) == null) {
            pluginManager.addPermission(new org.bukkit.permissions.Permission(BYPASS_ALL, "Bypasses all of Canvas' visibility anticheat", org.bukkit.permissions.PermissionDefault.FALSE, children));
        }
    }

    private static void registerPermission(final org.bukkit.plugin.PluginManager pluginManager, final String name, final String description, final java.util.Map<String, Boolean> parentChildren) {
        parentChildren.put(name, Boolean.TRUE);
        if (pluginManager.getPermission(name) == null) {
            pluginManager.addPermission(new org.bukkit.permissions.Permission(name, description, org.bukkit.permissions.PermissionDefault.FALSE));
        }
    }

    /**
     * Ticks every player owned by the current region. Called once per region tick, right after the entity
     * tracker ran, so the anti-ESP candidates collected by the tracker are complete.
     */
    public static void tickPlayers(final ServerLevel level) {
        for (final ServerPlayer player : level.getLocalPlayers()) {
            final PlayerVisibility visibility = player.canvas$visibility;
            try {
                visibility.tick();
            } catch (final RuntimeException thrown) {
                // an exception on a region thread would stop the server, start over for this player instead
                visibility.failed("tick", thrown);
                try {
                    visibility.resetAll();
                } catch (final RuntimeException ignored) {
                    // reported above already
                }
            }
        }
    }

    /**
     * Reports an unexpected exception of a hook. The hooks fail open (as if the anticheat was off for the call), a
     * bug in here must never take down a region thread. Only the first failure per player is logged in full.
     */
    private void failed(final String hook, final RuntimeException thrown) {
        if (!this.reportedFailure) {
            this.reportedFailure = true;
            AnticheatExecutor.LOGGER.error("Visibility anticheat hook {} failed for player {}, further failures for this player are not logged", hook, this.player.getScoreboardName(), thrown);
        }
    }

    public WorldConfig.Anticheat config() {
        return this.player.level().canvasConfig().anticheat.effective();
    }

    public long epoch() {
        return this.epoch;
    }

    private void tick() {
        final ServerLevel level = this.player.level();
        if (level != this.lastLevel) {
            this.lastLevel = level;
            this.resetAll();
        }
        if (this.player.tickCount >= this.nextPermissionRefresh) {
            this.refreshPermissions();
        }

        final WorldConfig.Anticheat config = level.canvasConfig().anticheat.effective();
        for (int i = 0; i < this.modules.length; ++i) {
            final boolean active = this.modules[i].isActive(config);
            if (!active && this.moduleActive[i]) {
                // results of running jobs must not be applied after the reset
                ++this.epoch;
                this.modules[i].reset();
            }
            this.moduleActive[i] = active;
        }
        this.undergroundViewDistance.tick(config.undergroundViewDistance);

        for (int i = 0; i < this.modules.length; ++i) {
            final Job job = this.jobs[i];
            if (job.snapshot != null && job.done) {
                final CameraSnapshot jobSnapshot = job.snapshot;
                job.snapshot = null;
                job.done = false;
                if (jobSnapshot.epoch == this.epoch && this.moduleActive[i]) {
                    this.modules[i].applyResults(jobSnapshot);
                }
            }
        }

        final CameraSnapshot snapshot = this.snapshot(level);
        for (int i = 0; i < this.modules.length; ++i) {
            if (this.moduleActive[i]) {
                this.modules[i].tick(snapshot);
            } else {
                this.modules[i].tickInactive();
            }
        }

        for (int i = 0; i < this.modules.length; ++i) {
            final Job job = this.jobs[i];
            if (job.snapshot == null && this.moduleActive[i] && this.modules[i].prepareAsync(snapshot)) {
                job.snapshot = snapshot;
                AnticheatExecutor.execute(() -> this.runJob(job, snapshot), this.modules[i].isLatencySensitive());
            }
        }
        this.antiBud.tick(config);
        this.disguises.tick();
        this.invisiblePlayers.tick();
    }

    private void runJob(final Job job, final CameraSnapshot snapshot) {
        try {
            job.module.runAsync(snapshot, new OcclusionAccess(snapshot.level));
        } catch (final Throwable thrown) {
            AnticheatExecutor.LOGGER.error("Visibility job of {} failed for player {}", job.module.getClass().getSimpleName(), this.player.getScoreboardName(), thrown);
        } finally {
            OcclusionCache.releaseCurrent();
            job.done = true;
        }
    }

    private CameraSnapshot snapshot(final ServerLevel level) {
        final double eyeX = this.player.getX();
        final double eyeY = this.player.getEyeY();
        final double eyeZ = this.player.getZ();
        final Vec3 look = this.player.getViewVector(1.0F);

        double velocityX = 0.0;
        double velocityY = 0.0;
        double velocityZ = 0.0;
        if (!Double.isNaN(this.lastEyeX)) {
            velocityX = eyeX - this.lastEyeX;
            velocityY = eyeY - this.lastEyeY;
            velocityZ = eyeZ - this.lastEyeZ;
        }
        this.lastEyeX = eyeX;
        this.lastEyeY = eyeY;
        this.lastEyeZ = eyeZ;

        return new CameraSnapshot(
            level, this.epoch, this.player.tickCount,
            eyeX, eyeY, eyeZ,
            this.player.getYRot(), this.player.getXRot(),
            look.x, look.y, look.z,
            velocityX, velocityY, velocityZ,
            this.cameraDistance()
        );
    }

    /**
     * The maximum distance of the third person camera from the eyes. The client scales the camera distance attribute by
     * the entity's scale, and uses the vehicle's when that's further away (a happy ghast's camera is further back).
     */
    private double cameraDistance() {
        double ret = this.player.getAttributeValue(Attributes.CAMERA_DISTANCE) * this.player.getScale();
        if (this.player.getVehicle() instanceof final LivingEntity vehicle) {
            ret = Math.max(ret, vehicle.getAttributeValue(Attributes.CAMERA_DISTANCE) * vehicle.getScale());
        }
        return ret;
    }

    /**
     * Drops all visibility state, used when the player changes world or respawns. Results of jobs that are still
     * running are discarded through the epoch.
     */
    public void resetAll() {
        ++this.epoch;
        this.lastEyeX = Double.NaN;
        for (final VisibilityModule module : this.modules) {
            module.reset();
        }
        this.undergroundViewDistance.reset();
        this.antiBud.reset();
        this.disguises.reset();
        this.invisiblePlayers.reset();
    }

    private void refreshPermissions() {
        this.nextPermissionRefresh = this.player.tickCount + PERMISSION_REFRESH_INTERVAL;
        final org.bukkit.craftbukkit.entity.CraftPlayer bukkit = this.player.getBukkitEntity();
        this.bypassAntiXray = bukkit.hasPermission(BYPASS_ANTI_XRAY);
        this.bypassAntiEsp = bukkit.hasPermission(BYPASS_ANTI_ESP);
        this.bypassAntiFreecam = bukkit.hasPermission(BYPASS_ANTI_FREECAM);
        this.bypassUndergroundViewDistance = bukkit.hasPermission(BYPASS_UNDERGROUND_VIEW_DISTANCE);
        this.bypassAntiBud = bukkit.hasPermission(BYPASS_ANTI_BUD);
        this.bypassAntiStorageEsp = bukkit.hasPermission(BYPASS_ANTI_STORAGE_ESP);
        this.bypassInvisiblePlayers = bukkit.hasPermission(BYPASS_INVISIBLE_PLAYERS);
    }

    private void ensurePermissions() {
        if (this.nextPermissionRefresh == Integer.MIN_VALUE) {
            this.refreshPermissions();
        }
    }

    public boolean bypassesAntiXray() {
        this.ensurePermissions();
        return this.bypassAntiXray;
    }

    public boolean bypassesAntiEsp() {
        this.ensurePermissions();
        return this.bypassAntiEsp || this.player.isSpectator();
    }

    public boolean bypassesAntiFreecam() {
        this.ensurePermissions();
        return this.bypassAntiFreecam || this.player.isSpectator();
    }

    public boolean bypassesUndergroundViewDistance() {
        this.ensurePermissions();
        return this.bypassUndergroundViewDistance;
    }

    public boolean bypassesAntiBud() {
        this.ensurePermissions();
        return this.bypassAntiBud;
    }

    public boolean bypassesAntiStorageEsp() {
        this.ensurePermissions();
        return this.bypassAntiStorageEsp || this.player.isSpectator();
    }

    public boolean bypassesInvisiblePlayers() {
        this.ensurePermissions();
        return this.bypassInvisiblePlayers || this.player.isSpectator();
    }

    // hooks used by the server internals

    /**
     * Whether chunk packets sent to this player have to be built specifically for them, so they can't be shared
     * with other players.
     */
    public boolean needsPersonalChunkPackets() {
        // evaluated live, chunks can be sent before the first tick of a player
        final WorldConfig.Anticheat config = this.config();
        return this.antiXray.isActive(config) || this.antiFreecam.isActive(config) || this.antiBud.isActive(config) || this.storage.isActive(config)
            || this.bypassesAntiXray();
    }

    /**
     * @return whether the module was active during the last tick
     */
    public boolean isActive(final VisibilityModule module) {
        for (int i = 0; i < this.modules.length; ++i) {
            if (this.modules[i] == module) {
                return this.moduleActive[i];
            }
        }
        throw new IllegalArgumentException("Unknown module " + module);
    }

    /**
     * Creates the chunk packet sent to this player, with anti-freecam masking and the disguises of anti-bud and
     * storage hiding applied.
     */
    public ClientboundLevelChunkWithLightPacket createChunkPacket(final LevelChunk chunk, final boolean modifyBlocks) {
        try {
            // blocks ray traced anti-xray revealed stay real when the chunk is sent again
            RayTraceObfuscation.keepReal(this.antiXray.revealedIn(chunk));
            return this.antiFreecam.createChunkPacket(chunk, modifyBlocks && !this.bypassesAntiXray(), this.disguises.prepareChunk(chunk));
        } catch (final RuntimeException thrown) {
            this.failed("createChunkPacket", thrown);
            return new ClientboundLevelChunkWithLightPacket(chunk, chunk.getLevel().getLightEngine(), null, null, modifyBlocks);
        } finally {
            RayTraceObfuscation.keepReal(null);
        }
    }

    /**
     * Called right after a chunk packet created by {@link #createChunkPacket} was queued for sending.
     */
    public void onChunkSent(final LevelChunk chunk, final ClientboundLevelChunkWithLightPacket packet) {
        try {
            this.antiXray.onChunkSent(chunk, packet);
            this.antiFreecam.onChunkSent(chunk, packet);
        } catch (final RuntimeException thrown) {
            this.failed("onChunkSent", thrown);
        }
    }

    /**
     * Called right before the client is told to forget a chunk.
     */
    public void onChunkUnload(final int chunkX, final int chunkZ) {
        try {
            this.antiXray.onChunkUnload(chunkX, chunkZ);
            this.antiFreecam.onChunkUnload(chunkX, chunkZ);
            this.antiBud.onChunkUnload(chunkX, chunkZ);
            this.storage.onChunkUnload(chunkX, chunkZ);
            this.disguises.onChunkUnload(chunkX, chunkZ);
        } catch (final RuntimeException thrown) {
            this.failed("onChunkUnload", thrown);
        }
    }

    /**
     * Entity tracker check, called every tick for every entity in tracking range. Must be cheap.
     */
    public boolean canSee(final Entity entity) {
        try {
            // anti-ESP keeps its view of hidden invisible players up to date as well, their particles need it
            final boolean visible = entity.getRootVehicle() == this.player.getRootVehicle()
                || !this.antiFreecam.isEntityMasked(entity) && this.antiEsp.canSee(entity);
            return this.invisiblePlayers.maySee(entity) && visible;
        } catch (final RuntimeException thrown) {
            this.failed("canSee", thrown);
            return true;
        }
    }

    /**
     * Called when the entity tracker pairs the entity with this player, with the packet that adds it.
     *
     * @param out receives packets that must arrive before it
     * @return the packet that adds the entity to this player's client
     */
    public Packet<ClientGamePacketListener> pairingPacket(final Entity entity, final Packet<ClientGamePacketListener> addEntity,
                                                          final Consumer<Packet<ClientGamePacketListener>> out) {
        try {
            return this.invisiblePlayers.onPairing(entity, addEntity, out);
        } catch (final RuntimeException thrown) {
            this.failed("pairingPacket", thrown);
            return addEntity;
        }
    }

    /**
     * Called when the entity tracker removed the entity from this player's client.
     */
    public void onUnpaired(final Entity entity) {
        try {
            this.invisiblePlayers.onUnpaired(entity);
        } catch (final RuntimeException thrown) {
            this.failed("onUnpaired", thrown);
        }
    }

    /**
     * Called when this player attacked or used an item, a block or an entity, on the thread owning the player.
     */
    public void onInteraction() {
        try {
            this.invisiblePlayers.onInteraction();
        } catch (final RuntimeException thrown) {
            this.failed("onInteraction", thrown);
        }
    }

    /**
     * Called when this player swung their main hand, on the thread owning the player.
     */
    public void onSwing() {
        try {
            this.invisiblePlayers.onSwing();
        } catch (final RuntimeException thrown) {
            this.failed("onSwing", thrown);
        }
    }
    /**
     * Called when this player starts breaking a block, on the thread owning the player.
     *
     * @return whether the player hit a hidden player instead, the block must not be broken then
     */
    public boolean interceptsDestroy() {
        try {
            return this.invisiblePlayers.interceptsDestroy();
        } catch (final RuntimeException thrown) {
            this.failed("interceptsDestroy", thrown);
            return false;
        }
    }

    /**
     * Whether particles at the block position must not be sent to this player, because it can't see the place.
     * Callable from any thread, off the player's thread only filler counts.
     */
    public boolean hidesParticlesAt(final int x, final int y, final int z) {
        try {
            return this.config().antiFreecam.hideParticles && this.antiFreecam.hidesPosition(x, y, z);
        } catch (final RuntimeException thrown) {
            this.failed("hidesParticlesAt", thrown);
            return false;
        }
    }

    /**
     * Called by the player chunk loader before it sends a chunk, chunks that may not be sent yet stay queued.
     */
    public boolean mayChunkBeSent(final int chunkX, final int chunkZ) {
        try {
            return this.antiFreecam.mayChunkBeSent(chunkX, chunkZ);
        } catch (final RuntimeException thrown) {
            this.failed("mayChunkBeSent", thrown);
            return true;
        }
    }

    /**
     * Whether the given state of the block must not be sent to this player right now, because the block is hidden
     * by the ray traced anti-xray or storage hiding, or masked by anti-freecam (which, per block, also withholds
     * updates of blocks that can't be seen right now). Used to filter block updates, block entity data and block
     * events.
     * <p>
     * This may be called from any thread, as block events are broadcast to players of other regions too. The answer
     * may then be slightly stale, which is harmless since revealing always sends the current state.
     *
     * @param state the state that would be sent, for block entity data and block events the current state
     */
    public boolean isBlockHidden(final int x, final int y, final int z, final BlockState state) {
        try {
            // first, it records storage the client doesn't have yet
            final boolean storage = this.storage.isHidden(x, y, z, state);
            return this.antiXray.isHidden(x, y, z, state) || this.antiFreecam.withholdsUpdate(x, y, z) || storage;
        } catch (final RuntimeException thrown) {
            this.failed("isBlockHidden", thrown);
            return false;
        }
    }

    /**
     * Cheap pre-check for {@link #isBlockHidden}: {@code false} if nothing in the chunk section is hidden from this
     * player. Same thread rules.
     */
    public boolean mayHideInSection(final int chunkX, final int sectionY, final int chunkZ) {
        try {
            return this.antiFreecam.mayHideIn(chunkX, sectionY, chunkZ) || this.antiXray.mayHideInSection(chunkX, sectionY, chunkZ)
                || this.storage.mayHideIn(chunkX, sectionY, chunkZ);
        } catch (final RuntimeException thrown) {
            this.failed("mayHideInSection", thrown);
            // isBlockHidden decides
            return true;
        }
    }

    /**
     * Called when the player is added to a world, before any chunk of it is sent, so state of the previous world is
     * dropped before it could be mixed with the new one.
     */
    public void onAddedToWorld() {
        final ServerLevel level = this.player.level();
        if (level != this.lastLevel) {
            this.lastLevel = level;
            this.resetAll();
        }
    }

    /**
     * Whether anti-ESP or anti-freecam currently hide the entity from this player, i.e. the entity tracker doesn't
     * send it to them. Used to keep other packets about the entity (like its block breaking progress) away from this
     * player.
     */
    public boolean isEntityHidden(final Entity entity) {
        if (entity == this.player || entity.getRootVehicle() == this.player.getRootVehicle()) {
            return false;
        }
        try {
            return this.antiFreecam.isEntityMasked(entity) || (this.isActive(this.antiEsp) && this.antiEsp.isHidden(entity));
        } catch (final RuntimeException thrown) {
            this.failed("isEntityHidden", thrown);
            return false;
        }
    }

    /**
     * Whether the client could move blocks differently than the server if it played the piston event at the
     * position: clients simulate pushes on their own view of the world, in which hidden blocks are stone and masked
     * sections filler. Only hidden blocks within reach that pistons treat differently than stone matter, see
     * {@link VisibilityFilters#movesLikeStone}. Same thread rules as {@link #isBlockHidden}, called on the region
     * thread owning the piston.
     */
    public boolean mayDesyncPiston(final ServerLevel level, final BlockPos pos, final Direction facing) {
        try {
            final int reach = VisibilityFilters.PISTON_REACH;
            final int x = pos.getX();
            final int y = pos.getY();
            final int z = pos.getZ();
            // filler only matters in the corridor the piston pushes or pulls along, and only where the real block
            // doesn't move like the filler: elsewhere the client gets the real blocks once they can be seen
            final int endX = x + facing.getStepX() * reach;
            final int endY = y + facing.getStepY() * reach;
            final int endZ = z + facing.getStepZ() * reach;
            if (this.antiFreecam.anyHidden(Math.min(x, endX) - 1, Math.min(y, endY) - 1, Math.min(z, endZ) - 1,
                Math.max(x, endX) + 1, Math.max(y, endY) + 1, Math.max(z, endZ) + 1,
                (final int hiddenX, final int hiddenY, final int hiddenZ) -> !VisibilityFilters.movesLikeFiller(level, hiddenX, hiddenY, hiddenZ))) {
                return true;
            }
            return this.antiXray.anyHidden(level, x - reach, y - reach, z - reach, x + reach, y + reach, z + reach,
                (final int hiddenX, final int hiddenY, final int hiddenZ) -> !VisibilityFilters.movesLikeStone(level, hiddenX, hiddenY, hiddenZ));
        } catch (final RuntimeException thrown) {
            this.failed("mayDesyncPiston", thrown);
            return false;
        }
    }

    /**
     * Whether the locator bar may get the exact position of the entity for this player. While anti-ESP is active, only
     * entities the player can currently see may be located exactly, all others only get a direction. Called on the
     * player's region thread.
     */
    public boolean mayLocateExactly(final Entity entity) {
        try {
            return !this.isActive(this.antiEsp) || !this.config().antiEsp.limitLocatorBar || this.antiEsp.isKnownVisible(entity);
        } catch (final RuntimeException thrown) {
            this.failed("mayLocateExactly", thrown);
            return true;
        }
    }

    /**
     * Filters a light update before it is sent to this player.
     *
     * @param disguiseScan the disguised blocks whose light reaches into the update, see {@link BlockDisguises#scan}
     * @return the packet to send, which may be the given packet, a filtered copy, or {@code null} to send nothing
     */
    public @Nullable ClientboundLightUpdatePacket filterLightUpdate(final ClientboundLightUpdatePacket packet, final BlockDisguises.@Nullable LightScan disguiseScan) {
        try {
            final ClientboundLightUpdatePacket corrected = this.disguises.correctLight(packet, disguiseScan);
            return corrected == null ? null : this.antiFreecam.filterLightUpdate(corrected);
        } catch (final RuntimeException thrown) {
            this.failed("filterLightUpdate", thrown);
            return packet;
        }
    }

    /**
     * The state of a block to send to this player in a block update, which is the given state unless anti-bud
     * disguises it. May be called from any thread, like {@link #isBlockHidden}, which is checked first.
     *
     * @return the state to send, or {@code null} to send nothing because the client has that disguise already
     */
    public @Nullable BlockState filterBlockUpdate(final int x, final int y, final int z, final BlockState state) {
        try {
            return this.antiBud.filterUpdate(x, y, z, state);
        } catch (final RuntimeException thrown) {
            this.failed("filterBlockUpdate", thrown);
            return state;
        }
    }

    /**
     * Like {@link #filterBlockUpdate}, but always a state to send, for corrections the client waits for.
     */
    public BlockState disguiseBlock(final int x, final int y, final int z, final BlockState state) {
        try {
            return this.antiBud.disguise(x, y, z, state);
        } catch (final RuntimeException thrown) {
            this.failed("disguiseBlock", thrown);
            return state;
        }
    }

    /**
     * What a block hidden from this player is sent as when the client must get a state for it, like after an
     * interaction or for the particles of a breaking block: what storage hiding sends it as, or the hide block of
     * anti-xray. Same thread rules as {@link #isBlockHidden}.
     */
    public BlockState hiddenStateOf(final ServerLevel level, final int x, final int y, final int z, final BlockState state) {
        try {
            if (this.antiFreecam.hidesPosition(x, y, z)) {
                // what the chunk has there
                return ChunkMasking.filler(level, y >> 4);
            }
            final BlockState storage = this.storage.hiddenAs(x, y, z, state);
            if (storage != null) {
                return storage;
            }
        } catch (final RuntimeException thrown) {
            this.failed("hiddenStateOf", thrown);
        }
        return VisibilityFilters.hideBlock(level, y);
    }

    /**
     * Whether anti-bud would disguise a bud at the position, for effects that carry the state of a block that is
     * gone already. Same thread rules as {@link #disguiseBlock}.
     */
    public boolean disguisesBudAt(final int x, final int y, final int z) {
        try {
            return this.antiBud.disguisesAt(x, y, z);
        } catch (final RuntimeException thrown) {
            this.failed("disguisesBudAt", thrown);
            return false;
        }
    }

    /**
     * Called when a block of a chunk sent to this player changed between occluding and not occluding, on the thread
     * owning the block.
     */
    public void onOpacityChange(final ServerLevel level, final BlockPos pos, final boolean opaque) {
        try {
            this.antiFreecam.onOpacityChange(level, pos, opaque);
        } catch (final RuntimeException thrown) {
            this.failed("onOpacityChange", thrown);
        }
    }

    /**
     * Called when the player is moved by the server (teleports, ender pearls, respawn in the same world) right
     * before the client is told about the new position.
     */
    public void onTeleport() {
        try {
            this.antiFreecam.onTeleport();
        } catch (final RuntimeException thrown) {
            this.failed("onTeleport", thrown);
        }
    }

    /**
     * @return the maximum chunk send distance for this player, or {@link Integer#MAX_VALUE} for no limit
     */
    public int sendDistanceCap() {
        return this.undergroundViewDistance.sendDistanceCap();
    }

    /**
     * The job of one module. {@link #snapshot} is set on the region thread while the job is in flight, {@link #done}
     * is set by the worker once it finished.
     */
    private static final class Job {

        final VisibilityModule module;
        @Nullable CameraSnapshot snapshot;
        volatile boolean done;

        Job(final VisibilityModule module) {
            this.module = module;
        }
    }
}
