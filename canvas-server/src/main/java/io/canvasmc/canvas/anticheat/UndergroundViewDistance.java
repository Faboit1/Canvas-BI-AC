package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.WorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Lowers the chunk send distance of players that are underground.
 * <p>
 * The decision is made synchronously on the player's region thread and fed into the player chunk loader through
 * {@link PlayerVisibility#sendDistanceCap()}, which recomputes the sent chunks (and tells the client about the new
 * radius) whenever the cap changes. Loading and ticking distances are untouched.
 * <p>
 * Walking in and out of caves is debounced, but discontinuities (joining, changing worlds, respawning, teleporting)
 * are evaluated immediately, before any chunk is sent for the new position. While the data needed to decide is
 * missing the player is assumed to be underground, so reconnecting never sends the full view distance first.
 * <p>
 * Every change of the cap makes the client forget or receive a lot of chunks, so leaving the underground state
 * needs a clearly shallower position than entering it (or sky light), a player walking through a cave right at the
 * configured depth doesn't flip between the two.
 */
public final class UndergroundViewDistance {

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_SURFACE = 1;
    private static final int STATE_UNDERGROUND = 2;

    /**
     * Moving further than this between two evaluations is treated as a teleport.
     */
    private static final double DISCONTINUITY_DISTANCE_SQUARED = 16.0 * 16.0;
    /**
     * How many blocks shallower than the surface depth an underground player needs to get to count as on the
     * surface again, unless the sky light reaches them.
     */
    private static final int EXIT_HYSTERESIS = 4;

    private final PlayerVisibility owner;

    private int state = STATE_UNKNOWN;
    /**
     * Whether the current state was assumed because the chunk of the player wasn't available yet, in which case
     * the first real evaluation is applied without delay.
     */
    private boolean assumed;
    private int pendingTicks;
    private int cap = Integer.MAX_VALUE;

    private @Nullable ServerLevel evaluatedLevel;
    private double evaluatedX;
    private double evaluatedY;
    private double evaluatedZ;

    UndergroundViewDistance(final PlayerVisibility owner) {
        this.owner = owner;
    }

    void reset() {
        this.state = STATE_UNKNOWN;
        this.assumed = false;
        this.pendingTicks = 0;
        this.cap = Integer.MAX_VALUE;
        this.evaluatedLevel = null;
    }

    void tick(final WorldConfig.Anticheat.UndergroundViewDistance config) {
        if (!this.isActive(config)) {
            this.reset();
            return;
        }

        final boolean discontinuity = this.isDiscontinuity();
        final int now = this.evaluate(config, !discontinuity && !this.assumed && this.state == STATE_UNDERGROUND);
        if (this.state == STATE_UNKNOWN || discontinuity || (this.assumed && now != STATE_UNKNOWN)) {
            this.applyImmediately(now, config);
            return;
        }
        if (now == STATE_UNKNOWN || now == this.state) {
            this.pendingTicks = 0;
            return;
        }

        final int delay = now == STATE_UNDERGROUND ? config.enterDelayTicks : config.exitDelayTicks;
        if (++this.pendingTicks > delay) {
            this.setState(now, false, config);
        }
    }

    /**
     * Queried by the player chunk loader whenever it recomputes the view distances, including when the player is
     * added to a world. Discontinuities are resolved right away so no chunks are sent for a stale state.
     */
    int sendDistanceCap() {
        if (this.state == STATE_UNKNOWN || this.isDiscontinuity()) {
            final WorldConfig.Anticheat.UndergroundViewDistance config = this.owner.config().undergroundViewDistance;
            if (this.isActive(config)) {
                this.applyImmediately(this.evaluate(config, false), config);
            } else {
                this.reset();
            }
        }
        return this.cap;
    }

    public boolean isUnderground() {
        return this.state == STATE_UNDERGROUND;
    }

    private boolean isActive(final WorldConfig.Anticheat.UndergroundViewDistance config) {
        return config.enabled && !this.owner.player.isSpectator() && !this.owner.bypassesUndergroundViewDistance();
    }

    private boolean isDiscontinuity() {
        final ServerPlayer player = this.owner.player;
        if (player.level() != this.evaluatedLevel) {
            return true;
        }
        final double dx = player.getX() - this.evaluatedX;
        final double dy = player.getY() - this.evaluatedY;
        final double dz = player.getZ() - this.evaluatedZ;
        return dx * dx + dy * dy + dz * dz > DISCONTINUITY_DISTANCE_SQUARED;
    }

    private void applyImmediately(final int now, final WorldConfig.Anticheat.UndergroundViewDistance config) {
        // fail closed while the player's chunk isn't available
        this.setState(now == STATE_UNKNOWN ? STATE_UNDERGROUND : now, now == STATE_UNKNOWN, config);
    }

    private void setState(final int state, final boolean assumed, final WorldConfig.Anticheat.UndergroundViewDistance config) {
        this.state = state;
        this.assumed = assumed;
        this.pendingTicks = 0;
        this.cap = state == STATE_UNDERGROUND ? config.viewDistance : Integer.MAX_VALUE;
    }

    /**
     * @param underground whether the player is underground and only moved continuously since, which makes leaving
     *                    the underground state harder
     */
    private int evaluate(final WorldConfig.Anticheat.UndergroundViewDistance config, final boolean underground) {
        final ServerPlayer player = this.owner.player;
        final ServerLevel level = player.level();
        this.evaluatedLevel = level;
        this.evaluatedX = player.getX();
        this.evaluatedY = player.getY();
        this.evaluatedZ = player.getZ();

        if (level.dimensionType().hasCeiling() || !level.dimensionType().hasSkyLight()) {
            // the heightmap of a roofed dimension is its ceiling, everything would count as underground, and without
            // sky light (the end) the light test can't tell caves from the space under an overhang
            return STATE_SURFACE;
        }
        if (player.isEyeInFluid(FluidTags.WATER)) {
            // fog limits the view under water anyway, and conduit power users would notice the reduced distance
            return STATE_SURFACE;
        }

        final int x = Mth.floor(player.getX());
        final int z = Mth.floor(player.getZ());
        final LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(x >> 4, z >> 4);
        if (chunk == null) {
            return STATE_UNKNOWN;
        }

        final double eyeY = player.getEyeY();
        final int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        final int depth = underground ? Math.max(0, config.surfaceDepth - EXIT_HYSTERESIS) : config.surfaceDepth;
        if (eyeY + depth > surface) {
            return STATE_SURFACE;
        }
        return level.getBrightness(LightLayer.SKY, BlockPos.containing(player.getX(), eyeY, player.getZ())) > 0 ? STATE_SURFACE : STATE_UNDERGROUND;
    }
}
