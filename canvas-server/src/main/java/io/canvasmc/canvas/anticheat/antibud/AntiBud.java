package io.canvasmc.canvas.anticheat.antibud;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Per-player anti-bud: hides how far amethyst buds have grown from players that aren't close to them, see
 * {@link WorldConfig.Anticheat.AntiBud}.
 * <p>
 * Buds farther than the reveal distance from the player's eyes are sent as small buds, everywhere a block state
 * reaches the client: chunk packets, block updates, block break effects. A small bud emits less light, so block light
 * is sent as if the disguised buds were small buds as well ({@link BudLight}), in chunk packets and light updates.
 * Once the player gets within the reveal distance of a disguised bud its real state is sent, along with the light
 * around it. A revealed bud stays revealed until its chunk is unloaded, so the client never sees a bud shrink.
 * <p>
 * Per sent chunk this keeps the buds the client has disguised, which the player thread reveals, and the buds the
 * client has seen real. Both are read and written from any thread under the chunk's lock, since block updates and
 * light updates are broadcast by the region owning the chunk.
 */
public final class AntiBud {

    /**
     * How far the light of a disguised bud reaches: clusters emit 5.
     */
    static final int LIGHT_REACH = 4;
    private static final int SMALL_BUD_EMISSION = Blocks.SMALL_AMETHYST_BUD.defaultBlockState().getLightEmission();
    /**
     * How far the eyes must move before disguised buds are checked again.
     */
    private static final double RESCAN_DISTANCE_SQUARED = 0.25 * 0.25;
    private static final int DEACTIVATION_REVEALS_PER_TICK = 256;
    private static final int SWEEP_INTERVAL_TICKS = 200;

    private final PlayerVisibility owner;
    private final ConcurrentChainedLong2ReferenceHashTable<BudChunk> chunks = new ConcurrentChainedLong2ReferenceHashTable<>();
    /**
     * Whether the module is active, for the hooks called from other threads. Set on the player thread.
     */
    private volatile boolean active;
    /**
     * Light sections to send again, by chunk, because a bud whose light reaches them was revealed. Guarded by itself.
     */
    private final Long2ObjectOpenHashMap<BitSet> refresh = new Long2ObjectOpenHashMap<>();

    // player thread state
    private double scanX = Double.NaN;
    private double scanY;
    private double scanZ;
    private double scanDistance;
    private boolean rescan;
    private int nextSweep;
    /**
     * Chunks whose disguised buds are revealed over the next ticks because the module was deactivated.
     */
    private final ArrayList<BudChunk> deactivated = new ArrayList<>();

    public AntiBud(final PlayerVisibility owner) {
        this.owner = owner;
    }

    public boolean isActive(final WorldConfig.Anticheat config) {
        return config.antiBud.enabled && !this.owner.bypassesAntiBud();
    }

    /**
     * Drops all state, used when the player changes world or respawns.
     */
    public void reset() {
        this.chunks.clear();
        synchronized (this.refresh) {
            this.refresh.clear();
        }
        this.deactivated.clear();
        this.scanX = Double.NaN;
    }

    // player thread

    public void tick(final WorldConfig.Anticheat config) {
        final boolean active = this.isActive(config);
        if (active != this.active) {
            this.active = active;
            if (active) {
                // activated again before everything was revealed, the rest is still disguised
                for (final BudChunk chunk : this.deactivated) {
                    this.chunks.putIfAbsent(chunk.key, chunk);
                }
                this.deactivated.clear();
                this.rescan = true;
            } else {
                // deactivated (bypass granted, disabled by a reload): the client still has disguised buds, they are
                // revealed over the next ticks
                for (final Iterator<BudChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
                    this.deactivated.add(iterator.next());
                }
                this.chunks.clear();
            }
        }
        if (active) {
            this.revealNearby(config.antiBud.revealDistance);
        } else {
            this.revealDeactivated();
        }
        if (this.owner.player.tickCount >= this.nextSweep) {
            this.nextSweep = this.owner.player.tickCount + SWEEP_INTERVAL_TICKS;
            this.sweep();
        }
        this.flushRefresh();
    }

    private void revealNearby(final double distance) {
        final ServerPlayer player = this.owner.player;
        final double eyeX = player.getX();
        final double eyeY = player.getEyeY();
        final double eyeZ = player.getZ();
        if (!this.rescan && distance == this.scanDistance && !Double.isNaN(this.scanX)) {
            final double dx = eyeX - this.scanX;
            final double dy = eyeY - this.scanY;
            final double dz = eyeZ - this.scanZ;
            if (dx * dx + dy * dy + dz * dz < RESCAN_DISTANCE_SQUARED) {
                return;
            }
        }
        this.rescan = false;
        this.scanX = eyeX;
        this.scanY = eyeY;
        this.scanZ = eyeZ;
        this.scanDistance = distance;
        final double distanceSquared = distance * distance;
        final ServerLevel level = player.level();
        for (final Iterator<BudChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
            final BudChunk chunk = iterator.next();
            if (chunk.level != level) {
                this.chunks.remove(chunk.key, chunk);
                continue;
            }
            final double dx = Math.max(0.0, Math.max((chunk.chunkX << 4) - eyeX, eyeX - ((chunk.chunkX << 4) + 16)));
            final double dz = Math.max(0.0, Math.max((chunk.chunkZ << 4) - eyeZ, eyeZ - ((chunk.chunkZ << 4) + 16)));
            if (dx * dx + dz * dz > distanceSquared) {
                continue;
            }
            final int[] disguised = chunk.disguised();
            for (final int packed : disguised) {
                final int x = chunk.blockX(packed);
                final int y = BudChunk.unpackY(packed);
                final int z = chunk.blockZ(packed);
                if (distanceSquared(x, y, z, eyeX, eyeY, eyeZ) <= distanceSquared) {
                    this.reveal(chunk, packed);
                }
            }
        }
    }

    private void revealDeactivated() {
        int budget = DEACTIVATION_REVEALS_PER_TICK;
        final ServerLevel level = this.owner.player.level();
        while (budget > 0 && !this.deactivated.isEmpty()) {
            final BudChunk chunk = this.deactivated.getLast();
            if (chunk.level != level) {
                this.deactivated.removeLast();
                continue;
            }
            final int[] disguised = chunk.disguised();
            if (disguised.length == 0) {
                this.deactivated.removeLast();
                continue;
            }
            for (final int packed : disguised) {
                if (budget-- <= 0) {
                    return;
                }
                if (!this.reveal(chunk, packed)) {
                    // not owned by this region right now, the client keeps the disguise
                    chunk.forget(packed);
                }
            }
        }
    }

    /**
     * Sends the real state of a disguised bud, and the light around it.
     *
     * @return whether the bud isn't disguised anymore
     */
    private boolean reveal(final BudChunk chunk, final int packed) {
        final ServerPlayer player = this.owner.player;
        if (!TickThread.isTickThreadFor(chunk.level, chunk.chunkX, chunk.chunkZ)) {
            // retried once the chunk is owned by the player's region
            this.rescan = true;
            return false;
        }
        final LevelChunk levelChunk = chunk.level.getChunkSource().getChunkAtIfLoadedImmediately(chunk.chunkX, chunk.chunkZ);
        if (levelChunk == null || !chunk.reveal(packed)) {
            return levelChunk != null;
        }
        final BlockPos pos = new BlockPos(chunk.blockX(packed), BudChunk.unpackY(packed), chunk.blockZ(packed));
        final BlockState state = levelChunk.getBlockState(pos);
        if (BudDisguise.isDisguised(state)) {
            // what anti-xray or anti-freecam hide gets the real state once they reveal it
            if (!this.owner.isBlockHidden(pos.getX(), pos.getY(), pos.getZ(), state)) {
                player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            }
            this.queueRefresh(pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    /**
     * Requests the light sections a bud's light reaches to be sent again, now that the bud isn't disguised anymore.
     */
    private void queueRefresh(final int x, final int y, final int z) {
        final int minLightSection = this.owner.player.level().getLightEngine().getMinLightSection();
        synchronized (this.refresh) {
            for (int chunkX = (x - LIGHT_REACH) >> 4; chunkX <= (x + LIGHT_REACH) >> 4; ++chunkX) {
                for (int chunkZ = (z - LIGHT_REACH) >> 4; chunkZ <= (z + LIGHT_REACH) >> 4; ++chunkZ) {
                    final BitSet sections = this.refresh.computeIfAbsent(CoordinateUtils.getChunkKey(chunkX, chunkZ), (final long key) -> new BitSet());
                    for (int sectionY = (y - LIGHT_REACH) >> 4; sectionY <= (y + LIGHT_REACH) >> 4; ++sectionY) {
                        sections.set(sectionY - minLightSection);
                    }
                }
            }
        }
    }

    private void flushRefresh() {
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
     * Drops state of chunks the client doesn't have, which updates broadcast while the chunk was being unloaded may
     * have left behind.
     */
    private void sweep() {
        final ServerPlayer player = this.owner.player;
        final ServerLevel level = player.level();
        for (final Iterator<BudChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
            final BudChunk chunk = iterator.next();
            if (chunk.level != level || !level.moonrise$getPlayerChunkLoader().isChunkSent(player, chunk.chunkX, chunk.chunkZ)) {
                this.chunks.remove(chunk.key, chunk);
            }
        }
    }

    public void onChunkUnload(final int chunkX, final int chunkZ) {
        this.chunks.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    /**
     * Decides which buds of a chunk are sent disguised to the player, and the light that goes with that. Records them
     * as what the client has. Called on the thread owning the chunk, right before its packet is created.
     *
     * @return the disguise, or {@code null} if the chunk is sent as it is
     */
    public @Nullable ChunkDisguise prepareChunk(final LevelChunk chunk) {
        final WorldConfig.Anticheat config = this.owner.config();
        if (!this.isActive(config)) {
            return null;
        }
        // chunks can be sent before the first tick of a player
        this.active = true;
        final ServerLevel level = (ServerLevel) chunk.getLevel();
        if (level != this.owner.player.level()) {
            return null;
        }
        final double distanceSquared = config.antiBud.revealDistance * config.antiBud.revealDistance;
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final LevelChunkSection[] sections = chunk.getSections();
        final int minSectionY = chunk.getMinSectionY();

        BudChunk entry = this.chunks.get(key);
        if (entry != null && entry.level != level) {
            this.chunks.remove(key, entry);
            entry = null;
        }
        final Int2IntOpenHashMap disguised = new Int2IntOpenHashMap();
        final LongArrayList positions = new LongArrayList();
        final ByteArrayList emissions = new ByteArrayList();
        int @Nullable [] @Nullable [] buds = null;
        for (int index = 0; index < sections.length; ++index) {
            final LevelChunkSection section = sections[index];
            if (!BudDisguise.mayContainBuds(section)) {
                continue;
            }
            final PalettedContainer<BlockState> states = section.getStates();
            final int baseY = (minSectionY + index) << 4;
            IntArrayList inSection = null;
            for (int i = 0; i < 4096; ++i) {
                final BlockState state = states.get(i);
                if (!BudDisguise.isBud(state)) {
                    continue;
                }
                if (entry == null) {
                    entry = this.chunks.computeIfAbsent(key, (final long ignored) -> new BudChunk(level, chunkX, chunkZ));
                }
                final int x = (chunkX << 4) | (i & 15);
                final int y = baseY | i >>> 8;
                final int z = (chunkZ << 4) | ((i >>> 4) & 15);
                if (!this.isHiddenFrom(entry, x, y, z, distanceSquared)) {
                    continue;
                }
                // small buds are recorded too, so growing into a medium bud doesn't send an update
                disguised.put(BudChunk.pack(x, y, z), Block.getId(BudDisguise.disguise(state)));
                if (!BudDisguise.isDisguised(state)) {
                    continue;
                }
                if (inSection == null) {
                    inSection = new IntArrayList();
                }
                inSection.add(i);
                positions.add(BudLight.pack(x, y, z));
                emissions.add((byte) state.getLightEmission());
            }
            if (inSection != null) {
                if (buds == null) {
                    buds = new int[sections.length][];
                }
                buds[index] = inSection.toIntArray();
            }
        }
        if (entry != null) {
            // the packet replaces everything the client had of the chunk
            entry.setDisguised(disguised);
        }

        // the light of disguised buds of the neighbours reaches into the chunk too
        final OcclusionAccess access = new OcclusionAccess(level);
        final int minX = (chunkX << 4) - LIGHT_REACH;
        final int minZ = (chunkZ << 4) - LIGHT_REACH;
        final int maxX = (chunkX << 4) + 15 + LIGHT_REACH;
        final int maxZ = (chunkZ << 4) + 15 + LIGHT_REACH;
        final LongArrayList around = new LongArrayList();
        final ByteArrayList aroundEmissions = new ByteArrayList();
        collect(access, level, minX, level.getMinY(), minZ, maxX, level.getMaxY(), maxZ, chunkX, chunkZ, around, aroundEmissions);
        for (int i = 0; i < around.size(); ++i) {
            final long pos = around.getLong(i);
            final int x = BudLight.unpackX(pos);
            final int z = BudLight.unpackZ(pos);
            final BudChunk neighbour = this.chunks.computeIfAbsent(CoordinateUtils.getChunkKey(x >> 4, z >> 4), (final long ignored) -> new BudChunk(level, x >> 4, z >> 4));
            if (this.isHiddenFrom(neighbour, x, BudLight.unpackY(pos), z, distanceSquared)) {
                positions.add(pos);
                emissions.add(aroundEmissions.getByte(i));
            }
        }

        final Long2ByteMap light = positions.isEmpty() ? null : computeLight(level, positions, emissions);
        if (light != null && !light.isEmpty() && entry == null) {
            // the light of the neighbours' buds is corrected here, which the chunk's light records must know
            entry = this.chunks.computeIfAbsent(key, (final long ignored) -> new BudChunk(level, chunkX, chunkZ));
        }
        if (entry == null) {
            return null;
        }
        // records the light the client gets for chunks with buds, even if it isn't corrected
        return new ChunkDisguise(chunkX, chunkZ, level.getLightEngine().getMinLightSection(), buds, light, entry);
    }

    /**
     * Whether a bud is sent disguised: it is farther than the reveal distance and the client hasn't seen it real
     * before. Buds within the reveal distance are recorded as seen real.
     */
    private boolean isHiddenFrom(final BudChunk chunk, final int x, final int y, final int z, final double distanceSquared) {
        final ServerPlayer player = this.owner.player;
        final int packed = BudChunk.pack(x, y, z);
        if (distanceSquared(x, y, z, player.getX(), player.getEyeY(), player.getZ()) <= distanceSquared) {
            if (chunk.markRevealed(packed)) {
                // chunks sent earlier got the light of the bud disguised
                this.queueRefresh(x, y, z);
            }
            return false;
        }
        return !chunk.isRevealed(packed);
    }

    private static double distanceSquared(final int x, final int y, final int z, final double eyeX, final double eyeY, final double eyeZ) {
        final double dx = x + 0.5 - eyeX;
        final double dy = y + 0.5 - eyeY;
        final double dz = z + 0.5 - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    // any thread

    /**
     * @return the state to send for a block update: the small bud the state is disguised as if the player may not see
     * it, otherwise the state itself. {@code null} if the client has that disguise already: a bud growing doesn't
     * change its disguise, and the update would tell that it grew
     */
    public @Nullable BlockState filterUpdate(final int x, final int y, final int z, final BlockState state) {
        return this.disguise(x, y, z, state, true);
    }

    /**
     * @return the state to send for a block, like {@link #filterUpdate} but always a state, for corrections the client
     * waits for
     */
    public BlockState disguise(final int x, final int y, final int z, final BlockState state) {
        final BlockState ret = this.disguise(x, y, z, state, false);
        return ret == null ? state : ret;
    }

    private @Nullable BlockState disguise(final int x, final int y, final int z, final BlockState state, final boolean skipUnchanged) {
        if (!this.active || !BudDisguise.isBud(state)) {
            return state;
        }
        final ServerPlayer player = this.owner.player;
        final ServerLevel level = player.level();
        final BudChunk chunk = this.chunks.computeIfAbsent(CoordinateUtils.getChunkKey(x >> 4, z >> 4), (final long ignored) -> new BudChunk(level, x >> 4, z >> 4));
        final int packed = BudChunk.pack(x, y, z);
        final double distance = this.owner.config().antiBud.revealDistance;
        if (distanceSquared(x, y, z, player.getX(), player.getEyeY(), player.getZ()) <= distance * distance) {
            // a disguised bud stays recorded as disguised, so the player thread sends the light around it
            chunk.markRevealed(packed);
            return state;
        }
        final BlockState disguise = BudDisguise.disguise(state);
        final int previous = chunk.disguiseUnlessRevealed(packed, Block.getId(disguise));
        if (previous == BudChunk.REVEALED) {
            return state;
        }
        return skipUnchanged && previous == Block.getId(disguise) ? null : disguise;
    }

    /**
     * @return whether a bud at the position would be sent disguised, for effects that carry a state, like the
     * particles of a breaking block
     */
    public boolean disguisesAt(final int x, final int y, final int z) {
        if (!this.active) {
            return false;
        }
        final ServerPlayer player = this.owner.player;
        final double distance = this.owner.config().antiBud.revealDistance;
        if (distanceSquared(x, y, z, player.getX(), player.getEyeY(), player.getZ()) <= distance * distance) {
            return false;
        }
        final BudChunk chunk = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        return chunk == null || !chunk.isRevealed(BudChunk.pack(x, y, z));
    }

    /**
     * Corrects the block light of a light update for the buds disguised from this player.
     *
     * @param scan the buds whose light reaches into the update, see {@link #scan}
     * @return the packet itself if no disguised bud lights it, otherwise a corrected copy
     */
    public @Nullable ClientboundLightUpdatePacket correctLight(final ClientboundLightUpdatePacket packet, final @Nullable LightScan scan) {
        if (!this.active) {
            return packet;
        }
        final ServerPlayer player = this.owner.player;
        if (scan == null || scan.level != player.level()) {
            return this.dropUnchanged(packet, null);
        }
        final double distance = this.owner.config().antiBud.revealDistance;
        final double distanceSquared = distance * distance;
        final double eyeX = player.getX();
        final double eyeY = player.getEyeY();
        final double eyeZ = player.getZ();
        final long[] positions = scan.positions;
        long[] hidden = null;
        for (int i = 0; i < positions.length; ++i) {
            final int x = BudLight.unpackX(positions[i]);
            final int y = BudLight.unpackY(positions[i]);
            final int z = BudLight.unpackZ(positions[i]);
            if (distanceSquared(x, y, z, eyeX, eyeY, eyeZ) <= distanceSquared) {
                continue;
            }
            final BudChunk chunk = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
            if (chunk != null && chunk.isRevealed(BudChunk.pack(x, y, z))) {
                continue;
            }
            if (hidden == null) {
                hidden = new long[(positions.length + 63) >>> 6];
            }
            hidden[i >>> 6] |= 1L << i;
        }
        if (hidden == null) {
            return this.dropUnchanged(packet, null);
        }
        // most receivers are either near the buds or far from all of them
        if (Arrays.equals(hidden, scan.lastHidden)) {
            return this.dropUnchanged(scan.lastResult, scan.lastCorrected);
        }
        final LongArrayList lowered = new LongArrayList();
        final ByteArrayList emissions = new ByteArrayList();
        for (int i = 0; i < positions.length; ++i) {
            if ((hidden[i >>> 6] & 1L << i) != 0L) {
                lowered.add(positions[i]);
                emissions.add(scan.emissions[i]);
            }
        }
        final Long2ByteMap light = computeLight(scan.level, lowered, emissions);
        final BitSet corrected = new BitSet();
        final ClientboundLightUpdatePacketData data = applyLight(packet.lightData(), packet.x(), packet.z(),
            scan.level.getLightEngine().getMinLightSection(), light, corrected);
        final ClientboundLightUpdatePacket ret = data == packet.lightData() ? packet : new ClientboundLightUpdatePacket(packet.x(), packet.z(), data);
        scan.lastHidden = hidden;
        scan.lastResult = ret;
        scan.lastCorrected = corrected;
        return this.dropUnchanged(ret, corrected);
    }

    /**
     * Leaves out the light sections of a light update for disguised buds that are exactly what the client has. When
     * a disguised bud grows, its light changes, but the light sent for it doesn't, and the light engine sends unchanged
     * sky light along: an update with the same light would tell that it grew. The light sent for chunks with buds is
     * recorded as hashes.
     *
     * @param corrected the light indexes of the block light sections corrected for disguised buds
     * @return the packet, a copy without the unchanged sections, or {@code null} if nothing is left
     */
    private @Nullable ClientboundLightUpdatePacket dropUnchanged(final ClientboundLightUpdatePacket packet, final @Nullable BitSet corrected) {
        final ClientboundLightUpdatePacketData data = packet.lightData();
        final long key = CoordinateUtils.getChunkKey(packet.x(), packet.z());
        BudChunk chunk = this.chunks.get(key);
        if (chunk == null) {
            if (corrected == null || corrected.isEmpty()) {
                return packet;
            }
            final ServerLevel level = this.owner.player.level();
            chunk = this.chunks.computeIfAbsent(key, (final long ignored) -> new BudChunk(level, packet.x(), packet.z()));
        }
        chunk.forgetLight(data.emptySkyYMask(), true);
        chunk.forgetLight(data.emptyBlockYMask(), false);
        final BitSet unchangedSky = chunk.updateLight(data.skyYMask(), data.skyUpdates(), true);
        final BitSet unchangedBlock = chunk.updateLight(data.blockYMask(), data.blockUpdates(), false);
        if (corrected == null || corrected.isEmpty() || (unchangedSky == null && unchangedBlock == null)) {
            // only updates that involve disguised buds are trimmed: the client computes the light of block updates
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

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static long hash(final byte[] nibbles) {
        long ret = 0x9E3779B97F4A7C15L;
        for (int i = 0; i + 8 <= nibbles.length; i += 8) {
            ret = (ret ^ (long) LONGS.get(nibbles, i)) * 0xBF58476D1CE4E5B9L;
            ret ^= ret >>> 31;
        }
        return ret;
    }

    /**
     * @return the light of a chunk packet with the block light corrected for the disguised buds
     */
    public static ClientboundLightUpdatePacketData correctLight(final ClientboundLightUpdatePacketData data, final @Nullable ChunkDisguise buds) {
        return buds == null ? data : buds.correctLight(data);
    }

    /**
     * Finds the buds whose light may reach into the block light of a light update, once for all of its receivers.
     *
     * @return the buds, or {@code null} if there are none or anti-bud is disabled in the level
     */
    public static @Nullable LightScan scan(final ServerLevel level, final ClientboundLightUpdatePacket packet) {
        if (!level.canvasConfig().anticheat.antiBud.enabled) {
            return null;
        }
        final BitSet mask = packet.lightData().blockYMask();
        if (mask.isEmpty()) {
            return null;
        }
        final int minLightSection = level.getLightEngine().getMinLightSection();
        final int minY = ((minLightSection + mask.nextSetBit(0)) << 4) - LIGHT_REACH;
        final int maxY = ((minLightSection + mask.length() - 1) << 4) + 15 + LIGHT_REACH;
        final int minX = (packet.x() << 4) - LIGHT_REACH;
        final int minZ = (packet.z() << 4) - LIGHT_REACH;
        final LongArrayList positions = new LongArrayList();
        final ByteArrayList emissions = new ByteArrayList();
        collect(new OcclusionAccess(level), level, minX, minY, minZ, minX + 15 + 2 * LIGHT_REACH, maxY, minZ + 15 + 2 * LIGHT_REACH,
            Integer.MIN_VALUE, Integer.MIN_VALUE, positions, emissions);
        return positions.isEmpty() ? null : new LightScan(level, positions.toLongArray(), emissions.toByteArray());
    }

    /**
     * Collects the disguisable buds in the box, except those of the given chunk.
     */
    private static void collect(final OcclusionAccess access, final ServerLevel level, final int minX, final int minY, final int minZ,
                                final int maxX, final int maxY, final int maxZ, final int skipChunkX, final int skipChunkZ,
                                final LongArrayList positions, final ByteArrayList emissions) {
        final int minSectionY = level.getMinSectionY();
        final int fromSectionY = Math.max(minY, level.getMinY()) >> 4;
        final int toSectionY = Math.min(maxY, level.getMaxY()) >> 4;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; ++chunkX) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; ++chunkZ) {
                if (chunkX == skipChunkX && chunkZ == skipChunkZ) {
                    continue;
                }
                final LevelChunkSection[] sections = access.sections(chunkX, chunkZ);
                if (sections == null) {
                    continue;
                }
                final int fromX = Math.max(minX, chunkX << 4);
                final int toX = Math.min(maxX, (chunkX << 4) + 15);
                final int fromZ = Math.max(minZ, chunkZ << 4);
                final int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int sectionY = fromSectionY; sectionY <= toSectionY; ++sectionY) {
                    final LevelChunkSection section = sections[sectionY - minSectionY];
                    if (!BudDisguise.mayContain(section)) {
                        continue;
                    }
                    final int fromY = Math.max(minY, sectionY << 4);
                    final int toY = Math.min(maxY, (sectionY << 4) + 15);
                    try {
                        for (int y = fromY; y <= toY; ++y) {
                            for (int z = fromZ; z <= toZ; ++z) {
                                for (int x = fromX; x <= toX; ++x) {
                                    final BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                    if (BudDisguise.isDisguised(state)) {
                                        positions.add(BudLight.pack(x, y, z));
                                        emissions.add((byte) state.getLightEmission());
                                    }
                                }
                            }
                        }
                    } catch (final MissingPaletteEntryException ignored) {
                        // racing a palette resize of a chunk owned by another region, sent with its next update
                    }
                }
            }
        }
    }

    private static Long2ByteMap computeLight(final ServerLevel level, final LongArrayList positions, final ByteArrayList emissions) {
        final BudLight light = new BudLight(new WorldLight(level, new LongOpenHashSet(positions)));
        for (int i = 0; i < positions.size(); ++i) {
            final long pos = positions.getLong(i);
            light.lower(BudLight.unpackX(pos), BudLight.unpackY(pos), BudLight.unpackZ(pos), emissions.getByte(i), SMALL_BUD_EMISSION);
        }
        light.propagate();
        return light.changes();
    }

    /**
     * Writes the changed block light of a chunk into light data.
     *
     * @return the data itself if no section it contains changed, otherwise a copy
     */
    static ClientboundLightUpdatePacketData applyLight(final ClientboundLightUpdatePacketData data, final int chunkX, final int chunkZ,
                                                       final int minLightSection, final Long2ByteMap light, final BitSet corrected) {
        final BitSet mask = data.blockYMask();
        final List<byte[]> updates = data.blockUpdates();
        List<byte[]> copy = null;
        boolean[] copied = null;
        for (final Long2ByteMap.Entry entry : light.long2ByteEntrySet()) {
            final long pos = entry.getLongKey();
            final int x = BudLight.unpackX(pos);
            final int y = BudLight.unpackY(pos);
            final int z = BudLight.unpackZ(pos);
            if (x >> 4 != chunkX || z >> 4 != chunkZ) {
                continue;
            }
            final int lightIndex = (y >> 4) - minLightSection;
            if (lightIndex < 0 || !mask.get(lightIndex)) {
                continue;
            }
            // updates are ordered like the set bits of the mask
            final int update = mask.get(0, lightIndex).cardinality();
            if (copy == null) {
                copy = new ArrayList<>(updates);
                copied = new boolean[updates.size()];
            }
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
     * The buds that may light up the block light of a light update, shared by its receivers.
     */
    public static final class LightScan {

        final ServerLevel level;
        final long[] positions;
        final byte[] emissions;
        long @Nullable [] lastHidden;
        @Nullable ClientboundLightUpdatePacket lastResult;
        @Nullable BitSet lastCorrected;

        LightScan(final ServerLevel level, final long[] positions, final byte[] emissions) {
            this.level = level;
            this.positions = positions;
            this.emissions = emissions;
        }
    }

    /**
     * What a chunk packet sends differently for anti-bud: the buds written as small buds, and the block light.
     */
    public static final class ChunkDisguise {

        private final int chunkX;
        private final int chunkZ;
        private final int minLightSection;
        private final int @Nullable [] @Nullable [] buds;
        private final @Nullable Long2ByteMap light;
        private final BudChunk chunk;

        ChunkDisguise(final int chunkX, final int chunkZ, final int minLightSection, final int @Nullable [] @Nullable [] buds,
                      final @Nullable Long2ByteMap light, final BudChunk chunk) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minLightSection = minLightSection;
            this.buds = buds;
            this.light = light;
            this.chunk = chunk;
        }

        /**
         * @return the blocks of the section to write as the small bud their state is disguised as, indexed by
         * {@code x | z << 4 | y << 8}, or {@code null} if there are none
         */
        public int @Nullable [] budsIn(final int sectionIndex) {
            final int[][] buds = this.buds;
            return buds == null || sectionIndex >= buds.length ? null : buds[sectionIndex];
        }

        /**
         * Corrects the light of the chunk packet, and records its light as what the client has.
         */
        public ClientboundLightUpdatePacketData correctLight(final ClientboundLightUpdatePacketData data) {
            final Long2ByteMap light = this.light;
            final ClientboundLightUpdatePacketData ret = light == null ? data : applyLight(data, this.chunkX, this.chunkZ, this.minLightSection, light, new BitSet());
            this.chunk.updateLight(ret.skyYMask(), ret.skyUpdates(), true);
            this.chunk.updateLight(ret.blockYMask(), ret.blockUpdates(), false);
            return ret;
        }
    }

    /**
     * The buds of a chunk the client has disguised and those it has seen real, as block positions packed by
     * {@link #pack}. Guarded by itself.
     */
    private static final class BudChunk {

        final ServerLevel level;
        final long key;
        final int chunkX;
        final int chunkZ;
        static final int REVEALED = -1;
        private static final long NO_LIGHT = 0L;

        /**
         * The disguised buds and the id of the state the client has for them.
         */
        private Int2IntOpenHashMap disguised = new Int2IntOpenHashMap();
        private final IntOpenHashSet revealed = new IntOpenHashSet();
        /**
         * Hashes of the light sections the client has, by light index, with {@link #SKY} set for sky light.
         */
        private final Int2LongOpenHashMap light = new Int2LongOpenHashMap();
        private static final int SKY = 1 << 16;

        BudChunk(final ServerLevel level, final int chunkX, final int chunkZ) {
            this.level = level;
            this.key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        static int pack(final int x, final int y, final int z) {
            return y << 8 | (z & 15) << 4 | (x & 15);
        }

        static int unpackY(final int packed) {
            return packed >> 8;
        }

        int blockX(final int packed) {
            return (this.chunkX << 4) | (packed & 15);
        }

        int blockZ(final int packed) {
            return (this.chunkZ << 4) | ((packed >>> 4) & 15);
        }

        synchronized int[] disguised() {
            return this.disguised.keySet().toIntArray();
        }

        /**
         * Replaces what the client has of the chunk, for a chunk packet.
         */
        synchronized void setDisguised(final Int2IntOpenHashMap disguised) {
            this.disguised = disguised;
            this.light.clear();
        }

        /**
         * Records the light sections the client gets.
         *
         * @return the light indexes of the sections the client had exactly like that already, or {@code null} if none
         */
        synchronized @Nullable BitSet updateLight(final BitSet mask, final List<byte[]> updates, final boolean sky) {
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

        synchronized void forgetLight(final BitSet lightIndexes, final boolean sky) {
            if (this.light.isEmpty()) {
                return;
            }
            for (int lightIndex = lightIndexes.nextSetBit(0); lightIndex >= 0; lightIndex = lightIndexes.nextSetBit(lightIndex + 1)) {
                this.light.remove(sky ? lightIndex | SKY : lightIndex);
            }
        }

        synchronized boolean isRevealed(final int packed) {
            return this.revealed.contains(packed);
        }

        /**
         * @return whether the bud wasn't recorded as seen real before
         */
        synchronized boolean markRevealed(final int packed) {
            return this.revealed.add(packed);
        }

        /**
         * @return whether the bud was disguised, and is recorded as seen real now
         */
        synchronized boolean reveal(final int packed) {
            if (!this.disguised.containsKey(packed)) {
                return false;
            }
            this.disguised.remove(packed);
            this.revealed.add(packed);
            return true;
        }

        synchronized void forget(final int packed) {
            this.disguised.remove(packed);
        }

        /**
         * Records the bud as disguised as the given state, unless the client has seen it real.
         *
         * @return {@link #REVEALED} if the client has seen it real, otherwise the id of the disguise the client had
         * before, or {@code 0} if none
         */
        synchronized int disguiseUnlessRevealed(final int packed, final int disguise) {
            if (this.revealed.contains(packed)) {
                return REVEALED;
            }
            return this.disguised.put(packed, disguise);
        }
    }
}
