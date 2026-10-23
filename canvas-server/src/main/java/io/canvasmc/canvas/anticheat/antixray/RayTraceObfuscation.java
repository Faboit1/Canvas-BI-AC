package io.canvasmc.canvas.anticheat.antixray;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.AnticheatExecutor;
import io.papermc.paper.antixray.ChunkPacketBlockControllerAntiXray;
import io.papermc.paper.antixray.ChunkPacketInfoAntiXray;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import org.jspecify.annotations.Nullable;

/**
 * The ray traced part of Paper's anti-xray obfuscation, compiled from the configuration of one world.
 * <p>
 * Paper's engine-mode 1 only replaces hidden blocks that aren't exposed to air. Once Paper is done with a chunk
 * packet, every traced block that is still in it is therefore exposed (or in a layer Paper doesn't process). Those
 * are replaced by the same block Paper hides with, up to a configured amount per chunk counting from the bottom, and
 * their positions are recorded on the {@link ChunkPacketInfoAntiXray} so that {@link RayTraceAntiXray} can reveal
 * them to the player once they become visible. Block entities of every block that ends up hidden, by Paper or by us,
 * are stripped from the packet, which defeats chest ESP and the block entity counter of the debug screen.
 * <p>
 * This runs as a separate pass after Paper's obfuscation instead of inside it, so Paper's hot loop stays untouched.
 * The pass only reads the sections whose palette contains a traced block. Instances are immutable and shared by all
 * threads obfuscating chunks of a world.
 */
public final class RayTraceObfuscation {

    private static final int[] NO_POSITIONS = new int[0];
    private static final int SECTION_BLOCKS = 16 * 16 * 16;
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final ThreadLocal<int[]> POSITIONS = ThreadLocal.withInitial(() -> new int[128]);

    /**
     * The configuration this was compiled from, compared by identity to notice reloads.
     */
    public final WorldConfig.Anticheat.RayTraceAntiXray config;
    private final boolean[] trace;
    private final boolean[] obfuscate;
    private final int maxBlockCount;

    /**
     * @param paperObfuscate the states Paper hides, indexed by global block state id
     */
    public RayTraceObfuscation(final WorldConfig.Anticheat.RayTraceAntiXray config, final boolean[] paperObfuscate) {
        this.config = config;
        this.maxBlockCount = config.maxRayTraceBlockCountPerChunk;
        if (config.rayTraceBlocks.isEmpty()) {
            this.trace = paperObfuscate;
            this.obfuscate = paperObfuscate;
            return;
        }

        this.trace = new boolean[paperObfuscate.length];
        for (final String id : config.rayTraceBlocks) {
            final Identifier identifier = Identifier.tryParse(id);
            final Block block = identifier == null ? null : BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
            if (block == null) {
                AnticheatExecutor.LOGGER.warn("Ignoring unknown block \"{}\" in the ray traced anti-xray block list", id);
                continue;
            }
            // like paper, never hide air, it would cause needless updates and break updates in the void
            if (block.defaultBlockState().isAir()) {
                continue;
            }
            for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
                this.trace[Block.BLOCK_STATE_REGISTRY.getId(state)] = true;
            }
        }
        // traced blocks must be hidden by paper while they aren't exposed, otherwise they would count towards the limit
        this.obfuscate = paperObfuscate.clone();
        for (int i = 0; i < this.obfuscate.length; ++i) {
            this.obfuscate[i] |= this.trace[i];
        }
    }

    /**
     * @return the settings to obfuscate chunks of the level with, or {@code null} if ray traced anti-xray isn't
     * enabled or Paper's anti-xray doesn't run in engine-mode 1 there
     */
    public static @Nullable RayTraceObfuscation of(final ServerLevel level) {
        return level.chunkPacketBlockController instanceof final ChunkPacketBlockControllerAntiXray controller ? controller.rayTraceObfuscation(level) : null;
    }

    /**
     * The states Paper must hide while they aren't exposed: Paper's hidden blocks plus the traced blocks, indexed by
     * global block state id.
     */
    public boolean[] obfuscateGlobal() {
        return this.obfuscate;
    }

    /**
     * Whether the state belongs to a block that is hidden while exposed and revealed by ray tracing.
     */
    public boolean isTraced(final BlockState state) {
        final int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        return id >= 0 && this.trace[id];
    }

    /**
     * Hides the exposed traced blocks of an obfuscated chunk packet, records their positions on the info and strips
     * the block entities of all hidden blocks. Must be called after Paper's obfuscation and before the packet is
     * marked ready, which publishes the recorded positions to other threads.
     *
     * @param maxChunkSectionIndex the highest section index Paper's obfuscation considered
     */
    public void hideExposedBlocks(final ChunkPacketInfoAntiXray info, final int maxChunkSectionIndex) {
        final int minSectionY = info.getChunk().getMinSectionY();
        final int maxCount = Math.min(this.maxBlockCount, (maxChunkSectionIndex + 1) * SECTION_BLOCKS);
        int[] positions = POSITIONS.get();
        if (positions.length < maxCount) {
            POSITIONS.set(positions = new int[maxCount]);
        }
        int count = 0;
        try {
            boolean[] paletteTrace = null;
            for (int sectionIndex = 0; sectionIndex <= maxChunkSectionIndex && count < maxCount; ++sectionIndex) {
                final BlockState[] presets = info.getPresetValues(sectionIndex);
                if (!info.isWritten(sectionIndex) || presets == null) {
                    continue;
                }
                final Palette<BlockState> palette = info.getPalette(sectionIndex);
                final boolean[] trace;
                final int hideId;
                if (palette instanceof GlobalPalette) {
                    trace = this.trace;
                    hideId = Block.BLOCK_STATE_REGISTRY.getId(presets[0]);
                } else {
                    final int size = palette.getSize();
                    if (paletteTrace == null || paletteTrace.length < size) {
                        paletteTrace = new boolean[Math.max(size, 16)];
                    }
                    if (!this.readPalette(palette, size, paletteTrace)) {
                        continue;
                    }
                    trace = paletteTrace;
                    // paper added the preset to the palette of every section it obfuscates
                    hideId = palette.idFor(presets[0], PaletteResize.noResizeExpected());
                }
                count = hideSection(
                    info.getBuffer(), info.getIndex(sectionIndex), info.getBits(sectionIndex), trace, hideId,
                    (sectionIndex + minSectionY) << 4, positions, count, maxCount
                );
            }
            removeHiddenBlockEntities(info, maxChunkSectionIndex);
        } catch (final RuntimeException thrown) {
            // never keep the packet from becoming ready, the client would stop receiving packets
            AnticheatExecutor.LOGGER.error("Failed to hide exposed blocks of chunk {}", info.getChunk().getPos(), thrown);
        }
        info.setRayTraceHidden(count == 0 ? NO_POSITIONS : Arrays.copyOf(positions, count));
    }

    /**
     * Strips the block entities of every block Paper's obfuscation hid from an obfuscated chunk packet. Must be called
     * after the obfuscation and before the packet is marked ready.
     *
     * @param maxChunkSectionIndex the highest section index Paper's obfuscation considered
     */
    public static void removeHiddenBlockEntities(final ChunkPacketInfoAntiXray info, final int maxChunkSectionIndex) {
        final int minSectionY = info.getChunk().getMinSectionY();
        try {
            info.getChunkPacket().chunkData().canvas$removeBlockEntitiesIf(
                (final int relX, final int y, final int relZ) -> isHiddenInPacket(info, maxChunkSectionIndex, minSectionY, relX, y, relZ)
            );
        } catch (final RuntimeException thrown) {
            // never keep the packet from becoming ready, the client would stop receiving packets
            AnticheatExecutor.LOGGER.error("Failed to remove hidden block entities of chunk {}", info.getChunk().getPos(), thrown);
        }
    }

    /**
     * Maps the palette to whether each entry is traced.
     *
     * @return whether any entry is traced
     */
    private boolean readPalette(final Palette<BlockState> palette, final int size, final boolean[] out) {
        boolean any = false;
        int i = 0;
        try {
            for (; i < size; ++i) {
                final boolean traced = this.isTraced(palette.valueFor(i));
                out[i] = traced;
                any |= traced;
            }
        } catch (final MissingPaletteEntryException ignored) {
            // racing a palette resize of the owning region: the packet was written before, so it can't reference the
            // missing entries
            Arrays.fill(out, i, size, false);
        }
        return any;
    }

    /**
     * Whether the block of a block entity is hidden in the packet: the block entity exists, so the real block has
     * one, but the packet shows a block without.
     */
    private static boolean isHiddenInPacket(final ChunkPacketInfoAntiXray info, final int maxChunkSectionIndex, final int minSectionY,
                                            final int relX, final int y, final int relZ) {
        final int sectionIndex = (y >> 4) - minSectionY;
        if (sectionIndex < 0 || sectionIndex > maxChunkSectionIndex || !info.isWritten(sectionIndex) || info.getPresetValues(sectionIndex) == null) {
            return false;
        }
        final int value = readValue(info.getBuffer(), info.getIndex(sectionIndex), info.getBits(sectionIndex), (y & 15) << 8 | relZ << 4 | relX);
        try {
            return !info.getPalette(sectionIndex).valueFor(value).hasBlockEntity();
        } catch (final MissingPaletteEntryException ignored) {
            return false;
        }
    }

    /**
     * Replaces every traced value of a serialized section with the hide value, in order of ascending block index,
     * until {@code maxCount} positions are recorded. Values never span two longs, like {@code SimpleBitStorage}.
     *
     * @param dataIndex index of the first byte of the section's long array in the buffer
     * @param baseY the y coordinate of the bottom of the section
     * @param positions receives the packed positions (see {@link #pack}), must have room for {@code maxCount}
     * @return the new amount of recorded positions
     */
    static int hideSection(final byte[] buffer, final int dataIndex, final int bits, final boolean[] trace, final int hideId,
                           final int baseY, final int[] positions, int count, final int maxCount) {
        final long mask = (1L << bits) - 1L;
        final long hide = hideId & mask;
        final int valuesPerLong = 64 / bits;
        for (int index = 0, offset = dataIndex; index < SECTION_BLOCKS && count < maxCount; offset += Long.BYTES) {
            final long word = (long) LONGS.get(buffer, offset);
            long modified = word;
            final int end = Math.min(valuesPerLong, SECTION_BLOCKS - index) * bits;
            for (int shift = 0; shift < end; shift += bits, ++index) {
                if (trace[(int) (word >>> shift & mask)]) {
                    if (count == maxCount) {
                        break;
                    }
                    modified = modified & ~(mask << shift) | hide << shift;
                    positions[count++] = pack(baseY + (index >> 8), index >> 4 & 15, index & 15);
                }
            }
            if (modified != word) {
                LONGS.set(buffer, offset, modified);
            }
        }
        return count;
    }

    /**
     * Reads the value at the given block index of a serialized section.
     */
    static int readValue(final byte[] buffer, final int dataIndex, final int bits, final int index) {
        final int valuesPerLong = 64 / bits;
        final long word = (long) LONGS.get(buffer, dataIndex + (index / valuesPerLong) * Long.BYTES);
        return (int) (word >>> (index % valuesPerLong) * bits & ((1L << bits) - 1L));
    }

    /**
     * Packs a position inside a chunk. Packed positions sort by y first, then z, then x, the order in which
     * {@link #hideSection} records them, so recorded arrays are sorted and can be binary searched.
     */
    static int pack(final int y, final int relZ, final int relX) {
        return y << 8 | relZ << 4 | relX;
    }

    static int unpackY(final int packed) {
        return packed >> 8;
    }

    static int unpackRelZ(final int packed) {
        return packed >> 4 & 15;
    }

    static int unpackRelX(final int packed) {
        return packed & 15;
    }
}
