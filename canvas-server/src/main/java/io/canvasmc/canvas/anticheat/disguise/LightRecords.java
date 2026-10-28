package io.canvasmc.canvas.anticheat.disguise;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.jspecify.annotations.Nullable;

/**
 * The light one player's client has of the chunks with disguised blocks, as hashes per light section. When a disguised
 * block changes (a bud grows, a hidden furnace lights up) its real light changes, but the light sent for its disguise
 * doesn't, and the light engine sends unchanged sky light along: an update repeating what the client has would tell
 * that something changed there. Such sections are left out.
 * <p>
 * Read and written from any thread, light updates are broadcast by the region owning the chunk.
 */
public final class LightRecords {

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final ConcurrentChainedLong2ReferenceHashTable<Chunk> chunks = new ConcurrentChainedLong2ReferenceHashTable<>();

    /**
     * @return the records of the chunk, which start empty
     */
    Chunk chunk(final int chunkX, final int chunkZ) {
        return this.chunks.computeIfAbsent(CoordinateUtils.getChunkKey(chunkX, chunkZ), (final long key) -> new Chunk());
    }

    /**
     * Forgets the chunk, which the client forgot or gets sent again.
     */
    void forget(final int chunkX, final int chunkZ) {
        this.chunks.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    void clear() {
        this.chunks.clear();
    }

    /**
     * Records the light of a light update, and leaves out the sections that are exactly what the client has, for
     * updates of chunks with disguised blocks whose block light had to be corrected.
     *
     * @param corrected the light indexes of the block light sections corrected for disguised blocks
     * @return the packet, a copy without the unchanged sections, or {@code null} if nothing is left
     */
    @Nullable ClientboundLightUpdatePacket dropUnchanged(final ClientboundLightUpdatePacket packet, final @Nullable BitSet corrected) {
        final ClientboundLightUpdatePacketData data = packet.lightData();
        Chunk chunk = this.chunks.get(CoordinateUtils.getChunkKey(packet.x(), packet.z()));
        if (chunk == null) {
            if (corrected == null || corrected.isEmpty()) {
                return packet;
            }
            chunk = this.chunk(packet.x(), packet.z());
        }
        chunk.forget(data.emptySkyYMask(), true);
        chunk.forget(data.emptyBlockYMask(), false);
        final BitSet unchangedSky = chunk.update(data.skyYMask(), data.skyUpdates(), true);
        final BitSet unchangedBlock = chunk.update(data.blockYMask(), data.blockUpdates(), false);
        if (corrected == null || corrected.isEmpty() || (unchangedSky == null && unchangedBlock == null)) {
            // only updates that involve disguised blocks are trimmed: the client computes the light of block updates
            // itself at the edge of its view distance too, so its light may differ from what it was sent last
            return packet;
        }
        final BitSet skyYMask = new BitSet();
        final List<byte[]> skyUpdates = new ArrayList<>();
        without(data.skyYMask(), data.skyUpdates(), unchangedSky, skyYMask, skyUpdates);
        final BitSet blockYMask = new BitSet();
        final List<byte[]> blockUpdates = new ArrayList<>();
        without(data.blockYMask(), data.blockUpdates(), unchangedBlock, blockYMask, blockUpdates);
        if (skyYMask.isEmpty() && blockYMask.isEmpty() && data.emptySkyYMask().isEmpty() && data.emptyBlockYMask().isEmpty()) {
            return null;
        }
        return new ClientboundLightUpdatePacket(packet.x(), packet.z(), new ClientboundLightUpdatePacketData(
            skyYMask, blockYMask, data.emptySkyYMask(), data.emptyBlockYMask(), skyUpdates, blockUpdates));
    }

    private static void without(final BitSet mask, final List<byte[]> updates, final @Nullable BitSet left, final BitSet newMask, final List<byte[]> newUpdates) {
        int update = 0;
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1), ++update) {
            if (left == null || !left.get(lightIndex)) {
                newMask.set(lightIndex);
                newUpdates.add(updates.get(update));
            }
        }
    }

    static long hash(final byte[] nibbles) {
        long ret = 0x9E3779B97F4A7C15L;
        for (int i = 0; i + 8 <= nibbles.length; i += 8) {
            ret = (ret ^ (long) LONGS.get(nibbles, i)) * 0xBF58476D1CE4E5B9L;
            ret ^= ret >>> 31;
        }
        return ret;
    }

    /**
     * Hashes of the light sections the client has of one chunk, by light index, with {@link #SKY} set for sky light.
     * Guarded by itself.
     */
    static final class Chunk {

        private static final int SKY = 1 << 16;
        private static final long NO_LIGHT = 0L;

        private final Int2LongOpenHashMap light = new Int2LongOpenHashMap();

        /**
         * Records the light sections the client gets.
         *
         * @return the light indexes of the sections the client had exactly like that already, or {@code null} if none
         */
        synchronized @Nullable BitSet update(final BitSet mask, final List<byte[]> updates, final boolean sky) {
            BitSet ret = null;
            int update = 0;
            for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1), ++update) {
                final long hash = hash(updates.get(update));
                // a hash of 0 is simply never taken for unchanged
                if (this.light.put(sky ? lightIndex | SKY : lightIndex, hash) == hash && hash != NO_LIGHT) {
                    if (ret == null) {
                        ret = new BitSet();
                    }
                    ret.set(lightIndex);
                }
            }
            return ret;
        }

        synchronized void forget(final BitSet lightIndexes, final boolean sky) {
            if (this.light.isEmpty()) {
                return;
            }
            for (int lightIndex = lightIndexes.nextSetBit(0); lightIndex >= 0; lightIndex = lightIndexes.nextSetBit(lightIndex + 1)) {
                this.light.remove(sky ? lightIndex | SKY : lightIndex);
            }
        }
    }
}
