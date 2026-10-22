package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.antiesp.EntityVisibility;
import io.canvasmc.canvas.anticheat.antixray.RayTraceAntiXray;
import io.canvasmc.canvas.anticheat.freecam.FreecamGuard;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
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

    private static final int PERMISSION_REFRESH_INTERVAL = 100;

    public final ServerPlayer player;

    public final RayTraceAntiXray antiXray;
    public final EntityVisibility antiEsp;
    public final FreecamGuard antiFreecam;
    public final UndergroundViewDistance undergroundViewDistance;

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
    private boolean bypassAntiXray;
    private boolean bypassAntiEsp;
    private boolean bypassAntiFreecam;
    private boolean bypassUndergroundViewDistance;

    public PlayerVisibility(final ServerPlayer player) {
        this.player = player;
        this.antiXray = new RayTraceAntiXray(this);
        this.antiEsp = new EntityVisibility(this);
        this.antiFreecam = new FreecamGuard(this);
        this.undergroundViewDistance = new UndergroundViewDistance(this);
        this.modules = new VisibilityModule[] {this.antiXray, this.antiEsp, this.antiFreecam};
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
            player.canvas$visibility.tick();
        }
    }

    public WorldConfig.Anticheat config() {
        return this.player.level().canvasConfig().anticheat;
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

        final WorldConfig.Anticheat config = level.canvasConfig().anticheat;
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
    }

    private void runJob(final Job job, final CameraSnapshot snapshot) {
        try {
            job.module.runAsync(snapshot, new OcclusionAccess(snapshot.level));
        } catch (final Throwable thrown) {
            AnticheatExecutor.LOGGER.error("Visibility job of {} failed for player {}", job.module.getClass().getSimpleName(), this.player.getScoreboardName(), thrown);
        } finally {
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
    }

    private void refreshPermissions() {
        this.nextPermissionRefresh = this.player.tickCount + PERMISSION_REFRESH_INTERVAL;
        final org.bukkit.craftbukkit.entity.CraftPlayer bukkit = this.player.getBukkitEntity();
        this.bypassAntiXray = bukkit.hasPermission(BYPASS_ANTI_XRAY);
        this.bypassAntiEsp = bukkit.hasPermission(BYPASS_ANTI_ESP);
        this.bypassAntiFreecam = bukkit.hasPermission(BYPASS_ANTI_FREECAM);
        this.bypassUndergroundViewDistance = bukkit.hasPermission(BYPASS_UNDERGROUND_VIEW_DISTANCE);
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

    // hooks used by the server internals

    /**
     * Whether chunk packets sent to this player have to be built specifically for them, so they can't be shared
     * with other players.
     */
    public boolean needsPersonalChunkPackets() {
        // evaluated live, chunks can be sent before the first tick of a player
        final WorldConfig.Anticheat config = this.config();
        return this.antiXray.isActive(config) || this.antiFreecam.isActive(config) || this.bypassesAntiXray();
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
     * Creates the chunk packet sent to this player, with anti-freecam masking applied.
     */
    public ClientboundLevelChunkWithLightPacket createChunkPacket(final LevelChunk chunk, final boolean modifyBlocks) {
        return this.antiFreecam.createChunkPacket(chunk, modifyBlocks && !this.bypassesAntiXray());
    }

    /**
     * Called right after a chunk packet created by {@link #createChunkPacket} was queued for sending.
     */
    public void onChunkSent(final LevelChunk chunk, final ClientboundLevelChunkWithLightPacket packet) {
        this.antiXray.onChunkSent(chunk, packet);
        this.antiFreecam.onChunkSent(chunk, packet);
    }

    /**
     * Called right before the client is told to forget a chunk.
     */
    public void onChunkUnload(final int chunkX, final int chunkZ) {
        this.antiXray.onChunkUnload(chunkX, chunkZ);
        this.antiFreecam.onChunkUnload(chunkX, chunkZ);
    }

    /**
     * Entity tracker check, called every tick for every entity in tracking range. Must be cheap.
     */
    public boolean canSee(final Entity entity) {
        return !this.antiFreecam.isEntityMasked(entity) && this.antiEsp.canSee(entity);
    }

    /**
     * Called by the player chunk loader before it sends a chunk, chunks that may not be sent yet stay queued.
     */
    public boolean mayChunkBeSent(final int chunkX, final int chunkZ) {
        return this.antiFreecam.mayChunkBeSent(chunkX, chunkZ);
    }

    /**
     * Whether the given state of the block must not be sent to this player right now, because the block is hidden
     * by the ray traced anti-xray or lies in a section masked by anti-freecam. Used to filter block updates, block
     * entity data and block events.
     * <p>
     * This may be called from any thread, as block events are broadcast to players of other regions too. The answer
     * may then be slightly stale, which is harmless since revealing always sends the current state.
     *
     * @param state the state that would be sent, for block entity data and block events the current state
     */
    public boolean isBlockHidden(final int x, final int y, final int z, final BlockState state) {
        return this.antiXray.isHidden(x, y, z, state) || this.antiFreecam.isMasked(x, y, z);
    }

    /**
     * Cheap pre-check for {@link #isBlockHidden}: {@code false} if nothing in the chunk section is hidden from this
     * player. Same thread rules.
     */
    public boolean mayHideInSection(final int chunkX, final int sectionY, final int chunkZ) {
        return this.antiFreecam.isSectionMasked(chunkX, sectionY, chunkZ) || this.antiXray.mayHideInSection(chunkX, sectionY, chunkZ);
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
        if (entity == this.player) {
            return false;
        }
        return this.antiFreecam.isEntityMasked(entity) || (this.isActive(this.antiEsp) && this.antiEsp.isHidden(entity));
    }

    /**
     * Filters a light update before it is sent to this player.
     *
     * @return the packet to send, which may be the given packet, a filtered copy, or {@code null} to send nothing
     */
    public @Nullable ClientboundLightUpdatePacket filterLightUpdate(final ClientboundLightUpdatePacket packet) {
        return this.antiFreecam.filterLightUpdate(packet);
    }

    /**
     * Called when the player is moved by the server (teleports, ender pearls, respawn in the same world) right
     * before the client is told about the new position.
     */
    public void onTeleport() {
        this.antiFreecam.onTeleport();
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
