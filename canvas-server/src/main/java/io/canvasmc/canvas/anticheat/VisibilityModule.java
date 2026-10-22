package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;

/**
 * A per-player visibility feature driven by {@link PlayerVisibility}.
 * <p>
 * Threading contract:
 * <ul>
 *     <li>Every method except {@link #runAsync} is called on the region thread owning the player.</li>
 *     <li>{@link #runAsync} is called on an anticheat worker thread, at most once at a time per player. Everything
 *     written by {@link #prepareAsync} is visible to it, and everything it writes is visible to the following
 *     {@link #applyResults} call. Jobs of different modules run independently of each other, possibly at the same
 *     time.</li>
 *     <li>{@link #applyResults} is only called for jobs of the current epoch, results of older epochs are dropped
 *     by not calling it (the module is {@link #reset} instead).</li>
 * </ul>
 */
public interface VisibilityModule {

    /**
     * @return whether the module is enabled for the player right now (world config, gamemode, bypass permission)
     */
    boolean isActive(WorldConfig.Anticheat config);

    /**
     * Called when the module stops being active, or the player's state is reset (world change, respawn). Must drop
     * all state, and undo anything that would otherwise keep information hidden from the client.
     * <p>
     * This may be called while {@link #runAsync} of an older epoch is still running on a worker thread, so
     * {@link #runAsync} must only read state that tolerates concurrent modification and must only write its results
     * into fields that {@link #applyResults} reads (which will not be called for that job).
     */
    void reset();

    /**
     * Applies the results of the last finished {@link #runAsync} call.
     */
    void applyResults(CameraSnapshot snapshot);

    /**
     * Called every tick while the module is active, after finished results were applied, also while a job is
     * running. For synchronous work that must not wait for a job, it must not touch the inputs or results of the
     * running job.
     */
    default void tick(final CameraSnapshot snapshot) {
    }

    /**
     * Called every tick while the module is not active, after {@link #reset} was called on deactivation. For undoing
     * work that is too expensive to do all at once in {@link #reset}, like resending chunks.
     */
    default void tickInactive() {
    }

    /**
     * Whether players notice late results of this module, so its jobs run on the urgent worker pool, which jobs of
     * the other modules can't hold up.
     */
    default boolean isLatencySensitive() {
        return true;
    }

    /**
     * Called every tick while no job of this module is running for this player.
     *
     * @return whether asynchronous work is needed now. If so, inputs for {@link #runAsync} should be copied here
     */
    boolean prepareAsync(CameraSnapshot snapshot);

    /**
     * Performs the expensive part of the visibility computation off the region thread.
     */
    void runAsync(CameraSnapshot snapshot, OcclusionAccess access);
}
