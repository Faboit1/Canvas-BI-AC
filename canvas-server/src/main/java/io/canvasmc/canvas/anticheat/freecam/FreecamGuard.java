package io.canvasmc.canvas.anticheat.freecam;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Per-player anti-freecam: decides which chunk sections are sent masked and re-sends chunks once masked sections
 * become potentially visible.
 */
public final class FreecamGuard implements VisibilityModule {

    private final PlayerVisibility owner;

    public FreecamGuard(final PlayerVisibility owner) {
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

    public ClientboundLevelChunkWithLightPacket createChunkPacket(final LevelChunk chunk, final boolean modifyBlocks) {
        return new ClientboundLevelChunkWithLightPacket(chunk, chunk.getLevel().getLightEngine(), null, null, modifyBlocks);
    }

    public void onChunkSent(final LevelChunk chunk, final ClientboundLevelChunkWithLightPacket packet) {
    }

    public void onChunkUnload(final int chunkX, final int chunkZ) {
    }

    public boolean isMasked(final int x, final int y, final int z) {
        return false;
    }

    public @Nullable ClientboundLightUpdatePacket filterLightUpdate(final ClientboundLightUpdatePacket packet) {
        return packet;
    }

    public void onTeleport() {
    }
}
