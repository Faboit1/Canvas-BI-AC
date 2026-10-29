package io.canvasmc.canvas.anticheat.disguise;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * What a chunk packet sends to one player differently from the real chunk: blocks sent as other states, the block
 * entities that go away with them, and the block light that goes with the disguises, including the light that
 * disguised blocks of the neighbouring chunks no longer cast into the chunk.
 * <p>
 * Built on the thread owning the chunk right before its packet, by every {@link DisguiseSource} of the player, see
 * {@link BlockDisguises#prepareChunk}.
 */
public final class ChunkRewrite {

    private final int chunkX;
    private final int chunkZ;
    private final int minLightSection;
    private final int @Nullable [] @Nullable [] indices;
    private final BlockState @Nullable [] @Nullable [] states;
    private final LongOpenHashSet withoutBlockEntity;
    private final @Nullable Long2ByteMap light;
    private final LightRecords.@Nullable Chunk records;

    private ChunkRewrite(final Builder builder, final @Nullable Long2ByteMap light, final LightRecords.@Nullable Chunk records) {
        this.chunkX = builder.chunkX;
        this.chunkZ = builder.chunkZ;
        this.minLightSection = builder.level.getLightEngine().getMinLightSection();
        final int sections = builder.indices.length;
        int[][] indices = null;
        BlockState[][] states = null;
        for (int index = 0; index < sections; ++index) {
            final IntArrayList list = builder.indices[index];
            if (list == null) {
                continue;
            }
            if (indices == null) {
                indices = new int[sections][];
                states = new BlockState[sections][];
            }
            indices[index] = list.toIntArray();
            states[index] = builder.states[index].toArray(new BlockState[0]);
        }
        this.indices = indices;
        this.states = states;
        this.withoutBlockEntity = builder.withoutBlockEntity;
        this.light = light;
        this.records = records;
    }

    /**
     * @return the blocks of the section that are sent as other states, indexed by {@code x | z << 4 | y << 8}, or
     * {@code null} if there are none, see {@link #statesIn}
     */
    public int @Nullable [] indicesIn(final int sectionIndex) {
        final int[][] indices = this.indices;
        return indices == null || sectionIndex >= indices.length ? null : indices[sectionIndex];
    }

    /**
     * @return the states the blocks of {@link #indicesIn} are sent as, in the same order
     */
    public BlockState @Nullable [] statesIn(final int sectionIndex) {
        final BlockState[][] states = this.states;
        return states == null || sectionIndex >= states.length ? null : states[sectionIndex];
    }

    /**
     * @return whether the block entity of the block at the position is left out, because the block is sent as a state
     * without one
     */
    public boolean hidesBlockEntity(final int x, final int y, final int z) {
        return !this.withoutBlockEntity.isEmpty() && this.withoutBlockEntity.contains(LoweredLight.pack(x, y, z));
    }

    /**
     * @return the light of a chunk packet with its block light corrected for the disguises
     */
    public static ClientboundLightUpdatePacketData correctLight(final ClientboundLightUpdatePacketData data, final @Nullable ChunkRewrite rewrite) {
        return rewrite == null ? data : rewrite.correctLight(data);
    }

    /**
     * Corrects the light of the chunk packet, and records its light as what the client has.
     */
    private ClientboundLightUpdatePacketData correctLight(final ClientboundLightUpdatePacketData data) {
        final Long2ByteMap light = this.light;
        final ClientboundLightUpdatePacketData ret = light == null || light.isEmpty()
            ? data : applyLight(data, this.chunkX, this.chunkZ, this.minLightSection, light, new BitSet());
        final LightRecords.Chunk records = this.records;
        if (records != null) {
            records.update(ret.skyYMask(), ret.skyUpdates(), true);
            records.update(ret.blockYMask(), ret.blockUpdates(), false);
        }
        return ret;
    }

    /**
     * Writes changed block light into the light data of a chunk.
     *
     * @param corrected receives the light indexes of the sections that changed
     * @return the data itself if no section it contains changed, otherwise a copy
     */
    static ClientboundLightUpdatePacketData applyLight(final ClientboundLightUpdatePacketData data, final int chunkX, final int chunkZ,
                                                       final int minLightSection, final Long2ByteMap light, final BitSet corrected) {
        final BitSet mask = data.blockYMask();
        final List<byte[]> updates = data.blockUpdates();
        List<byte[]> copy = null;
        boolean[] copied = null;
        int[] order = null;
        for (final Long2ByteMap.Entry entry : light.long2ByteEntrySet()) {
            final long pos = entry.getLongKey();
            final int x = LoweredLight.unpackX(pos);
            final int z = LoweredLight.unpackZ(pos);
            if (x >> 4 != chunkX || z >> 4 != chunkZ) {
                continue;
            }
            final int y = LoweredLight.unpackY(pos);
            final int lightIndex = (y >> 4) - minLightSection;
            if (lightIndex < 0 || !mask.get(lightIndex)) {
                continue;
            }
            if (copy == null) {
                copy = new ArrayList<>(updates);
                copied = new boolean[updates.size()];
                // updates are ordered like the set bits of the mask
                order = new int[mask.length()];
                int update = 0;
                for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1)) {
                    order[index] = update++;
                }
            }
            final int update = order[lightIndex];
            if (!copied[update]) {
                copied[update] = true;
                copy.set(update, copy.get(update).clone());
                corrected.set(lightIndex);
            }
            final byte[] nibbles = copy.get(update);
            final int index = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
            final int shift = (index & 1) << 2;
            nibbles[index >> 1] = (byte) (nibbles[index >> 1] & ~(15 << shift) | (entry.getByteValue() & 15) << shift);
        }
        if (copy == null) {
            return data;
        }
        return new ClientboundLightUpdatePacketData(data.skyYMask(), data.blockYMask(), data.emptySkyYMask(), data.emptyBlockYMask(), data.skyUpdates(), copy);
    }

    /**
     * Collects the disguises of one chunk packet.
     */
    public static final class Builder {

        final ServerLevel level;
        final int chunkX;
        final int chunkZ;
        private final int minSectionY;
        final @Nullable IntArrayList[] indices;
        final @Nullable List<BlockState>[] states;
        final LongOpenHashSet withoutBlockEntity = new LongOpenHashSet();
        private final Long2ByteOpenHashMap sentEmissions = new Long2ByteOpenHashMap();
        private final LongArrayList lowered = new LongArrayList();
        private final ByteArrayList emissions = new ByteArrayList();
        private boolean track;

        @SuppressWarnings("unchecked")
        Builder(final LevelChunk chunk) {
            this.level = (ServerLevel) chunk.getLevel();
            this.chunkX = chunk.getPos().x();
            this.chunkZ = chunk.getPos().z();
            this.minSectionY = chunk.getMinSectionY();
            this.indices = new IntArrayList[chunk.getSectionsCount()];
            this.states = new List[chunk.getSectionsCount()];
        }

        public ServerLevel level() {
            return this.level;
        }

        /**
         * Sends a block of the chunk as another state. Light the real block emits beyond what the other state emits
         * is taken out.
         */
        public void replace(final int x, final int y, final int z, final BlockState real, final BlockState sent) {
            final int sectionIndex = (y >> 4) - this.minSectionY;
            IntArrayList list = this.indices[sectionIndex];
            if (list == null) {
                this.indices[sectionIndex] = list = new IntArrayList();
                this.states[sectionIndex] = new ArrayList<>();
            }
            list.add((x & 15) | (z & 15) << 4 | (y & 15) << 8);
            this.states[sectionIndex].add(sent);
            if (real.hasBlockEntity() && !sent.hasBlockEntity()) {
                this.withoutBlockEntity.add(LoweredLight.pack(x, y, z));
            }
            this.lower(x, y, z, real, sent);
        }

        /**
         * Takes out the light a block emits beyond what the state it is sent as emits, for blocks of other chunks
         * whose light reaches into this one.
         */
        public void lower(final int x, final int y, final int z, final BlockState real, final BlockState sent) {
            this.lower(x, y, z, real, sent.getLightEmission());
        }

        /**
         * Like {@link #lower(int, int, int, BlockState, BlockState)}, with the light the block is sent with.
         */
        public void lower(final int x, final int y, final int z, final BlockState real, final int sentEmission) {
            if (real.getLightEmission() > sentEmission) {
                final long pos = LoweredLight.pack(x, y, z);
                this.sentEmissions.put(pos, (byte) sentEmission);
                this.lowered.add(pos);
                this.emissions.add((byte) real.getLightEmission());
            }
        }

        /**
         * Records the light the client gets for this chunk even if nothing in it is disguised, because a disguised
         * block may change later: the light updates that follow must not tell.
         */
        public void track() {
            this.track = true;
        }

        @Nullable ChunkRewrite build(final LightRecords records) {
            final Long2ByteMap light = this.lowered.isEmpty() ? null
                : BlockDisguises.computeLight(this.level, this.lowered, this.emissions, this.sentEmissions);
            final boolean disguised = this.withoutBlockEntity.size() > 0 || light != null && !light.isEmpty() || anyReplaced();
            if (!disguised && !this.track) {
                return null;
            }
            return new ChunkRewrite(this, light, records.chunk(this.chunkX, this.chunkZ));
        }

        private boolean anyReplaced() {
            for (final IntArrayList list : this.indices) {
                if (list != null) {
                    return true;
                }
            }
            return false;
        }
    }
}
