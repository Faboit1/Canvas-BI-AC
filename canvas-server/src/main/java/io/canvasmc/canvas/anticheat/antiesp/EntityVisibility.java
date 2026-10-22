package io.canvasmc.canvas.anticheat.antiesp;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import net.minecraft.world.entity.Entity;

/**
 * Per-player ray traced anti-ESP, consulted by the entity tracker.
 */
public final class EntityVisibility implements VisibilityModule {

    private final PlayerVisibility owner;

    public EntityVisibility(final PlayerVisibility owner) {
        this.owner = owner;
    }

    @Override
    public boolean isActive(final WorldConfig.Anticheat config) {
        return false;
    }

    @Override
    public void reset() {
    }

    @Override
    public void applyResults(final CameraSnapshot snapshot) {
    }

    @Override
    public boolean prepareAsync(final CameraSnapshot snapshot) {
        return false;
    }

    @Override
    public void runAsync(final CameraSnapshot snapshot, final OcclusionAccess access) {
    }

    public boolean canSee(final Entity entity) {
        return true;
    }
}
