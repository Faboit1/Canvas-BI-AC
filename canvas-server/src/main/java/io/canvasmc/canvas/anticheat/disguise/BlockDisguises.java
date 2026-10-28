package io.canvasmc.canvas.anticheat.disguise;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.antibud.AntiBud;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.storage.StorageGuard;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteList;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * The blocks one player gets sent as something else, by all {@link DisguiseSource}s together, and the light that goes
 * with them: block light is sent as the disguises would light the world, in chunk packets and in light updates.
 * <p>
 * The server only sends light updates for chunks at the edge of a player's view distance; elsewhere clients light
 * block updates themselves, from the disguise they were sent. When a disguised block is revealed, the light around it
 * is sent again, since chunks sent earlier got the light of its disguise.
 */
public final class BlockDisguises {

    /**
     * How far the light of a disguised block reaches at most: a source of light 15 lights 14 blocks around it.
     */
    public static final int MAX_LIGHT_REACH = 14;

    private final PlayerVisibility owner;
    private final DisguiseSource[] sources;
    private final LightRecords records = new LightRecords();
    /**
     * Light sections to send again, by chunk, because a block whose light reaches them was revealed. Guarded by itself.
     */
    private final Long2ObjectOpenHashMap<BitSet> refresh = new Long2ObjectOpenHashMap<>();

    /**
     * @param budSource     the anti-bud of the player, see {@link LightScan}
     * @param storageSource the storage hiding of the player
     */
    public BlockDisguises(final PlayerVisibility owner, final DisguiseSource budSource, final DisguiseSource storageSource) {
        this.owner = owner;
        this.sources = new DisguiseSource[] {budSource, storageSource};
    }

    public void reset() {
        this.records.clear();
        synchronized (this.refresh) {
            this.refresh.clear();
        }
    }

    public void onChunkUnload(final int chunkX, final int chunkZ) {
        this.records.forget(chunkX, chunkZ);
    }

    // chunk packets

    /**
     * Collects the disguises of a chunk packet from every source. Called on the thread owning the chunk, right
     * before its packet is created.
     *
     * @return the disguises, or {@code null} if the chunk is sent as it is
     */
    public @Nullable ChunkRewrite prepareChunk(final LevelChunk chunk) {
        // the packet replaces everything the client had of the chunk
        this.records.forget(chunk.getPos().x(), chunk.getPos().z());
        final ChunkRewrite.Builder builder = new ChunkRewrite.Builder(chunk);
        for (final DisguiseSource source : this.sources) {
            source.prepareChunk(chunk, builder);
        }
        return builder.build(this.records);
    }

    static Long2ByteMap computeLight(final ServerLevel level, final LongList positions, final ByteList emissions, final Long2ByteMap sentEmissions) {
        final LoweredLight light = new LoweredLight(new WorldLight(level, sentEmissions));
        for (int i = 0; i < positions.size(); ++i) {
            final long pos = positions.getLong(i);
            light.lower(LoweredLight.unpackX(pos), LoweredLight.unpackY(pos), LoweredLight.unpackZ(pos), emissions.getByte(i), sentEmissions.get(pos));
        }
        light.propagate();
        return light.changes();
    }

    // light updates

    /**
     * Finds the blocks whose disguise may lower the block light of a light update, once for all of its receivers.
     *
     * @return the blocks, or {@code null} if there are none or no disguise is enabled in the level
     */
    public static @Nullable LightScan scan(final ServerLevel level, final ClientboundLightUpdatePacket packet) {
        final boolean buds = AntiBud.isEnabled(level);
        final boolean storage = StorageGuard.isEnabled(level);
        if (!buds && !storage) {
            return null;
        }
        final BitSet mask = packet.lightData().blockYMask();
        if (mask.isEmpty()) {
            return null;
        }
        final int minLightSection = level.getLightEngine().getMinLightSection();
        final int minY = (minLightSection + mask.nextSetBit(0)) << 4;
        final int maxY = ((minLightSection + mask.length() - 1) << 4) + 15;
        final int minX = packet.x() << 4;
        final int minZ = packet.z() << 4;
        final LightScan ret = new LightScan(level);
        final OcclusionAccess access = new OcclusionAccess(level);
        if (buds) {
            final int reach = AntiBud.LIGHT_REACH;
            AntiBud.collectSources(access, level, minX - reach, minY - reach, minZ - reach, minX + 15 + reach, maxY + reach, minZ + 15 + reach,
                (final int x, final int y, final int z, final BlockState state) -> ret.add(x, y, z, state, LightScan.BUD));
        }
        if (storage) {
            final int reach = MAX_LIGHT_REACH;
            StorageGuard.collectEmitting(access, level, minX - reach, minY - reach, minZ - reach, minX + 15 + reach, maxY + reach, minZ + 15 + reach,
                (final int x, final int y, final int z, final BlockState state) -> {
                    // only blocks whose light can reach into the update
                    final int lightReach = state.getLightEmission() - 1;
                    if (x >= minX - lightReach && x <= minX + 15 + lightReach && z >= minZ - lightReach && z <= minZ + 15 + lightReach
                        && y >= minY - lightReach && y <= maxY + lightReach) {
                        ret.add(x, y, z, state, LightScan.STORAGE);
                    }
                });
        }
        return ret.isEmpty() ? null : ret;
    }

    /**
     * Corrects the block light of a light update for the blocks disguised from this player, and leaves out light the
     * client has already.
     *
     * @param scan the blocks whose disguise may lower the light of the update, see {@link #scan}
     * @return the packet itself if nothing changes, a corrected copy, or {@code null} if nothing is left to send
     */
    public @Nullable ClientboundLightUpdatePacket correctLight(final ClientboundLightUpdatePacket packet, final @Nullable LightScan scan) {
        final ServerPlayer player = this.owner.player;
        if (scan == null || scan.level != player.level()) {
            return this.records.dropUnchanged(packet, null);
        }
        final int count = scan.positions.size();
        long[] lowered = null;
        byte[] sentEmissions = null;
        for (int i = 0; i < count; ++i) {
            final long pos = scan.positions.getLong(i);
            final BlockState state = scan.states.get(i);
            final int sent = this.sources[scan.kinds.getByte(i)].sentEmission(LoweredLight.unpackX(pos), LoweredLight.unpackY(pos), LoweredLight.unpackZ(pos), state);
            if (sent != DisguiseSource.REAL && sent < state.getLightEmission()) {
                if (lowered == null) {
                    lowered = new long[(count + 63) >>> 6];
                    sentEmissions = new byte[count];
                }
                lowered[i >>> 6] |= 1L << i;
                sentEmissions[i] = (byte) sent;
            }
        }
        if (lowered == null) {
            return this.records.dropUnchanged(packet, null);
        }
        // most receivers are either near the disguised blocks or far from all of them
        if (Arrays.equals(lowered, scan.lastLowered) && Arrays.equals(sentEmissions, scan.lastSentEmissions)) {
            return this.records.dropUnchanged(scan.lastResult, scan.lastCorrected);
        }
        final LongArrayList positions = new LongArrayList();
        final ByteArrayList emissions = new ByteArrayList();
        final Long2ByteOpenHashMap sentByPosition = new Long2ByteOpenHashMap();
        for (int i = 0; i < count; ++i) {
            if ((lowered[i >>> 6] & 1L << i) != 0L) {
                final long pos = scan.positions.getLong(i);
                positions.add(pos);
                emissions.add((byte) scan.states.get(i).getLightEmission());
                sentByPosition.put(pos, sentEmissions[i]);
            }
        }
        final Long2ByteMap light = computeLight(scan.level, positions, emissions, sentByPosition);
        final BitSet corrected = new BitSet();
        final ClientboundLightUpdatePacketData data = ChunkRewrite.applyLight(packet.lightData(), packet.x(), packet.z(),
            scan.level.getLightEngine().getMinLightSection(), light, corrected);
        final ClientboundLightUpdatePacket ret = data == packet.lightData() ? packet : new ClientboundLightUpdatePacket(packet.x(), packet.z(), data);
        scan.lastLowered = lowered;
        scan.lastSentEmissions = sentEmissions;
        scan.lastResult = ret;
        scan.lastCorrected = corrected;
        return this.records.dropUnchanged(ret, corrected);
    }

    // revealing

    /**
     * Requests the light sections a revealed block's light reaches to be sent again, since chunks sent earlier got
     * the light of its disguise. Callable from any thread, sent by {@link #tick}.
     *
     * @param reach how far the block's light reaches
     */
    public void queueRefresh(final int x, final int y, final int z, final int reach) {
        final int minLightSection = this.owner.player.level().getLightEngine().getMinLightSection();
        synchronized (this.refresh) {
            for (int chunkX = (x - reach) >> 4; chunkX <= (x + reach) >> 4; ++chunkX) {
                for (int chunkZ = (z - reach) >> 4; chunkZ <= (z + reach) >> 4; ++chunkZ) {
                    final BitSet sections = this.refresh.computeIfAbsent(CoordinateUtils.getChunkKey(chunkX, chunkZ), (final long key) -> new BitSet());
                    for (int sectionY = (y - reach) >> 4; sectionY <= (y + reach) >> 4; ++sectionY) {
                        sections.set(sectionY - minLightSection);
                    }
                }
            }
        }
    }

    /**
     * Sends the light requested by {@link #queueRefresh}. Called on the player's thread every tick.
     */
    public void tick() {
        final Long2ObjectOpenHashMap<BitSet> refresh;
        synchronized (this.refresh) {
            if (this.refresh.isEmpty()) {
                return;
            }
            refresh = this.refresh.clone();
            this.refresh.clear();
        }
        final ServerPlayer player = this.owner.player;
        final ServerLevel level = player.level();
        for (final Long2ObjectMap.Entry<BitSet> entry : refresh.long2ObjectEntrySet()) {
            final int chunkX = CoordinateUtils.getChunkX(entry.getLongKey());
            final int chunkZ = CoordinateUtils.getChunkZ(entry.getLongKey());
            if (!level.moonrise$getPlayerChunkLoader().isChunkSent(player, chunkX, chunkZ)) {
                continue;
            }
            final ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(new ChunkPos(chunkX, chunkZ), level.getLightEngine(), new BitSet(), entry.getValue());
            final ClientboundLightUpdatePacket filtered = this.owner.filterLightUpdate(packet, scan(level, packet));
            if (filtered != null) {
                player.connection.send(filtered);
            }
        }
    }

    /**
     * The blocks whose disguise may lower the block light of a light update, shared by its receivers.
     */
    public static final class LightScan {

        static final byte BUD = 0;
        static final byte STORAGE = 1;

        final ServerLevel level;
        final LongArrayList positions = new LongArrayList();
        final List<BlockState> states = new ArrayList<>();
        final ByteArrayList kinds = new ByteArrayList();
        // the result of the last receiver
        long @Nullable [] lastLowered;
        byte @Nullable [] lastSentEmissions;
        @Nullable ClientboundLightUpdatePacket lastResult;
        @Nullable BitSet lastCorrected;

        LightScan(final ServerLevel level) {
            this.level = level;
        }

        void add(final int x, final int y, final int z, final BlockState state, final byte kind) {
            this.positions.add(LoweredLight.pack(x, y, z));
            this.states.add(state);
            this.kinds.add(kind);
        }

        boolean isEmpty() {
            return this.positions.isEmpty();
        }
    }
}
