package io.canvasmc.canvas.anticheat.raytrace;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import org.jspecify.annotations.Nullable;

/**
 * Reads whether blocks occlude vision, straight from the loaded chunks of a level.
 * <p>
 * This is meant to be used from the anticheat worker threads. Chunk lookups go through the concurrent full
 * chunk map, and block reads are plain racy reads of the paletted containers: a concurrent modification can at
 * worst make us see the previous state of a block, which is corrected by the next visibility pass.
 * <p>
 * A block occludes when it renders as a full opaque cube ({@link BlockState#isSolidRender()}), which is exactly
 * the predicate the client uses for face culling and its section visibility graph. Instances cache the last
 * accessed chunk and are therefore not thread safe, use one instance per job.
 */
public final class OcclusionAccess {

    private static final BlockState LAVA_SOURCE = Blocks.LAVA.defaultBlockState();

    private final ServerLevel level;
    private final ConcurrentChainedLong2ReferenceHashTable<LevelChunk> chunks;
    private final int minY;
    private final int maxY;
    private final int minSectionY;
    private boolean lavaObscures;

    private long cachedChunkKey = Long.MIN_VALUE;
    private LevelChunkSection @Nullable [] cachedSections;

    public OcclusionAccess(final ServerLevel level) {
        this.level = level;
        this.chunks = level.getChunkSource().fullChunks;
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
        this.minSectionY = level.getMinSectionY();
    }

    public ServerLevel level() {
        return this.level;
    }

    /**
     * Whether stationary lava counts as occluding, mirrors paper's "lava-obscures" anti-xray option.
     */
    public void setLavaObscures(final boolean lavaObscures) {
        this.lavaObscures = lavaObscures;
    }

    /**
     * @return the sections of the given chunk, or {@code null} if the chunk is not loaded
     */
    public LevelChunkSection @Nullable [] sections(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        if (key == this.cachedChunkKey) {
            return this.cachedSections;
        }
        final LevelChunk chunk = this.chunks.get(key);
        final LevelChunkSection[] sections = chunk == null ? null : chunk.getSections();
        this.cachedChunkKey = key;
        this.cachedSections = sections;
        return sections;
    }

    /**
     * Unloaded chunks are treated as occluding, so missing data never reveals anything. Positions outside the
     * build height are air.
     */
    public boolean isOpaque(final int x, final int y, final int z) {
        if (y < this.minY || y > this.maxY) {
            return false;
        }
        final LevelChunkSection[] sections = this.sections(x >> 4, z >> 4);
        if (sections == null) {
            return true;
        }
        final BlockState state = readState(sections[(y >> 4) - this.minSectionY], x, y, z);
        return state != null && (state.isSolidRender() || (this.lavaObscures && state == LAVA_SOURCE));
    }

    /**
     * @return the block state, or {@code null} if the chunk isn't loaded or the read raced with a palette resize
     */
    public @Nullable BlockState getBlockState(final int x, final int y, final int z) {
        if (y < this.minY || y > this.maxY) {
            return null;
        }
        final LevelChunkSection[] sections = this.sections(x >> 4, z >> 4);
        return sections == null ? null : readState(sections[(y >> 4) - this.minSectionY], x, y, z);
    }

    private static @Nullable BlockState readState(final LevelChunkSection section, final int x, final int y, final int z) {
        try {
            return section.getBlockState(x & 15, y & 15, z & 15);
        } catch (final MissingPaletteEntryException ignored) {
            // racing a palette resize on the owning region thread, the next pass sees the new state
            return null;
        }
    }
}
