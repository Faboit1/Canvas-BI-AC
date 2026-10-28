package io.canvasmc.canvas.anticheat.disguise;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A module that sends blocks to a player as something else, like anti-bud and storage hiding. {@link BlockDisguises}
 * puts the disguises of all modules into one chunk packet, and computes the light that goes with all of them at once:
 * light lowered by two modules next to each other must be computed together.
 */
public interface DisguiseSource {

    /**
     * Means the block is sent with its real light, see {@link #sentEmission}.
     */
    int REAL = -1;

    /**
     * Adds the blocks of the chunk that are sent as something else, and the blocks of other chunks around it whose
     * lowered light reaches into it, and records what the client gets. Called on the thread owning the chunk right
     * before its packet is created.
     */
    void prepareChunk(LevelChunk chunk, ChunkRewrite.Builder builder);

    /**
     * @return the light the block at the position is sent with to the player, or {@link #REAL} if it is sent as it
     * is. Called from any thread, for blocks of the kind this source collects for light updates
     */
    int sentEmission(int x, int y, int z, BlockState state);

    /**
     * Receives blocks found in the world.
     */
    @FunctionalInterface
    interface Collector {
        void add(int x, int y, int z, BlockState state);
    }
}
