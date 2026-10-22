package io.canvasmc.canvas.anticheat.freecam;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

/**
 * Writes masked chunk sections into chunk packets and strips their light.
 * <p>
 * A masked section is sent as a section completely filled with the natural stone of its dimension, using the
 * single value palette, so it costs a few bytes and, having no bits per block, is skipped by Paper's anti-xray.
 * Biomes stay real since the client needs them for fog and colors of the surrounding sections. Air is never used as
 * filler: players would fall into it, and light would outline caves.
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
     * @return the size of the section once written by {@link #writeMaskedSection}
     */
    public static int maskedSectionSize(final LevelChunk chunk, final LevelChunkSection section, final int sectionIndex) {
        final int filler = fillerId(chunk.getLevel(), chunk.getMinSectionY() + sectionIndex);
        // block count, fluid count, bits per entry, palette entry, then the real biomes
        return 2 + 2 + 1 + VarInt.getByteSize(filler) + section.getBiomes().getSerializedSize();
    }

    /**
     * Writes the section as solid filler, in the network format of {@link LevelChunkSection#write}.
     */
    public static void writeMaskedSection(final FriendlyByteBuf buffer, final LevelChunk chunk, final LevelChunkSection section, final int sectionIndex) {
        buffer.writeShort(16 * 16 * 16);
        buffer.writeShort(0);
        // single value palette: no bits per entry and no data array
        buffer.writeByte(0);
        buffer.writeVarInt(fillerId(chunk.getLevel(), chunk.getMinSectionY() + sectionIndex));
        section.getBiomes().write(buffer, null, sectionIndex);
    }

    private static boolean isMaskedLightIndex(final long maskedSections, final int lightIndex) {
        final int sectionIndex = lightIndex - 1;
        return sectionIndex >= 0 && sectionIndex < 64 && (maskedSections >>> sectionIndex & 1L) != 0L;
    }

    /**
     * Light of a chunk packet: masked sections that have light are sent as completely dark, like the filler would
     * be. Sections without light data stay without, so the client doesn't propagate darkness below them.
     */
    public static ClientboundLightUpdatePacketData maskLight(final ClientboundLightUpdatePacketData data, final long maskedSections) {
        if (maskedSections == 0L) {
            return data;
        }
        final BitSet skyYMask = new BitSet();
        final BitSet emptySkyYMask = (BitSet) data.getEmptySkyYMask().clone();
        final List<byte[]> skyUpdates = new ArrayList<>(data.getSkyUpdates().size());
        darken(data.getSkyYMask(), data.getSkyUpdates(), maskedSections, skyYMask, emptySkyYMask, skyUpdates);
        final BitSet blockYMask = new BitSet();
        final BitSet emptyBlockYMask = (BitSet) data.getEmptyBlockYMask().clone();
        final List<byte[]> blockUpdates = new ArrayList<>(data.getBlockUpdates().size());
        darken(data.getBlockYMask(), data.getBlockUpdates(), maskedSections, blockYMask, emptyBlockYMask, blockUpdates);
        return new ClientboundLightUpdatePacketData(skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask, skyUpdates, blockUpdates);
    }

    private static void darken(final BitSet mask, final List<byte[]> updates, final long maskedSections,
                               final BitSet newMask, final BitSet newEmptyMask, final List<byte[]> newUpdates) {
        int update = 0;
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            final byte[] data = updates.get(update++);
            if (isMaskedLightIndex(maskedSections, lightIndex)) {
                newEmptyMask.set(lightIndex);
            } else {
                newMask.set(lightIndex);
                newUpdates.add(data);
            }
        }
    }

    /**
     * Light update of loaded chunks: masked sections are left out entirely, so the client keeps the darkness it
     * got with the chunk.
     *
     * @return the packet itself if no masked section is updated, otherwise a filtered copy, or {@code null} if
     * nothing is left
     */
    public static @Nullable ClientboundLightUpdatePacket filterLight(final ClientboundLightUpdatePacket packet, final long maskedSections) {
        final ClientboundLightUpdatePacketData data = packet.getLightData();
        if (!touches(data.getSkyYMask(), maskedSections) && !touches(data.getBlockYMask(), maskedSections)
            && !touches(data.getEmptySkyYMask(), maskedSections) && !touches(data.getEmptyBlockYMask(), maskedSections)) {
            return packet;
        }
        final BitSet skyYMask = new BitSet();
        final List<byte[]> skyUpdates = new ArrayList<>(data.getSkyUpdates().size());
        keepUnmasked(data.getSkyYMask(), data.getSkyUpdates(), maskedSections, skyYMask, skyUpdates);
        final BitSet blockYMask = new BitSet();
        final List<byte[]> blockUpdates = new ArrayList<>(data.getBlockUpdates().size());
        keepUnmasked(data.getBlockYMask(), data.getBlockUpdates(), maskedSections, blockYMask, blockUpdates);
        final BitSet emptySkyYMask = withoutMasked(data.getEmptySkyYMask(), maskedSections);
        final BitSet emptyBlockYMask = withoutMasked(data.getEmptyBlockYMask(), maskedSections);
        if (skyYMask.isEmpty() && blockYMask.isEmpty() && emptySkyYMask.isEmpty() && emptyBlockYMask.isEmpty()) {
            return null;
        }
        return new ClientboundLightUpdatePacket(packet.getX(), packet.getZ(),
            new ClientboundLightUpdatePacketData(skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask, skyUpdates, blockUpdates));
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
                                     final BitSet newMask, final List<byte[]> newUpdates) {
        int update = 0;
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            final byte[] data = updates.get(update++);
            if (!isMaskedLightIndex(maskedSections, lightIndex)) {
                newMask.set(lightIndex);
                newUpdates.add(data);
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
