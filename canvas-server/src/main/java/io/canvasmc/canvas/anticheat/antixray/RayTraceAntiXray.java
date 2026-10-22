package io.canvasmc.canvas.anticheat.antixray;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Per-player ray traced anti-xray: tracks the hidden blocks of every chunk sent to the player and reveals them once
 * they become visible.
 */
public final class RayTraceAntiXray implements VisibilityModule {

    private final PlayerVisibility owner;

    public RayTraceAntiXray(final PlayerVisibility owner) {
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

    public void onChunkSent(final LevelChunk chunk, final ClientboundLevelChunkWithLightPacket packet) {
    }

    public void onChunkUnload(final int chunkX, final int chunkZ) {
    }

    public boolean isHidden(final int x, final int y, final int z, final BlockState state) {
        return false;
    }
}
