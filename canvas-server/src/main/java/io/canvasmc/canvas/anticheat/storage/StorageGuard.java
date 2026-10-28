package io.canvasmc.canvas.anticheat.storage;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.disguise.BlockDisguises;
import io.canvasmc.canvas.anticheat.disguise.ChunkRewrite;
import io.canvasmc.canvas.anticheat.disguise.DisguiseSource;
import io.canvasmc.canvas.anticheat.raytrace.BlockOcclusion;
import io.canvasmc.canvas.anticheat.raytrace.FaceVisibility;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionCache;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Per-player storage hiding: chests, barrels, shulker boxes and other storage that isn't open to the sky are only sent
 * once the player can see them, see {@link WorldConfig.Anticheat.AntiStorageEsp}. Stash finders (x-ray, storage ESP,
 * freecam) get nothing of bases the player hasn't seen.
 * <p>
 * Every storage block of a chunk sent to the player is either sent real, because it is open to the sky, within the
 * always visible distance or in sight, or sent disguised ({@link StorageBlocks#filler}) without its block entity and
 * its light. Updates, block entity data and block events of hidden storage are withheld, and storage placed out of
 * sight is hidden as well. Passes on the worker pool trace lines of sight from the player's cameras to the hidden
 * storage around them, which is revealed (its real state, block entity and light are sent) once one is clear. Once
 * revealed, storage stays real for the player until its chunk is unloaded.
 * <p>
 * Per sent chunk this keeps the storage hidden from the client and the storage it has seen real, read and written from
 * any thread under the chunk's lock, since updates are broadcast by the region owning the chunk.
 */
public final class StorageGuard implements VisibilityModule, DisguiseSource {

    /**
     * How far the eyes must move from the position of the last pass to trigger a new one.
     */
    private static final double MOVE_THRESHOLD_SQUARED = 0.25 * 0.25;
    /**
     * Hidden storage within this distance is traced by every pass, farther storage only by full passes: what is far
     * away comes into sight slowly as the player moves.
     */
    private static final double NEAR_TIER = 16.0;
    /**
     * How far the eyes must move from the position of the last full pass to trigger a new one before the update
     * interval is over.
     */
    private static final double FULL_MOVE_THRESHOLD_SQUARED = 2.0 * 2.0;
    /**
     * Full passes after which the sealed storage of a chunk is checked again although no block of the chunk changed.
     */
    private static final int SEALED_RECHECK_PASSES = 64;
    /**
     * Storage within this distance of the eyes is traced right away when its chunk is sent, so it doesn't pop in
     * after joining or teleporting.
     */
    private static final double SEND_TRACE_DISTANCE = 16.0;
    private static final int DEACTIVATION_REVEALS_PER_TICK = 512;

    private final PlayerVisibility owner;
    private final ConcurrentChainedLong2ReferenceHashTable<StorageChunk> chunks = new ConcurrentChainedLong2ReferenceHashTable<>();
    /**
     * Whether the module is active, for the hooks called from other threads. Set on the player thread.
     */
    private volatile boolean active;
    /**
     * Set when storage was hidden by an update, requests a pass.
     */
    private volatile boolean hiddenByUpdate;

    // player thread state
    private boolean needsPass;
    private double passX = Double.NaN;
    private double passY;
    private double passZ;
    private double lastEyeX = Double.NaN;
    private double lastEyeY;
    private double lastEyeZ;
    private boolean needsFullPass;
    private double fullPassX = Double.NaN;
    private double fullPassY;
    private double fullPassZ;
    private int fullPassTick;
    /**
     * Chunks whose hidden storage is revealed over the next ticks because the module was deactivated.
     */
    private final ArrayList<StorageChunk> deactivated = new ArrayList<>();

    // job inputs, written by prepareAsync
    private boolean jobFull;
    private double jobDistance;
    private double jobNearDistance;
    private boolean jobThirdPerson;
    private int jobBudget;

    // job results, written by runAsync and read by applyResults
    private StorageChunk[] resultChunks = new StorageChunk[64];
    private int[] resultPositions = new int[64];
    private int resultCount;
    private boolean resultIncomplete;
    /**
     * The number of passes and of full passes run, for {@link StorageChunk#tracedPass} and
     * {@link StorageChunk#sealedPass}. Only touched by the passes, which run one at a time.
     */
    private int passes;
    private int fullPasses;

    public StorageGuard(final PlayerVisibility owner) {
        this.owner = owner;
    }

    @Override
    public boolean isActive(final WorldConfig.Anticheat config) {
        return config.antiStorageEsp.enabled && !this.owner.bypassesAntiStorageEsp();
    }

    /**
     * @return whether storage hiding is enabled in the level
     */
    public static boolean isEnabled(final ServerLevel level) {
        return StorageBlocks.of(level) != null;
    }

    private double nearDistance() {
        return this.owner.config().antiStorageEsp.alwaysVisibleDistance;
    }

    @Override
    public void reset() {
        if (this.isActive(this.owner.config())) {
            // world change or respawn: the chunk loader may have sent chunks of the new world in this tick already,
            // the client keeps those, so only the old world's chunks are dropped
            final ServerLevel level = this.owner.player.level();
            for (final Iterator<StorageChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
                final StorageChunk chunk = iterator.next();
                if (chunk.level != level) {
                    this.chunks.remove(chunk.key, chunk);
                }
            }
            this.deactivated.clear();
        } else {
            // deactivated (bypass granted, disabled by a reload): the client still has hidden storage, it is revealed
            // over the next ticks. Updates of it go through from now on
            this.active = false;
            for (final Iterator<StorageChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
                this.deactivated.add(iterator.next());
            }
            this.chunks.clear();
        }
        this.passX = Double.NaN;
        this.fullPassX = Double.NaN;
        this.lastEyeX = Double.NaN;
        this.needsPass = true;
    }

    @Override
    public void tick(final CameraSnapshot snapshot) {
        this.active = true;
        if (this.deactivated.isEmpty()) {
            return;
        }
        // activated again before everything was revealed, the rest is still hidden
        for (final StorageChunk chunk : this.deactivated) {
            if (chunk.level == snapshot.level) {
                this.chunks.putIfAbsent(chunk.key, chunk);
            }
        }
        this.deactivated.clear();
        this.needsPass = true;
    }

    @Override
    public void tickInactive() {
        this.active = false;
        int budget = DEACTIVATION_REVEALS_PER_TICK;
        final ServerLevel level = this.owner.player.level();
        while (budget > 0 && !this.deactivated.isEmpty()) {
            final StorageChunk chunk = this.deactivated.getLast();
            final int[] hidden = chunk.level == level ? chunk.hidden() : new int[0];
            if (hidden.length == 0) {
                this.deactivated.removeLast();
                continue;
            }
            for (final int packed : hidden) {
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

    public void onChunkUnload(final int chunkX, final int chunkZ) {
        this.chunks.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    // chunk packets

    /**
     * Decides which storage of a chunk is sent hidden, records it, and adds the disguises. Called on the thread owning
     * the chunk, right before its packet is created.
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
        final StorageBlocks blocks = StorageBlocks.of(level);
        if (level != this.owner.player.level() || blocks == null) {
            return;
        }
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final LevelChunkSection[] sections = chunk.getSections();
        final int minSectionY = chunk.getMinSectionY();
        final Decision decision = new Decision(this, level, blocks, config.antiStorageEsp);

        StorageChunk entry = this.chunks.get(key);
        if (entry != null && entry.level != level) {
            this.chunks.remove(key, entry);
            entry = null;
        }
        IntOpenHashSet hidden = null;
        for (int index = 0; index < sections.length; ++index) {
            final LevelChunkSection section = sections[index];
            if (!blocks.mayContain(section)) {
                continue;
            }
            final PalettedContainer<BlockState> states = section.getStates();
            final int baseY = (minSectionY + index) << 4;
            for (int i = 0; i < 4096; ++i) {
                final BlockState state = states.get(i);
                if (!blocks.isStorage(state)) {
                    continue;
                }
                if (entry == null) {
                    entry = this.chunks.computeIfAbsent(key, (final long ignored) -> new StorageChunk(level, chunkX, chunkZ));
                }
                final int x = (chunkX << 4) | (i & 15);
                final int y = baseY | i >>> 8;
                final int z = (chunkZ << 4) | ((i >>> 4) & 15);
                if (!decision.hides(entry, chunk, x, y, z, state)) {
                    continue;
                }
                if (hidden == null) {
                    hidden = new IntOpenHashSet();
                }
                hidden.add(StorageChunk.pack(x, y, z));
                builder.replace(x, y, z, state, blocks.filler(decision.access, level, x, y, z, state));
            }
        }
        if (entry != null) {
            // the packet replaces everything the client had of the chunk
            entry.setHidden(hidden == null ? new IntOpenHashSet() : hidden);
            if (hidden != null) {
                // the light updates of hidden storage that changes, like a furnace lighting up, must not tell
                builder.track();
            }
        }

        // the light of hidden storage of the neighbours reaches into the chunk too
        final int reach = BlockDisguises.MAX_LIGHT_REACH;
        collectEmitting(decision.access, level, (chunkX << 4) - reach, level.getMinY(), (chunkZ << 4) - reach,
            (chunkX << 4) + 15 + reach, level.getMaxY(), (chunkZ << 4) + 15 + reach,
            (final int x, final int y, final int z, final BlockState state) -> {
                if (x >> 4 == chunkX && z >> 4 == chunkZ) {
                    return;
                }
                final LevelChunk neighbour = level.getChunkSource().fullChunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
                final StorageChunk neighbourEntry = this.chunks.computeIfAbsent(CoordinateUtils.getChunkKey(x >> 4, z >> 4), (final long ignored) -> new StorageChunk(level, x >> 4, z >> 4));
                // storage the client already has hidden, or will have hidden once the chunk is sent
                final int status = neighbourEntry.status(StorageChunk.pack(x, y, z));
                if (status == StorageChunk.HIDDEN || status == StorageChunk.UNKNOWN && neighbour != null && decision.hides(neighbourEntry, neighbour, x, y, z, state)) {
                    builder.lower(x, y, z, state, 0);
                }
            });
    }

    /**
     * Decides whether storage of a chunk being sent is hidden, recording what is sent real.
     */
    private static final class Decision {

        final StorageGuard guard;
        final ServerLevel level;
        final StorageBlocks blocks;
        final OcclusionAccess access;
        final double eyeX;
        final double eyeY;
        final double eyeZ;
        final double nearSquared;
        final double traceSquared;

        Decision(final StorageGuard guard, final ServerLevel level, final StorageBlocks blocks, final WorldConfig.Anticheat.AntiStorageEsp config) {
            this.guard = guard;
            this.level = level;
            this.blocks = blocks;
            this.access = blocks.occlusion(level);
            final ServerPlayer player = guard.owner.player;
            this.eyeX = player.getX();
            this.eyeY = player.getEyeY();
            this.eyeZ = player.getZ();
            this.nearSquared = config.alwaysVisibleDistance * config.alwaysVisibleDistance;
            final double trace = Math.min(SEND_TRACE_DISTANCE, config.rayTraceDistance);
            this.traceSquared = trace * trace;
        }

        boolean hides(final StorageChunk entry, final LevelChunk chunk, final int x, final int y, final int z, final BlockState state) {
            final int packed = StorageChunk.pack(x, y, z);
            if (entry.status(packed) == StorageChunk.REVEALED) {
                return false;
            }
            if (!this.sees(chunk, x, y, z)) {
                // the halves of a double chest are sent alike, a lone half would tell that a chest is next to it
                final BlockPos partner = partnerOf(x, y, z, state);
                if (partner == null || !this.seesPartner(partner)) {
                    return true;
                }
            }
            if (entry.markRevealed(packed) && state.getLightEmission() > 0) {
                // chunks sent earlier got the light of its disguise
                this.guard.owner.disguises.queueRefresh(x, y, z, state.getLightEmission() - 1);
            }
            return false;
        }

        private boolean sees(final LevelChunk chunk, final int x, final int y, final int z) {
            final double distanceSquared = distanceSquared(x, y, z, this.eyeX, this.eyeY, this.eyeZ);
            return distanceSquared <= this.nearSquared || isOpenToSky(chunk, x, y, z)
                || distanceSquared <= this.traceSquared && FaceVisibility.isVisibleFrom(this.access, x, y, z, this.eyeX, this.eyeY, this.eyeZ);
        }

        private boolean seesPartner(final BlockPos partner) {
            final StorageChunk entry = this.guard.chunks.get(CoordinateUtils.getChunkKey(partner.getX() >> 4, partner.getZ() >> 4));
            if (entry != null && entry.level == this.level) {
                final int status = entry.status(StorageChunk.pack(partner.getX(), partner.getY(), partner.getZ()));
                if (status != StorageChunk.UNKNOWN) {
                    return status == StorageChunk.REVEALED;
                }
            }
            final LevelChunk chunk = this.level.getChunkSource().fullChunks.get(CoordinateUtils.getChunkKey(partner.getX() >> 4, partner.getZ() >> 4));
            return chunk != null && this.sees(chunk, partner.getX(), partner.getY(), partner.getZ());
        }
    }

    /**
     * @return the position of the other half if the state is half of a double chest
     */
    private static @Nullable BlockPos partnerOf(final int x, final int y, final int z, final BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return new BlockPos(x, y, z).relative(ChestBlock.getConnectedDirection(state));
    }

    private static double distanceSquared(final int x, final int y, final int z, final double eyeX, final double eyeY, final double eyeZ) {
        final double dx = x + 0.5 - eyeX;
        final double dy = y + 0.5 - eyeY;
        final double dz = z + 0.5 - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Whether nothing above the block stops a falling player: storage in the open is public, anyone flying over sees it.
     */
    private static boolean isOpenToSky(final LevelChunk chunk, final int x, final int y, final int z) {
        return y >= chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
    }

    /**
     * Collects the storage that emits light in the box.
     */
    public static void collectEmitting(final OcclusionAccess access, final ServerLevel level, final int minX, final int minY, final int minZ,
                                       final int maxX, final int maxY, final int maxZ, final Collector out) {
        final StorageBlocks blocks = StorageBlocks.of(level);
        if (blocks == null) {
            return;
        }
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
                    if (!blocks.mayContainEmitting(section)) {
                        continue;
                    }
                    final int fromY = Math.max(minY, sectionY << 4);
                    final int toY = Math.min(maxY, (sectionY << 4) + 15);
                    try {
                        for (int y = fromY; y <= toY; ++y) {
                            for (int z = fromZ; z <= toZ; ++z) {
                                for (int x = fromX; x <= toX; ++x) {
                                    final BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                    if (blocks.isEmitting(state)) {
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

    // filtering, any thread

    @Override
    public int sentEmission(final int x, final int y, final int z, final BlockState state) {
        if (!this.active) {
            return REAL;
        }
        final StorageChunk chunk = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        final int status = chunk == null ? StorageChunk.UNKNOWN : chunk.status(StorageChunk.pack(x, y, z));
        if (status == StorageChunk.HIDDEN) {
            return 0;
        }
        if (status == StorageChunk.REVEALED) {
            return REAL;
        }
        // the client doesn't have the chunk yet: as it would be sent
        final ServerPlayer player = this.owner.player;
        final double near = this.nearDistance();
        if (distanceSquared(x, y, z, player.getX(), player.getEyeY(), player.getZ()) <= near * near) {
            return REAL;
        }
        final LevelChunk levelChunk = player.level().getChunkSource().fullChunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        return levelChunk == null || isOpenToSky(levelChunk, x, y, z) ? REAL : 0;
    }

    /**
     * Whether the state of the block must not be sent to the player because it is storage hidden from them. Storage
     * the client doesn't have yet (placed, or changed into storage) is hidden too unless the player is close or it is
     * open to the sky, a pass reveals it if it can be seen. If hidden storage changed into something else, it stops
     * being hidden and the update goes through. Callable from any thread.
     */
    public boolean isHidden(final int x, final int y, final int z, final BlockState state) {
        if (!this.active) {
            return false;
        }
        final ServerPlayer player = this.owner.player;
        final ServerLevel level = player.level();
        final StorageBlocks blocks = StorageBlocks.of(level);
        if (blocks == null) {
            return false;
        }
        final long key = CoordinateUtils.getChunkKey(x >> 4, z >> 4);
        final int packed = StorageChunk.pack(x, y, z);
        StorageChunk chunk = this.chunks.get(key);
        if (chunk != null) {
            // every block update the player gets passes here: storage next to the block may not be sealed anymore
            chunk.sealedDirty = true;
            final int status = chunk.status(packed);
            if (status == StorageChunk.HIDDEN) {
                if (blocks.isStorage(state)) {
                    return true;
                }
                chunk.forget(packed);
                return false;
            }
            if (status == StorageChunk.REVEALED) {
                return false;
            }
        }
        if (!blocks.isStorage(state)) {
            return false;
        }
        if (chunk == null) {
            chunk = this.chunks.computeIfAbsent(key, (final long ignored) -> new StorageChunk(level, x >> 4, z >> 4));
        }
        final double near = this.nearDistance();
        final LevelChunk levelChunk = level.getChunkSource().fullChunks.get(key);
        final BlockPos partner = partnerOf(x, y, z, state);
        final StorageChunk partnerChunk = partner == null ? null : this.chunks.get(CoordinateUtils.getChunkKey(partner.getX() >> 4, partner.getZ() >> 4));
        if (distanceSquared(x, y, z, player.getX(), player.getEyeY(), player.getZ()) <= near * near
            || levelChunk != null && isOpenToSky(levelChunk, x, y, z)
            // a chest placed next to one the player sees becomes a double chest the player sees
            || partnerChunk != null && partnerChunk.status(StorageChunk.pack(partner.getX(), partner.getY(), partner.getZ())) == StorageChunk.REVEALED) {
            chunk.markRevealed(packed);
            return false;
        }
        chunk.hide(packed);
        if (!this.hiddenByUpdate) {
            this.hiddenByUpdate = true;
        }
        return true;
    }

    /**
     * Cheap check for multi block updates.
     *
     * @return {@code false} if the client has no hidden storage in the section. Callable from any thread
     */
    public boolean mayHideIn(final int chunkX, final int sectionY, final int chunkZ) {
        if (!this.active) {
            return false;
        }
        final StorageChunk chunk = this.chunks.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        return chunk != null && chunk.mayHideIn(sectionY);
    }

    /**
     * @return whether the block at the position is storage the client has hidden. Callable from any thread
     */
    public boolean isHiddenAt(final int x, final int y, final int z) {
        if (!this.active) {
            return false;
        }
        final StorageChunk chunk = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        return chunk != null && chunk.status(StorageChunk.pack(x, y, z)) == StorageChunk.HIDDEN;
    }

    /**
     * @return what hidden storage at the position is sent as, or {@code null} if it isn't hidden. Callable from any
     * thread
     */
    public @Nullable BlockState hiddenAs(final int x, final int y, final int z, final BlockState state) {
        if (!this.isHiddenAt(x, y, z)) {
            return null;
        }
        final ServerLevel level = this.owner.player.level();
        final StorageBlocks blocks = StorageBlocks.of(level);
        return blocks == null ? null : blocks.filler(new OcclusionAccess(level), level, x, y, z, state);
    }

    // visibility passes

    @Override
    public boolean prepareAsync(final CameraSnapshot snapshot) {
        if (this.hiddenByUpdate) {
            this.hiddenByUpdate = false;
            this.needsPass = true;
        }
        final WorldConfig.Anticheat.AntiStorageEsp config = this.owner.config().antiStorageEsp;
        final boolean stopped = !Double.isNaN(this.lastEyeX) && snapshot.distanceSquared(this.lastEyeX, this.lastEyeY, this.lastEyeZ) < 1.0E-6;
        this.lastEyeX = snapshot.eyeX;
        this.lastEyeY = snapshot.eyeY;
        this.lastEyeZ = snapshot.eyeZ;
        final double fullPassDistanceSquared = Double.isNaN(this.fullPassX) ? Double.POSITIVE_INFINITY : snapshot.distanceSquared(this.fullPassX, this.fullPassY, this.fullPassZ);
        // a player that stopped somewhere new looks around: everything is traced from there right away
        final boolean full = this.needsFullPass || fullPassDistanceSquared > FULL_MOVE_THRESHOLD_SQUARED
            || stopped && fullPassDistanceSquared > MOVE_THRESHOLD_SQUARED
            || snapshot.tick - this.fullPassTick >= config.updateIntervalTicks || snapshot.tick < this.fullPassTick;
        final boolean moved = Double.isNaN(this.passX) || snapshot.distanceSquared(this.passX, this.passY, this.passZ) > MOVE_THRESHOLD_SQUARED;
        if (!this.needsPass && !moved && !full) {
            return false;
        }
        this.needsPass = false;
        if (this.chunks.isEmpty()) {
            return false;
        }
        this.passX = snapshot.eyeX;
        this.passY = snapshot.eyeY;
        this.passZ = snapshot.eyeZ;
        if (full) {
            this.needsFullPass = false;
            this.fullPassX = snapshot.eyeX;
            this.fullPassY = snapshot.eyeY;
            this.fullPassZ = snapshot.eyeZ;
            this.fullPassTick = snapshot.tick;
        }
        this.jobFull = full;
        this.jobDistance = config.rayTraceDistance;
        this.jobNearDistance = config.alwaysVisibleDistance;
        this.jobThirdPerson = config.rayTraceThirdPerson;
        this.jobBudget = config.maxTracedBlocksPerTick;
        return true;
    }

    @Override
    public boolean isLatencySensitive() {
        // storage popping in late is noticed, like entities
        return true;
    }

    @Override
    public void runAsync(final CameraSnapshot snapshot, final OcclusionAccess access) {
        // a discarded job may have left references behind
        Arrays.fill(this.resultChunks, 0, this.resultCount, null);
        this.resultCount = 0;
        this.resultIncomplete = false;

        // cameras are placed like the client does, but storage is traced with storage occluding: a chest hides the
        // chest behind it, and rays through large storage rooms end early
        final double[] cameras = this.jobThirdPerson ? snapshot.cameras(access) : null;
        final StorageBlocks blocks = StorageBlocks.of(snapshot.level);
        if (blocks == null) {
            return;
        }
        final OcclusionCache cache = OcclusionCache.get(blocks.occlusion(snapshot.level), snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ);
        final boolean full = this.jobFull;
        final double distance = full ? this.jobDistance : Math.min(this.jobDistance, NEAR_TIER);
        final double distanceSquared = distance * distance;
        final double nearSquared = this.jobNearDistance * this.jobNearDistance;
        final double nearTierSquared = NEAR_TIER * NEAR_TIER;

        // the chunks close to the player first, so a pass that runs out of budget reveals what matters most. In both
        // groups the chunks traced longest ago come first, then the nearest: a pass that runs out of budget is
        // continued by the next one where it stopped, so all storage is traced eventually
        final ArrayList<StorageChunk> near = new ArrayList<>();
        for (final Iterator<StorageChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
            final StorageChunk chunk = iterator.next();
            if (chunk.level == snapshot.level && chunk.hasHidden()) {
                final double chunkDistanceSquared = chunkDistanceSquared(chunk, snapshot);
                if (chunkDistanceSquared <= distanceSquared) {
                    chunk.sortDistanceSquared = chunkDistanceSquared;
                    near.add(chunk);
                }
            }
        }
        near.sort((final StorageChunk a, final StorageChunk b) -> {
            final boolean nearA = a.sortDistanceSquared <= nearTierSquared;
            final boolean nearB = b.sortDistanceSquared <= nearTierSquared;
            if (nearA != nearB) {
                return nearA ? -1 : 1;
            }
            if (a.tracedPass != b.tracedPass) {
                return Integer.compare(a.tracedPass, b.tracedPass);
            }
            return Double.compare(a.sortDistanceSquared, b.sortDistanceSquared);
        });

        final int passNumber = ++this.passes;
        final int pass = full ? ++this.fullPasses : this.fullPasses;
        int budget = this.jobBudget;
        for (final StorageChunk chunk : near) {
            final LevelChunk levelChunk = snapshot.level.getChunkSource().fullChunks.get(chunk.key);
            // storage enclosed on all six sides can't be seen from anywhere: in large storage rooms most of it is. The
            // rest is remembered until the hidden storage or a block of the chunk changes, and only it is traced
            final int[] knownUnsealed = !chunk.sealedDirty && pass - chunk.sealedPass < SEALED_RECHECK_PASSES ? chunk.unsealed : null;
            final IntArrayList sealed = knownUnsealed == null && full ? new IntArrayList() : null;
            final IntArrayList unsealed = sealed == null ? null : new IntArrayList();
            if (sealed != null) {
                // a change from now on must be seen by the next pass
                chunk.sealedDirty = false;
            }
            if (knownUnsealed != null && chunk.sortDistanceSquared <= nearSquared) {
                // sealed storage is sent within the always visible distance all the same
                for (final int packed : chunk.sealed) {
                    if (snapshot.distanceSquared(chunk.blockX(packed) + 0.5, StorageChunk.unpackY(packed) + 0.5, chunk.blockZ(packed) + 0.5) <= nearSquared) {
                        this.addResult(chunk, packed);
                    }
                }
            }
            boolean complete = true;
            int resumeAt = -1;
            final int[] positions = knownUnsealed != null ? knownUnsealed : chunk.hidden();
            final int start = positions.length == 0 ? 0 : chunk.cursor % positions.length;
            for (int index = 0; index < positions.length; ++index) {
                final int at = start + index < positions.length ? start + index : start + index - positions.length;
                final int packed = positions[at];
                final int x = chunk.blockX(packed);
                final int y = StorageChunk.unpackY(packed);
                final int z = chunk.blockZ(packed);
                final double blockDistanceSquared = snapshot.distanceSquared(x + 0.5, y + 0.5, z + 0.5);
                if (blockDistanceSquared <= nearSquared || levelChunk != null && isOpenToSky(levelChunk, x, y, z)) {
                    this.addResult(chunk, packed);
                    continue;
                }
                if (knownUnsealed == null) {
                    if (isSealed(cache, x, y, z)) {
                        if (sealed != null) {
                            sealed.add(packed);
                        }
                        continue;
                    }
                    if (unsealed != null) {
                        unsealed.add(packed);
                    }
                }
                if (blockDistanceSquared > distanceSquared || !isExposed(cache, snapshot, cameras, x, y, z)) {
                    // storage enclosed by blocks (and other storage) facing the cameras costs no rays
                    continue;
                }
                if (this.owner.antiFreecam.isMasked(x, y, z)) {
                    // anti-freecam found no way to see it, the client has filler there: traced once anti-freecam
                    // reveals the block, which doesn't reveal hidden storage
                    continue;
                }
                if (budget <= 0) {
                    if (complete) {
                        complete = false;
                        resumeAt = at;
                    }
                    continue;
                }
                --budget;
                if (isVisible(cache, snapshot, cameras, x, y, z)) {
                    this.addResult(chunk, packed);
                }
            }
            if (sealed != null) {
                chunk.sealed = sealed.toIntArray();
                chunk.unsealed = unsealed.toIntArray();
                chunk.sealedPass = pass;
            }
            if (!complete) {
                this.resultIncomplete = true;
                chunk.cursor = resumeAt;
            } else {
                chunk.cursor = 0;
                chunk.tracedPass = passNumber;
            }
        }
    }

    /**
     * Whether all six neighbours of the block occlude. Only for blocks inside their chunk section, whose neighbours are
     * all in the same section: a change of the section's blocks reaches {@link #isHidden} as a block update.
     */
    private static boolean isSealed(final BlockOcclusion access, final int x, final int y, final int z) {
        final int inX = x & 15;
        final int inY = y & 15;
        final int inZ = z & 15;
        if (inX == 0 || inX == 15 || inY == 0 || inY == 15 || inZ == 0 || inZ == 15) {
            return false;
        }
        return access.isOpaque(x - 1, y, z) && access.isOpaque(x + 1, y, z) && access.isOpaque(x, y - 1, z)
            && access.isOpaque(x, y + 1, z) && access.isOpaque(x, y, z - 1) && access.isOpaque(x, y, z + 1);
    }

    private static boolean isExposed(final BlockOcclusion access, final CameraSnapshot snapshot, final double @Nullable [] cameras,
                                     final int x, final int y, final int z) {
        if (cameras == null) {
            return FaceVisibility.isExposedTo(access, x, y, z, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ);
        }
        for (int i = 0; i < cameras.length; i += 3) {
            if (FaceVisibility.isExposedTo(access, x, y, z, cameras[i], cameras[i + 1], cameras[i + 2])) {
                return true;
            }
        }
        return false;
    }

    private static double chunkDistanceSquared(final StorageChunk chunk, final CameraSnapshot snapshot) {
        final double dx = Math.max(0.0, Math.max((chunk.chunkX << 4) - snapshot.eyeX, snapshot.eyeX - ((chunk.chunkX << 4) + 16)));
        final double dz = Math.max(0.0, Math.max((chunk.chunkZ << 4) - snapshot.eyeZ, snapshot.eyeZ - ((chunk.chunkZ << 4) + 16)));
        return dx * dx + dz * dz;
    }

    private static boolean isVisible(final BlockOcclusion access, final CameraSnapshot snapshot, final double @Nullable [] cameras,
                                     final int x, final int y, final int z) {
        if (cameras == null) {
            return FaceVisibility.isVisibleFrom(access, x, y, z, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ);
        }
        for (int i = 0; i < cameras.length; i += 3) {
            if (i != 0 && cameras[i] == snapshot.eyeX && cameras[i + 1] == snapshot.eyeY && cameras[i + 2] == snapshot.eyeZ) {
                // a third person camera pulled all the way into the head
                continue;
            }
            if (FaceVisibility.isVisibleFrom(access, x, y, z, cameras[i], cameras[i + 1], cameras[i + 2])) {
                return true;
            }
        }
        return false;
    }

    private void addResult(final StorageChunk chunk, final int packed) {
        final int count = this.resultCount;
        if (count == this.resultPositions.length) {
            this.resultChunks = Arrays.copyOf(this.resultChunks, count << 1);
            this.resultPositions = Arrays.copyOf(this.resultPositions, count << 1);
        }
        this.resultChunks[count] = chunk;
        this.resultPositions[count] = packed;
        this.resultCount = count + 1;
    }

    @Override
    public void applyResults(final CameraSnapshot snapshot) {
        final int count = this.resultCount;
        this.resultCount = 0;
        final ServerLevel level = this.owner.player.level();
        for (int i = 0; i < count; ++i) {
            final StorageChunk chunk = this.resultChunks[i];
            this.resultChunks[i] = null;
            if (this.chunks.get(chunk.key) != chunk || chunk.level != level) {
                // unloaded or sent again since
                continue;
            }
            this.reveal(chunk, this.resultPositions[i]);
        }
        if (this.resultIncomplete) {
            // the rest next tick, the chunks that weren't traced first
            this.needsPass = true;
            this.needsFullPass = true;
        }
    }

    /**
     * Sends the real state of hidden storage, with its block entity and the light around it. The other half of a
     * double chest goes along.
     *
     * @return whether the storage isn't hidden anymore
     */
    private boolean reveal(final StorageChunk chunk, final int packed) {
        if (!TickThread.isTickThreadFor(chunk.level, chunk.chunkX, chunk.chunkZ)) {
            // retried once the chunk is owned by the player's region
            this.needsPass = true;
            return false;
        }
        final LevelChunk levelChunk = chunk.level.getChunkSource().getChunkAtIfLoadedImmediately(chunk.chunkX, chunk.chunkZ);
        if (levelChunk == null) {
            return false;
        }
        if (!chunk.reveal(packed)) {
            return true;
        }
        final BlockPos pos = new BlockPos(chunk.blockX(packed), StorageChunk.unpackY(packed), chunk.blockZ(packed));
        final BlockState state = levelChunk.getBlockState(pos);
        final StorageBlocks blocks = StorageBlocks.of(chunk.level);
        if (blocks == null || !blocks.isStorage(state)) {
            // changed meanwhile, the update went through
            return true;
        }
        final ServerPlayer player = this.owner.player;
        // what anti-xray or anti-freecam hide gets the real state once they reveal it
        if (!this.owner.antiXray.isHidden(pos.getX(), pos.getY(), pos.getZ(), state) && !this.owner.antiFreecam.withholdsUpdate(pos.getX(), pos.getY(), pos.getZ())) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            if (state.hasBlockEntity()) {
                final BlockEntity blockEntity = levelChunk.getBlockEntity(pos);
                final Packet<ClientGamePacketListener> packet = blockEntity == null ? null : blockEntity.getUpdatePacket();
                if (packet != null) {
                    player.connection.send(packet);
                }
            }
        }
        if (state.getLightEmission() > 0) {
            this.owner.disguises.queueRefresh(pos.getX(), pos.getY(), pos.getZ(), state.getLightEmission() - 1);
        }
        final BlockPos partner = partnerOf(pos.getX(), pos.getY(), pos.getZ(), state);
        if (partner != null) {
            final StorageChunk partnerChunk = this.chunks.get(CoordinateUtils.getChunkKey(partner.getX() >> 4, partner.getZ() >> 4));
            final int partnerPacked = StorageChunk.pack(partner.getX(), partner.getY(), partner.getZ());
            if (partnerChunk != null && partnerChunk.status(partnerPacked) == StorageChunk.HIDDEN) {
                this.reveal(partnerChunk, partnerPacked);
            }
        }
        return true;
    }

    /**
     * The storage of a chunk the client has hidden and the storage it has seen real, as block positions packed by
     * {@link #pack}. Guarded by itself.
     */
    private static final class StorageChunk {

        static final int UNKNOWN = 0;
        static final int HIDDEN = 1;
        static final int REVEALED = 2;

        final ServerLevel level;
        final long key;
        final int chunkX;
        final int chunkZ;
        /**
         * The last pass that traced all of the chunk's hidden storage in its range, where the last pass that ran out of
         * budget stopped, and the distance of the chunk for the pass being run. Only touched by the passes.
         */
        int tracedPass;
        int cursor;
        double sortDistanceSquared;
        /**
         * The hidden storage enclosed on all six sides and the rest, as of the full pass {@link #sealedPass}. Only
         * touched by the passes. Dirty once the hidden storage or a block of the chunk changed.
         */
        int[] sealed = new int[0];
        int @Nullable [] unsealed;
        int sealedPass;
        volatile boolean sealedDirty = true;
        private IntOpenHashSet hidden = new IntOpenHashSet();
        /**
         * The sections that had hidden storage since the chunk was sent, a bit per section y modulo 64.
         */
        private volatile long hiddenSections;
        private final IntOpenHashSet revealed = new IntOpenHashSet();

        StorageChunk(final ServerLevel level, final int chunkX, final int chunkZ) {
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

        synchronized int status(final int packed) {
            return this.hidden.contains(packed) ? HIDDEN : this.revealed.contains(packed) ? REVEALED : UNKNOWN;
        }

        synchronized boolean hasHidden() {
            return !this.hidden.isEmpty();
        }

        synchronized int[] hidden() {
            return this.hidden.toIntArray();
        }

        /**
         * Replaces what the client has hidden of the chunk, for a chunk packet.
         */
        synchronized void setHidden(final IntOpenHashSet hidden) {
            this.hidden = hidden;
            this.sealedDirty = true;
            long sections = 0L;
            for (final IntIterator iterator = hidden.iterator(); iterator.hasNext(); ) {
                sections |= sectionBit(iterator.nextInt());
            }
            this.hiddenSections = sections;
        }

        synchronized void hide(final int packed) {
            this.hidden.add(packed);
            this.sealedDirty = true;
            this.hiddenSections |= sectionBit(packed);
        }

        boolean mayHideIn(final int sectionY) {
            return (this.hiddenSections & 1L << sectionY) != 0L;
        }

        private static long sectionBit(final int packed) {
            // shifts use the low 6 bits only
            return 1L << (unpackY(packed) >> 4);
        }

        /**
         * @return whether the storage wasn't recorded as seen real before
         */
        synchronized boolean markRevealed(final int packed) {
            if (this.hidden.remove(packed)) {
                this.sealedDirty = true;
            }
            return this.revealed.add(packed);
        }

        /**
         * @return whether the storage was hidden, and is recorded as seen real now
         */
        synchronized boolean reveal(final int packed) {
            if (!this.hidden.remove(packed)) {
                return false;
            }
            this.sealedDirty = true;
            this.revealed.add(packed);
            return true;
        }

        synchronized void forget(final int packed) {
            this.hidden.remove(packed);
            this.sealedDirty = true;
        }
    }
}
