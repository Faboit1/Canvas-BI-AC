package io.canvasmc.canvas.anticheat.freecam;

import io.papermc.paper.antixray.ChunkPacketInfo;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jspecify.annotations.Nullable;

/**
 * Writes masked chunk sections into chunk packets and strips their light.
 * <p>
 * A masked section is sent as a section completely filled with the natural stone of its dimension, using the
 * single value palette, so it costs a few bytes and, having no bits per block, is skipped by Paper's anti-xray.
 * Air is never used as filler: players would fall into it, and light would outline caves. Masked sections get the
 * biomes of the chunk's top section, which is never masked, since underground biomes (deep dark, lush caves) would
 * give away what lies below, unless they are next to a section with visible blocks: the client spreads biomes over a
 * few blocks (lookup jitter, color blending), so those decide the colors of visible blocks.
 * <p>
 * Per-block anti-freecam sends sections the player can only see part of with every other block replaced by the
 * same filler ({@link #maskBlocks}), and zeroes the light of those blocks.
 * <p>
 * Light sections are indexed from one section below the world, so light index {@code i} is chunk section
 * {@code i - 1}.
 */
public final class ChunkMasking {

    private static final int STONE = Block.getId(Blocks.STONE.defaultBlockState());
    private static final int DEEPSLATE = Block.getId(Blocks.DEEPSLATE.defaultBlockState());
    private static final int NETHERRACK = Block.getId(Blocks.NETHERRACK.defaultBlockState());
    private static final int END_STONE = Block.getId(Blocks.END_STONE.defaultBlockState());

    private ChunkMasking() {
    }

    /**
     * @return whether the block height lies in a masked section of the chunk
     */
    public static boolean isMasked(final long maskedSections, final LevelChunk chunk, final int y) {
        final int sectionIndex = (y >> 4) - chunk.getMinSectionY();
        return sectionIndex >= 0 && sectionIndex < 64 && (maskedSections >>> sectionIndex & 1L) != 0L;
    }

    private static int fillerId(final Level level, final int sectionY) {
        return switch (level.getWorld().getEnvironment()) {
            case NETHER -> NETHERRACK;
            case THE_END -> END_STONE;
            default -> sectionY < 0 ? DEEPSLATE : STONE;
        };
    }

    /**
     * @return whether the block is filler in the chunk packet: in a masked section, or not visible in a section
     * that is partly masked
     */
    public static boolean isMasked(final long maskedSections, final long @Nullable [] @Nullable [] visibleCells, final LevelChunk chunk,
                                   final int x, final int y, final int z) {
        if (isMasked(maskedSections, chunk, y)) {
            return true;
        }
        final int sectionIndex = (y >> 4) - chunk.getMinSectionY();
        if (visibleCells == null || sectionIndex < 0 || sectionIndex >= visibleCells.length) {
            return false;
        }
        final long[] visible = visibleCells[sectionIndex];
        final int index = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        return visible != null && (visible[index >>> 6] >>> index & 1L) == 0L;
    }

    /**
     * A section with the blocks the player can't see replaced by filler, ready to be written into a chunk packet.
     */
    public static final class MaskedBlocks {

        private final PalettedContainer<BlockState> states;
        private final PalettedContainerRO<Holder<Biome>> biomes;
        private final short nonEmptyBlockCount;
        private final short fluidCount;

        MaskedBlocks(final PalettedContainer<BlockState> states, final PalettedContainerRO<Holder<Biome>> biomes,
                     final short nonEmptyBlockCount, final short fluidCount) {
            this.states = states;
            this.biomes = biomes;
            this.nonEmptyBlockCount = nonEmptyBlockCount;
            this.fluidCount = fluidCount;
        }

        /**
         * @return the size of the section once written
         */
        public int size() {
            return 4 + this.states.getSerializedSize() + this.biomes.getSerializedSize();
        }

        /**
         * Writes the section in the network format of {@link LevelChunkSection#write}. The blocks go through Paper's
         * anti-xray like those of any other section, the filler counting as an ordinary opaque block.
         */
        public void write(final FriendlyByteBuf buffer, final @Nullable ChunkPacketInfo<BlockState> chunkPacketInfo, final int sectionIndex) {
            buffer.writeShort(this.nonEmptyBlockCount);
            buffer.writeShort(this.fluidCount);
            this.states.write(buffer, chunkPacketInfo, sectionIndex);
            this.biomes.write(buffer, null, sectionIndex);
        }
    }

    /**
     * Copies the blocks of a section, replacing every block that isn't visible by filler. Runs on the thread owning
     * the chunk.
     *
     * @param visible the visible blocks, indexed by {@code x | z << 4 | y << 8}
     */
    public static MaskedBlocks maskBlocks(final LevelChunk chunk, final LevelChunkSection section, final int sectionIndex, final long[] visible) {
        final BlockState filler = Block.stateById(fillerId(chunk.getLevel(), chunk.getMinSectionY() + sectionIndex));
        final PalettedContainer<BlockState> states = section.getStates().copy();
        // adds the filler to the palette up front, so ids stay valid while the storage is rewritten directly
        states.data.palette().idFor(filler, states);
        final PalettedContainer.Data<BlockState> data = states.data;
        final int fillerId = data.palette().idFor(filler, PaletteResize.noResizeExpected());
        int nonEmpty = section.canvas$nonEmptyBlockCount();
        int fluids = section.canvas$fluidCount();
        for (int word = 0; word < CellVisibility.WORDS; ++word) {
            for (long hidden = ~visible[word]; hidden != 0L; hidden &= hidden - 1L) {
                final int index = word << 6 | Long.numberOfTrailingZeros(hidden);
                final int id = data.storage().get(index);
                if (id == fillerId) {
                    continue;
                }
                final BlockState old = data.palette().valueFor(id);
                data.storage().set(index, fillerId);
                if (old.isAir()) {
                    ++nonEmpty;
                }
                if (!old.getFluidState().isEmpty()) {
                    --fluids;
                }
            }
        }

        // a section with visible blocks keeps its biomes, the client spreads them over a few blocks
        final PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
        return new MaskedBlocks(states, biomes, (short) nonEmpty, (short) fluids);
    }

    /**
     * @return the size of the section once written by {@link #writeMaskedSection}
     */
    public static int maskedSectionSize(final LevelChunk chunk, final LevelChunkSection section, final int sectionIndex, final boolean maskBiomes) {
        final int filler = fillerId(chunk.getLevel(), chunk.getMinSectionY() + sectionIndex);
        // block count, fluid count, bits per entry, palette entry, then the biomes
        return 2 + 2 + 1 + VarInt.getByteSize(filler) + (maskBiomes ? biomeSource(chunk) : section).getBiomes().getSerializedSize();
    }

    private static LevelChunkSection biomeSource(final LevelChunk chunk) {
        final LevelChunkSection[] sections = chunk.getSections();
        return sections[sections.length - 1];
    }

    /**
     * Writes the section as solid filler, in the network format of {@link LevelChunkSection#write}.
     *
     * @param maskBiomes whether to write the biomes of the top section instead of the section's own, which only
     *                   sections far enough from anything visible may get: the client spreads biomes over a few blocks
     */
    public static void writeMaskedSection(final FriendlyByteBuf buffer, final LevelChunk chunk, final LevelChunkSection section, final int sectionIndex, final boolean maskBiomes) {
        buffer.writeShort(16 * 16 * 16);
        buffer.writeShort(0);
        // single value palette: no bits per entry and no data array
        buffer.writeByte(0);
        buffer.writeVarInt(fillerId(chunk.getLevel(), chunk.getMinSectionY() + sectionIndex));
        (maskBiomes ? biomeSource(chunk) : section).getBiomes().write(buffer, null, sectionIndex);
    }

    private static boolean isMaskedLightIndex(final long maskedSections, final int lightIndex) {
        final int sectionIndex = lightIndex - 1;
        return sectionIndex >= 0 && sectionIndex < 64 && (maskedSections >>> sectionIndex & 1L) != 0L;
    }

    /**
     * Light of a chunk packet: masked sections that have light are sent as completely dark, like the filler would
     * be. Sections without light data stay without, so the client doesn't propagate darkness below them. Blocks
     * that are filler in partly masked sections are dark as well, so light can't outline what they hide.
     */
    public static ClientboundLightUpdatePacketData maskLight(final ClientboundLightUpdatePacketData data, final long maskedSections) {
        return maskLight(data, maskedSections, null);
    }

    public static ClientboundLightUpdatePacketData maskLight(final ClientboundLightUpdatePacketData data, final long maskedSections,
                                                             final long @Nullable [] @Nullable [] visibleCells) {
        if (maskedSections == 0L && visibleCells == null) {
            return data;
        }
        final BitSet skyYMask = new BitSet();
        final BitSet emptySkyYMask = (BitSet) data.emptySkyYMask().clone();
        final List<byte[]> skyUpdates = new ArrayList<>(data.skyUpdates().size());
        darken(data.skyYMask(), data.skyUpdates(), maskedSections, visibleCells, skyYMask, emptySkyYMask, skyUpdates);
        final BitSet blockYMask = new BitSet();
        final BitSet emptyBlockYMask = (BitSet) data.emptyBlockYMask().clone();
        final List<byte[]> blockUpdates = new ArrayList<>(data.blockUpdates().size());
        darken(data.blockYMask(), data.blockUpdates(), maskedSections, visibleCells, blockYMask, emptyBlockYMask, blockUpdates);
        return new ClientboundLightUpdatePacketData(skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask, skyUpdates, blockUpdates);
    }

    private static void darken(final BitSet mask, final List<byte[]> updates, final long maskedSections, final long @Nullable [] @Nullable [] visibleCells,
                               final BitSet newMask, final BitSet newEmptyMask, final List<byte[]> newUpdates) {
        int update = 0;
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            final byte[] data = updates.get(update++);
            if (isMaskedLightIndex(maskedSections, lightIndex)) {
                newEmptyMask.set(lightIndex);
            } else {
                newMask.set(lightIndex);
                newUpdates.add(darkenHidden(data, visibleCells, lightIndex));
            }
        }
    }

    /**
     * @return the light of a section with the light of every block that isn't visible set to zero, a copy if
     * anything had to change
     */
    private static byte[] darkenHidden(final byte[] light, final long @Nullable [] @Nullable [] visibleCells, final int lightIndex) {
        final int sectionIndex = lightIndex - 1;
        if (visibleCells == null || sectionIndex < 0 || sectionIndex >= visibleCells.length) {
            return light;
        }
        final long[] visible = visibleCells[sectionIndex];
        if (visible == null) {
            return light;
        }
        // nibbles are indexed like the visible blocks, two blocks per byte, low nibble first
        final byte[] ret = light.clone();
        for (int word = 0; word < CellVisibility.WORDS; ++word) {
            final long bits = visible[word];
            if (bits == -1L) {
                continue;
            }
            for (int pair = 0; pair < 32; ++pair) {
                final int two = (int) (bits >>> (pair << 1)) & 3;
                if (two != 3) {
                    final int at = (word << 5) | pair;
                    ret[at] &= (byte) (((two & 1) != 0 ? 0x0F : 0) | ((two & 2) != 0 ? 0xF0 : 0));
                }
            }
        }
        return ret;
    }

    /**
     * Light update of loaded chunks: masked sections are left out entirely, so the client keeps the darkness it
     * got with the chunk, and blocks that are filler in partly masked sections stay dark.
     *
     * @return the packet itself if no masked section is updated, otherwise a filtered copy, or {@code null} if
     * nothing is left
     */
    public static @Nullable ClientboundLightUpdatePacket filterLight(final ClientboundLightUpdatePacket packet, final long maskedSections) {
        return filterLight(packet, maskedSections, null);
    }

    public static @Nullable ClientboundLightUpdatePacket filterLight(final ClientboundLightUpdatePacket packet, final long maskedSections,
                                                                     final long @Nullable [] @Nullable [] visibleCells) {
        final ClientboundLightUpdatePacketData data = packet.lightData();
        if (!touches(data.skyYMask(), maskedSections) && !touches(data.blockYMask(), maskedSections)
            && !touches(data.emptySkyYMask(), maskedSections) && !touches(data.emptyBlockYMask(), maskedSections)
            && !touchesPartial(data.skyYMask(), visibleCells) && !touchesPartial(data.blockYMask(), visibleCells)) {
            return packet;
        }
        final BitSet skyYMask = new BitSet();
        final List<byte[]> skyUpdates = new ArrayList<>(data.skyUpdates().size());
        keepUnmasked(data.skyYMask(), data.skyUpdates(), maskedSections, visibleCells, skyYMask, skyUpdates);
        final BitSet blockYMask = new BitSet();
        final List<byte[]> blockUpdates = new ArrayList<>(data.blockUpdates().size());
        keepUnmasked(data.blockYMask(), data.blockUpdates(), maskedSections, visibleCells, blockYMask, blockUpdates);
        final BitSet emptySkyYMask = withoutMasked(data.emptySkyYMask(), maskedSections);
        final BitSet emptyBlockYMask = withoutMasked(data.emptyBlockYMask(), maskedSections);
        if (skyYMask.isEmpty() && blockYMask.isEmpty() && emptySkyYMask.isEmpty() && emptyBlockYMask.isEmpty()) {
            return null;
        }
        return new ClientboundLightUpdatePacket(packet.x(), packet.z(),
            new ClientboundLightUpdatePacketData(skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask, skyUpdates, blockUpdates));
    }

    private static boolean touchesPartial(final BitSet mask, final long @Nullable [] @Nullable [] visibleCells) {
        if (visibleCells == null) {
            return false;
        }
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            final int sectionIndex = lightIndex - 1;
            if (sectionIndex >= 0 && sectionIndex < visibleCells.length && visibleCells[sectionIndex] != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean touches(final BitSet mask, final long maskedSections) {
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            if (isMaskedLightIndex(maskedSections, lightIndex)) {
                return true;
            }
        }
        return false;
    }

    private static void keepUnmasked(final BitSet mask, final List<byte[]> updates, final long maskedSections,
                                     final long @Nullable [] @Nullable [] visibleCells, final BitSet newMask, final List<byte[]> newUpdates) {
        int update = 0;
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            final byte[] data = updates.get(update++);
            if (!isMaskedLightIndex(maskedSections, lightIndex)) {
                newMask.set(lightIndex);
                newUpdates.add(darkenHidden(data, visibleCells, lightIndex));
            }
        }
    }

    private static BitSet withoutMasked(final BitSet mask, final long maskedSections) {
        final BitSet ret = (BitSet) mask.clone();
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            if (isMaskedLightIndex(maskedSections, lightIndex)) {
                ret.clear(lightIndex);
            }
        }
        return ret;
    }
}
