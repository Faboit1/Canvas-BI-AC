package io.canvasmc.canvas.anticheat.antibud;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.disguise.BlockDisguises;
import io.canvasmc.canvas.anticheat.disguise.ChunkRewrite;
import io.canvasmc.canvas.anticheat.disguise.DisguiseSource;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Iterator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
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
 * is sent as if the disguised buds were small buds as well, see {@link BlockDisguises}.
 * Once the player gets within the reveal distance of a disguised bud its real state is sent, along with the light
 * around it. A revealed bud stays revealed until its chunk is unloaded, so the client never sees a bud shrink.
 * <p>
 * Per sent chunk this keeps the buds the client has disguised, which the player thread reveals, and the buds the
 * client has seen real. Both are read and written from any thread under the chunk's lock, since block updates and
 * light updates are broadcast by the region owning the chunk.
 */
public final class AntiBud implements DisguiseSource {

    /**
     * How far the light of a disguised bud reaches: clusters emit 5.
     */
    public static final int LIGHT_REACH = 4;
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
            this.owner.disguises.queueRefresh(pos.getX(), pos.getY(), pos.getZ(), LIGHT_REACH);
        }
        return true;
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
     */
    @Override
    public void prepareChunk(final LevelChunk chunk, final ChunkRewrite.Builder builder) {
        final WorldConfig.Anticheat config = this.owner.config();
        if (!this.isActive(config)) {
            return;
        }
        // chunks can be sent before the first tick of a player
        this.active = true;
        final ServerLevel level = builder.level();
        if (level != this.owner.player.level()) {
            return;
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
        for (int index = 0; index < sections.length; ++index) {
            final LevelChunkSection section = sections[index];
            if (!BudDisguise.mayContainBuds(section)) {
                continue;
            }
            final PalettedContainer<BlockState> states = section.getStates();
            final int baseY = (minSectionY + index) << 4;
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
                final BlockState sent = BudDisguise.disguise(state);
                disguised.put(BudChunk.pack(x, y, z), Block.getId(sent));
                if (sent != state) {
                    builder.replace(x, y, z, state, sent);
                }
            }
        }
        if (entry != null) {
            // the packet replaces everything the client had of the chunk
            entry.setDisguised(disguised);
            // the light updates of a growing bud must not tell
            builder.track();
        }

        // the light of disguised buds of the neighbours reaches into the chunk too
        final OcclusionAccess access = new OcclusionAccess(level);
        collectSources(access, level, (chunkX << 4) - LIGHT_REACH, level.getMinY(), (chunkZ << 4) - LIGHT_REACH,
            (chunkX << 4) + 15 + LIGHT_REACH, level.getMaxY(), (chunkZ << 4) + 15 + LIGHT_REACH,
            (final int x, final int y, final int z, final BlockState state) -> {
                if (x >> 4 == chunkX && z >> 4 == chunkZ) {
                    return;
                }
                final BudChunk neighbour = this.chunks.computeIfAbsent(CoordinateUtils.getChunkKey(x >> 4, z >> 4), (final long ignored) -> new BudChunk(level, x >> 4, z >> 4));
                if (this.isHiddenFrom(neighbour, x, y, z, distanceSquared)) {
                    builder.lower(x, y, z, state, BudDisguise.disguise(state));
                }
            });
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
                this.owner.disguises.queueRefresh(x, y, z, LIGHT_REACH);
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

    @Override
    public int sentEmission(final int x, final int y, final int z, final BlockState state) {
        if (!this.active || !BudDisguise.isDisguised(state)) {
            return REAL;
        }
        final ServerPlayer player = this.owner.player;
        final double distance = this.owner.config().antiBud.revealDistance;
        if (distanceSquared(x, y, z, player.getX(), player.getEyeY(), player.getZ()) <= distance * distance) {
            return REAL;
        }
        final BudChunk chunk = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        return chunk != null && chunk.isRevealed(BudChunk.pack(x, y, z)) ? REAL : BudDisguise.disguise(state).getLightEmission();
    }

    /**
     * @return whether anti-bud is enabled in the level
     */
    public static boolean isEnabled(final ServerLevel level) {
        return level.canvasConfig().anticheat.effective().antiBud.enabled;
    }

    /**
     * Collects the disguisable buds in the box.
     */
    public static void collectSources(final OcclusionAccess access, final ServerLevel level, final int minX, final int minY, final int minZ,
                                      final int maxX, final int maxY, final int maxZ, final Collector out) {
        final int minSectionY = level.getMinSectionY();
        final int fromSectionY = Math.max(minY, level.getMinY()) >> 4;
        final int toSectionY = Math.min(maxY, level.getMaxY()) >> 4;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; ++chunkX) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; ++chunkZ) {
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
                                        out.add(x, y, z, state);
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

        /**
         * The disguised buds and the id of the state the client has for them.
         */
        private Int2IntOpenHashMap disguised = new Int2IntOpenHashMap();
        private final IntOpenHashSet revealed = new IntOpenHashSet();

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
