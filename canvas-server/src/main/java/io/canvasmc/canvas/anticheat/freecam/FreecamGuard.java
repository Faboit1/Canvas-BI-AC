package io.canvasmc.canvas.anticheat.freecam;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.AnticheatExecutor;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.VoxelRaycaster;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Per-player anti-freecam.
 * <p>
 * Freecam moves the camera of a client through walls, which the server can't observe. So instead the server only
 * sends the real contents of the chunk sections a vanilla client could render from the player's real position, and
 * sends every other section as solid filler without light and block entities (see {@link ChunkMasking}). Which
 * sections could be rendered is computed by replicating the client's own section occlusion culling, as a superset
 * of it ({@link OcclusionGraph}, {@link SectionVisibility}), from every camera position within
 * {@code reveal-margin} blocks of the eyes. Legit players therefore never get to see the filler.
 * <p>
 * A section is sent real if it is in the last computed potentially visible set (PVS), lies around the eyes, reaches
 * the surface of its chunk (see {@link #surfaceSections}), or was sent real before (reveals are sticky until the
 * client forgets the chunk, which avoids churn). Chunks are only sent once a PVS covers them
 * ({@link #mayChunkBeSent}), for at most a second; anything that sends a chunk the PVS doesn't cover yet gets
 * everything outside the start box masked until it does. Whenever a new PVS contains masked sections, their chunks
 * are re-sent to this player, nearest first and rate limited. Block, block entity, block event and light updates
 * inside masked sections are dropped, since the re-sent chunk brings the current state anyway.
 * <p>
 * Threading: everything runs on the player's region thread, except {@link #runAsync} (PVS computation on an
 * anticheat worker) and the leak filters {@link #isSectionMasked}, {@link #isMasked} and {@link #filterLightUpdate},
 * which read the sent chunks through a concurrent map and a volatile mask per chunk.
 */
public final class FreecamGuard implements VisibilityModule {

    /**
     * Masks are longs with one bit per section.
     */
    private static final int MAX_SECTIONS = 64;
    /**
     * The start box reaches this many ticks of movement ahead of the eyes, so fast players have their path
     * revealed before they get there.
     */
    private static final double LOOKAHEAD_TICKS = 10.0;
    /**
     * Speed, in blocks per tick, above which the lookahead stops growing (fast elytra flight).
     */
    private static final double MAX_LOOKAHEAD_SPEED = 3.0;
    /**
     * Movement faster than this, in blocks per tick, is a teleport and gets no lookahead at all. Falling (3.9) and
     * elytra flight (3.5) stay below it.
     */
    private static final double TELEPORT_SPEED = 4.0;
    private static final int MAX_GRID_RADIUS = 127;
    /**
     * How long chunks are withheld at most while waiting for a PVS that covers them. After that they are sent with
     * everything outside the start box masked and revealed once the PVS arrives, so a lagging worker pool can never
     * keep a player from getting chunks.
     */
    private static final long MAX_WITHHOLD_NANOS = 1_000_000_000L;
    private static final long NOT_WITHHOLDING = Long.MIN_VALUE;
    /**
     * Sections of a chunk that reach this quantile of its surface (the highest blocks of its columns, lowest first)
     * are never masked, see {@link #surfaceSections}.
     */
    private static final int SURFACE_QUANTILE_COLUMN = 64;
    /**
     * Minimum ticks between two checks of the PVS requested by {@link #onOpacityChange}.
     */
    private static final int OPACITY_CHECK_INTERVAL = 2;

    private static final AtomicBoolean WARNED_HEIGHT = new AtomicBoolean();
    private static final ThreadLocal<Worker> WORKERS = ThreadLocal.withInitial(Worker::new);

    private final PlayerVisibility owner;

    /**
     * Chunks sent to the player that have, or had, masked sections. Written on the region thread only.
     */
    private final ConcurrentChainedLong2ReferenceHashTable<SentChunk> sent = new ConcurrentChainedLong2ReferenceHashTable<>();
    /**
     * Amount of sent chunks with masked sections, lets the leak filters answer without a lookup in the common case.
     * Written on the region thread only.
     */
    private volatile int maskedChunks;
    /**
     * Set by {@link #onOpacityChange} from any thread, requests a check of the PVS.
     */
    private volatile boolean opacityChanged;

    // region thread state
    private @Nullable Pvs current;
    private @Nullable Pvs spare;
    /**
     * Bumped whenever the results of a running job become useless (resets).
     */
    private long generation;
    private boolean chunksChanged = true;
    private int lastJobTick;
    private boolean jobScheduled;
    /**
     * When chunks were first withheld for lack of a covering PVS since the last PVS was applied, or
     * {@link #NOT_WITHHOLDING}.
     */
    private long withheldSince = NOT_WITHHOLDING;
    /**
     * Whether the reveal queue holds the chunks to unmask after the module was deactivated.
     */
    private boolean unmasking;
    /**
     * The camera distance of the last snapshot, or {@code NaN} before the first one.
     */
    private double cameraDistance = Double.NaN;
    private @Nullable OcclusionAccess tickAccess;
    private final int[] surfaceHeights = new int[16 * 16];
    /**
     * The start box of the last snapshot in section coordinates: min x, y, z, max x, y, z.
     */
    private final int[] box = new int[6];
    private @Nullable Level boxLevel;
    private final int[] lastJobBox = new int[6];
    private final int[] teleportBox = new int[6];
    private final int[] maskBox = new int[6];
    private long[] revealQueue = new long[32];
    private int revealQueueHead;
    private int revealQueueSize;

    // job inputs, written by prepareAsync and read by runAsync
    private @Nullable Pvs jobResult;
    private @Nullable Pvs jobPrevious;
    private boolean jobCheckOnly;
    private final int[] jobBox = new int[6];

    public FreecamGuard(final PlayerVisibility owner) {
        this.owner = owner;
    }

    private static final class SentChunk {
        final LevelChunk chunk;
        final int minSectionY;
        /**
         * Sections the client currently has as filler. Read from any thread.
         */
        volatile long masked;
        /**
         * Sections the client got real since the chunk was sent, which are never masked again.
         */
        long revealed;

        SentChunk(final LevelChunk chunk) {
            this.chunk = chunk;
            this.minSectionY = chunk.getMinSectionY();
        }
    }

    /**
     * A potentially visible set, one bit per section for every column of a grid centered on the player.
     */
    private static final class Pvs {
        @Nullable Level level;
        long generation;
        boolean unchanged;
        int originX;
        int originZ;
        int width;
        int depth;
        int height;
        int minSectionY;
        long[] visible = new long[0];
        /**
         * Whether the column was loaded when the PVS was computed. Sections of other columns weren't explored.
         */
        boolean[] covered = new boolean[0];
        /**
         * Hash of the identity of the explored columns and the opacity versions of the visible sections, to detect
         * block changes that could change the result.
         */
        long fingerprint;

        void setGrid(final int originX, final int originZ, final int width, final int depth, final int height, final int minSectionY) {
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.depth = depth;
            this.height = height;
            this.minSectionY = minSectionY;
            final int columns = width * depth;
            if (this.visible.length < columns) {
                this.visible = new long[columns];
                this.covered = new boolean[columns];
            }
        }

        int column(final int chunkX, final int chunkZ) {
            final int x = chunkX - this.originX;
            final int z = chunkZ - this.originZ;
            if (x < 0 || x >= this.width || z < 0 || z >= this.depth) {
                return -1;
            }
            final int column = z * this.width + x;
            return this.covered[column] ? column : -1;
        }
    }

    /**
     * Working memory of a worker thread.
     */
    private static final class Worker implements OcclusionGraph.Sections {
        private static final int UNRECORDED = Integer.MIN_VALUE;

        final OcclusionGraph graph = new OcclusionGraph();
        final SectionVisibility.Scratch scratch = new SectionVisibility.Scratch();
        LevelChunkSection[] @Nullable [] columns = new LevelChunkSection[0][];
        int height;
        /**
         * Per section of the grid: the opacity version its visibility set was computed for while recording.
         */
        int[] versions = new int[0];
        boolean recording;

        void load(final OcclusionAccess access, final Pvs pvs) {
            final int count = pvs.width * pvs.depth;
            if (this.columns.length < count) {
                this.columns = new LevelChunkSection[count][];
            }
            this.height = pvs.height;
            for (int z = 0; z < pvs.depth; ++z) {
                for (int x = 0; x < pvs.width; ++x) {
                    final LevelChunkSection[] sections = access.sections(pvs.originX + x, pvs.originZ + z);
                    this.columns[z * pvs.width + x] = sections != null && sections.length == pvs.height ? sections : null;
                }
            }
        }

        /**
         * Starts remembering the versions of the visibility sets read for the loaded grid, so the fingerprint of the
         * result describes the blocks it was really computed from. Reading the versions afterwards would stamp a
         * result computed from older blocks with the version of a change that happened in the meantime, and the
         * change would never be noticed.
         */
        void startRecording(final Pvs pvs) {
            final int nodes = pvs.width * pvs.depth * pvs.height;
            if (this.versions.length < nodes) {
                this.versions = new int[nodes];
            }
            Arrays.fill(this.versions, 0, nodes, UNRECORDED);
            this.recording = true;
        }

        void release() {
            Arrays.fill(this.columns, null);
            this.recording = false;
        }

        long fingerprint(final Pvs pvs) {
            long hash = 1L;
            for (int column = 0, count = pvs.width * pvs.depth; column < count; ++column) {
                final LevelChunkSection[] sections = this.columns[column];
                hash = hash * 31L + (sections == null ? 0 : System.identityHashCode(sections));
                if (sections == null) {
                    continue;
                }
                for (long visible = pvs.visible[column]; visible != 0L; visible &= visible - 1L) {
                    final int index = Long.numberOfTrailingZeros(visible);
                    // start sections are left through every face, their contents never mattered
                    int version = this.recording ? this.versions[column * this.height + index] : UNRECORDED;
                    if (version == UNRECORDED) {
                        version = SectionVisibility.version(sections[index]);
                    }
                    hash = hash * 31L + version;
                }
            }
            return hash;
        }

        @Override
        public boolean hasColumn(final int column) {
            return this.columns[column] != null;
        }

        @Override
        public long visibility(final int column, final int sectionIndex) {
            final LevelChunkSection section = this.columns[column][sectionIndex];
            final int version = SectionVisibility.version(section);
            if (this.recording) {
                this.versions[column * this.height + sectionIndex] = version;
            }
            return SectionVisibility.get(section, this.scratch, version);
        }
    }

    // module lifecycle

    @Override
    public boolean isActive(final WorldConfig.Anticheat config) {
        return config.antiFreecam.enabled && !this.owner.bypassesAntiFreecam() && supportsHeight(this.owner.player.level());
    }

    private static boolean supportsHeight(final Level level) {
        if (level.getSectionsCount() <= MAX_SECTIONS) {
            return true;
        }
        if (WARNED_HEIGHT.compareAndSet(false, true)) {
            AnticheatExecutor.LOGGER.warn("Anti-freecam is not supported in worlds taller than {} chunk sections, it is disabled in {}", MAX_SECTIONS, level.getWorld().getName());
        }
        return false;
    }

    @Override
    public void reset() {
        ++this.generation;
        this.current = null;
        this.spare = null;
        this.boxLevel = null;
        this.chunksChanged = true;
        this.jobScheduled = false;
        this.withheldSince = NOT_WITHHOLDING;
        this.revealQueueHead = 0;
        this.revealQueueSize = 0;
        this.unmasking = false;

        final ServerPlayer player = this.owner.player;
        final ServerLevel level = player.level();
        final boolean active = this.isActive(this.owner.config());
        final LongArrayList stale = new LongArrayList();
        final int centerX = player.chunkPosition().x();
        final int centerZ = player.chunkPosition().z();
        for (final Iterator<SentChunk> iterator = this.sent.valueIterator(); iterator.hasNext(); ) {
            final SentChunk entry = iterator.next();
            final int chunkX = entry.chunk.getPos().x();
            final int chunkZ = entry.chunk.getPos().z();
            if (entry.chunk.getLevel() != level) {
                stale.add(CoordinateUtils.getChunkKey(chunkX, chunkZ));
            } else if (!active && entry.masked != 0L) {
                this.enqueueReveal(chunkX, chunkZ, centerX, centerZ);
            }
        }
        for (int i = 0; i < stale.size(); ++i) {
            this.remove(stale.getLong(i));
        }
        if (this.revealQueueSize != 0) {
            // the module stopped (bypass, spectator, disabled): give the client everything it is missing, nearest
            // first and spread over a few ticks
            Arrays.sort(this.revealQueue, 0, this.revealQueueSize);
            this.unmasking = true;
        }
    }

    /**
     * Re-sends the chunks that still have masked sections after the module was deactivated, rate limited. Chunks the
     * client forgot in the meantime aren't in {@link #sent} anymore and are skipped.
     */
    @Override
    public void tickInactive() {
        if (!this.unmasking) {
            return;
        }
        final int limit = 2 * this.owner.config().antiFreecam.maxResendsPerTick;
        int sentNow = 0;
        while (this.revealQueueHead < this.revealQueueSize && sentNow < limit) {
            final long queued = this.revealQueue[this.revealQueueHead++];
            final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(unpackQueuedX(queued), unpackQueuedZ(queued)));
            if (entry != null && entry.masked != 0L) {
                this.resend(entry);
                ++sentNow;
            }
        }
        if (this.revealQueueHead >= this.revealQueueSize) {
            this.unmasking = false;
            this.revealQueueHead = 0;
            this.revealQueueSize = 0;
        }
    }

    /**
     * Keeps the start box current and reveals what it needs every tick, also while a PVS is being computed.
     */
    @Override
    public void tick(final CameraSnapshot snapshot) {
        if (this.unmasking) {
            // reactivated before everything was unmasked, the chunks that are still masked stay tracked as such
            this.unmasking = false;
            this.revealQueueHead = 0;
            this.revealQueueSize = 0;
        }
        final WorldConfig.Anticheat.AntiFreecam config = this.owner.config().antiFreecam;
        this.cameraDistance = snapshot.cameraDistance;
        final double ahead = this.lookahead(snapshot);
        final double speed = Math.sqrt(snapshot.velocityX * snapshot.velocityX + snapshot.velocityY * snapshot.velocityY + snapshot.velocityZ * snapshot.velocityZ);
        final double scale = ahead == 0.0 ? 0.0 : ahead / speed;
        startBox(this.box, snapshot.level, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ, margin(config, snapshot.cameraDistance),
            snapshot.velocityX * scale, snapshot.velocityY * scale, snapshot.velocityZ * scale);
        this.boxLevel = snapshot.level;

        this.revealBox(this.box);
        this.processRevealQueue(config.maxResendsPerTick);
    }

    /**
     * How far, in blocks, the start box reaches ahead of the eyes along the movement: the distance moved in
     * {@link #LOOKAHEAD_TICKS}, but never past the first block in the way, since the player can't move through it.
     * Sections behind walls must not become start sections, those are entered from every side.
     */
    private double lookahead(final CameraSnapshot snapshot) {
        final double speed = Math.sqrt(snapshot.velocityX * snapshot.velocityX + snapshot.velocityY * snapshot.velocityY + snapshot.velocityZ * snapshot.velocityZ);
        if (speed < 1.0E-3 || speed > TELEPORT_SPEED) {
            return 0.0;
        }
        final double distance = LOOKAHEAD_TICKS * Math.min(speed, MAX_LOOKAHEAD_SPEED);
        OcclusionAccess access = this.tickAccess;
        if (access == null || access.level() != snapshot.level) {
            this.tickAccess = access = new OcclusionAccess(snapshot.level);
        }
        if (access.isOpaque(Mth.floor(snapshot.eyeX), Mth.floor(snapshot.eyeY), Mth.floor(snapshot.eyeZ))) {
            return 0.0;
        }
        return VoxelRaycaster.clipDistance(access, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ,
            snapshot.velocityX / speed, snapshot.velocityY / speed, snapshot.velocityZ / speed, distance);
    }

    @Override
    public boolean prepareAsync(final CameraSnapshot snapshot) {
        final WorldConfig.Anticheat.AntiFreecam config = this.owner.config().antiFreecam;
        final ServerLevel level = snapshot.level;
        final boolean boxChanged = !this.jobScheduled || !Arrays.equals(this.box, this.lastJobBox);
        final int sinceLastJob = snapshot.tick - this.lastJobTick;
        final boolean periodic = sinceLastJob >= config.updateIntervalTicks || sinceLastJob < 0
            || (this.opacityChanged && sinceLastJob >= OPACITY_CHECK_INTERVAL);
        if (this.current != null && !boxChanged && !this.chunksChanged && !periodic) {
            return false;
        }
        this.opacityChanged = false;

        final int sections = level.getSectionsCount();
        int radius = RegionizedPlayerChunkLoader.getAPISendViewDistance(this.owner.player);
        radius = Mth.clamp(radius < 0 ? level.getServer().getPlayerList().getViewDistance() : radius, 2, MAX_GRID_RADIUS - 1) + 1;
        final int centerX = Mth.floor(snapshot.eyeX) >> 4;
        final int centerZ = Mth.floor(snapshot.eyeZ) >> 4;

        Pvs result = this.spare;
        this.spare = null;
        if (result == null) {
            result = new Pvs();
        }
        result.level = level;
        result.generation = this.generation;
        result.setGrid(centerX - radius, centerZ - radius, 2 * radius + 1, 2 * radius + 1, sections, level.getMinSectionY());

        this.jobCheckOnly = this.current != null && !boxChanged && !this.chunksChanged;
        this.jobPrevious = this.current;
        this.jobResult = result;
        System.arraycopy(this.box, 0, this.jobBox, 0, 6);
        System.arraycopy(this.box, 0, this.lastJobBox, 0, 6);
        this.jobScheduled = true;
        this.chunksChanged = false;
        this.lastJobTick = snapshot.tick;
        return true;
    }

    @Override
    public void runAsync(final CameraSnapshot snapshot, final OcclusionAccess access) {
        final Pvs result = this.jobResult;
        if (result == null) {
            return;
        }
        final Worker worker = WORKERS.get();
        try {
            final Pvs previous = this.jobPrevious;
            if (this.jobCheckOnly && previous != null) {
                worker.load(access, previous);
                if (worker.fingerprint(previous) == previous.fingerprint) {
                    result.unchanged = true;
                    return;
                }
            }
            result.unchanged = false;
            worker.load(access, result);
            worker.startRecording(result);

            final int[] box = this.jobBox;
            final int maxSectionY = result.minSectionY + result.height - 1;
            worker.graph.compute(
                worker, result.width, result.depth, result.height,
                box[0] - result.originX, box[1] - result.minSectionY, box[2] - result.originZ,
                box[3] - result.originX, box[4] - result.minSectionY, box[5] - result.originZ,
                // a camera outside the world makes the client start from the whole top or bottom layer
                box[4] > maxSectionY, box[1] < result.minSectionY,
                result.visible
            );
            for (int column = 0, count = result.width * result.depth; column < count; ++column) {
                result.covered[column] = worker.columns[column] != null;
            }
            result.fingerprint = worker.fingerprint(result);
        } finally {
            worker.release();
        }
    }

    @Override
    public void applyResults(final CameraSnapshot snapshot) {
        final Pvs result = this.jobResult;
        this.jobResult = null;
        this.jobPrevious = null;
        if (result == null) {
            return;
        }
        if (result.generation != this.generation || result.unchanged) {
            this.spare = result;
            return;
        }
        this.spare = this.current;
        this.current = result;
        this.withheldSince = NOT_WITHHOLDING;
        this.queueReveals(result, Mth.floor(snapshot.eyeX) >> 4, Mth.floor(snapshot.eyeZ) >> 4);
    }

    // reveals

    /**
     * Queues every sent chunk with masked sections in the PVS, nearest first.
     */
    private void queueReveals(final Pvs pvs, final int centerX, final int centerZ) {
        this.revealQueueHead = 0;
        this.revealQueueSize = 0;
        for (final Iterator<SentChunk> iterator = this.sent.valueIterator(); iterator.hasNext(); ) {
            final SentChunk entry = iterator.next();
            final long masked = entry.masked;
            if (masked == 0L) {
                continue;
            }
            final int chunkX = entry.chunk.getPos().x();
            final int chunkZ = entry.chunk.getPos().z();
            final int column = pvs.column(chunkX, chunkZ);
            if (column < 0 || (masked & pvs.visible[column]) == 0L) {
                continue;
            }
            this.enqueueReveal(chunkX, chunkZ, centerX, centerZ);
        }
        Arrays.sort(this.revealQueue, 0, this.revealQueueSize);
    }

    /**
     * Appends a chunk to the reveal queue, keyed so that sorting orders the queue nearest first.
     */
    private void enqueueReveal(final int chunkX, final int chunkZ, final int centerX, final int centerZ) {
        final long dx = chunkX - centerX;
        final long dz = chunkZ - centerZ;
        if (this.revealQueueSize == this.revealQueue.length) {
            this.revealQueue = Arrays.copyOf(this.revealQueue, this.revealQueue.length * 2);
        }
        this.revealQueue[this.revealQueueSize++] = Math.min(dx * dx + dz * dz, 0x7FFFFL) << 44 | (chunkX & 0x3FFFFFL) << 22 | (chunkZ & 0x3FFFFFL);
    }

    private static int unpackQueuedX(final long queued) {
        return (int) (queued << 20 >> 42);
    }

    private static int unpackQueuedZ(final long queued) {
        return (int) (queued << 42 >> 42);
    }

    private void processRevealQueue(final int limit) {
        final Pvs pvs = this.current;
        int sentNow = 0;
        while (this.revealQueueHead < this.revealQueueSize && sentNow < limit) {
            final long queued = this.revealQueue[this.revealQueueHead++];
            final int chunkX = unpackQueuedX(queued);
            final int chunkZ = unpackQueuedZ(queued);
            final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
            if (entry == null || pvs == null) {
                continue;
            }
            final int column = pvs.column(chunkX, chunkZ);
            if (column >= 0 && (entry.masked & pvs.visible[column]) != 0L) {
                this.resend(entry);
                ++sentNow;
            }
        }
        if (this.revealQueueHead >= this.revealQueueSize) {
            this.revealQueueHead = 0;
            this.revealQueueSize = 0;
        }
    }

    /**
     * Immediately re-sends every chunk with masked sections inside the box, the client must never be inside filler.
     */
    private void revealBox(final int[] box) {
        if (this.maskedChunks == 0) {
            return;
        }
        for (int chunkZ = box[2]; chunkZ <= box[5]; ++chunkZ) {
            for (int chunkX = box[0]; chunkX <= box[3]; ++chunkX) {
                final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
                if (entry != null && (entry.masked & boxMask(box, chunkX, chunkZ, entry.minSectionY, entry.chunk.getSectionsCount())) != 0L) {
                    this.resend(entry);
                }
            }
        }
    }

    private void resend(final SentChunk entry) {
        final ServerPlayer player = this.owner.player;
        final LevelChunk chunk = entry.chunk;
        if (chunk.getLevel() != player.level()) {
            // left over from the previous world, the client doesn't have it anymore
            return;
        }
        final boolean modifyBlocks = chunk.getLevel().chunkPacketBlockController.shouldModify(player, chunk);
        final ClientboundLevelChunkWithLightPacket packet = this.owner.createChunkPacket(chunk, modifyBlocks);
        player.connection.send(packet);
        this.owner.onChunkSent(chunk, packet);
    }

    // start box

    private static double margin(final WorldConfig.Anticheat.AntiFreecam config, final double cameraDistance) {
        // third person cameras are pulled back up to the camera distance
        return Math.max(config.revealMargin, cameraDistance + 1.0);
    }

    /**
     * Computes the sections a camera within {@code margin} blocks of the eyes, or along the given movement, could be
     * in. The result may extend outside the world.
     */
    private static void startBox(final int[] out, final Level level, final double eyeX, final double eyeY, final double eyeZ,
                                 final double margin, final double aheadX, final double aheadY, final double aheadZ) {
        out[0] = Mth.floor(eyeX - margin + Math.min(0.0, aheadX)) >> 4;
        out[1] = Mth.floor(eyeY - margin + Math.min(0.0, aheadY)) >> 4;
        out[2] = Mth.floor(eyeZ - margin + Math.min(0.0, aheadZ)) >> 4;
        out[3] = Mth.floor(eyeX + margin + Math.max(0.0, aheadX)) >> 4;
        out[4] = Mth.floor(eyeY + margin + Math.max(0.0, aheadY)) >> 4;
        out[5] = Mth.floor(eyeZ + margin + Math.max(0.0, aheadZ)) >> 4;
        // keep the box finite for players far outside the world
        out[1] = Mth.clamp(out[1], level.getMinSectionY() - 1, level.getMaxSectionY() + 1);
        out[4] = Mth.clamp(out[4], level.getMinSectionY() - 1, level.getMaxSectionY() + 1);
    }

    private void currentBox(final int[] out) {
        final ServerPlayer player = this.owner.player;
        // before the first snapshot only the attribute is known, the snapshot also accounts for scale and vehicles
        final double cameraDistance = Double.isNaN(this.cameraDistance) ? player.getAttributeValue(Attributes.CAMERA_DISTANCE) : this.cameraDistance;
        startBox(out, player.level(), player.getX(), player.getEyeY(), player.getZ(),
            margin(this.owner.config().antiFreecam, cameraDistance), 0.0, 0.0, 0.0);
    }

    private static long boxMask(final int[] box, final int chunkX, final int chunkZ, final int minSectionY, final int sections) {
        if (chunkX < box[0] || chunkX > box[3] || chunkZ < box[2] || chunkZ > box[5]) {
            return 0L;
        }
        final int from = Math.max(0, box[1] - minSectionY);
        final int to = Math.min(sections - 1, box[4] - minSectionY);
        if (from > to) {
            return 0L;
        }
        return (to == 63 ? -1L : (1L << (to + 1)) - 1L) & (-1L << from);
    }

    // chunk sending

    /**
     * Creates the chunk packet for this player, with every section that can't be visible to them masked.
     */
    public ClientboundLevelChunkWithLightPacket createChunkPacket(final LevelChunk chunk, final boolean modifyBlocks) {
        final long masked = this.isActive(this.owner.config()) ? this.computeMask(chunk) : 0L;
        return new ClientboundLevelChunkWithLightPacket(chunk, chunk.getLevel().getLightEngine(), null, null, modifyBlocks, masked);
    }

    private long computeMask(final LevelChunk chunk) {
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        final int sections = chunk.getSectionsCount();
        final int minSectionY = chunk.getMinSectionY();

        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        long real = entry == null || entry.chunk != chunk ? 0L : entry.revealed;

        this.currentBox(this.maskBox);
        real |= boxMask(this.maskBox, chunkX, chunkZ, minSectionY, sections);
        if (this.boxLevel == chunk.getLevel()) {
            real |= boxMask(this.box, chunkX, chunkZ, minSectionY, sections);
        }

        real |= this.surfaceSections(chunk);

        final Pvs pvs = this.current;
        final int column = pvs == null || pvs.level != chunk.getLevel() ? -1 : pvs.column(chunkX, chunkZ);
        if (column >= 0) {
            real |= pvs.visible[column];
        } else {
            // fail closed, the PVS covering the chunk reveals what's visible right after
            this.chunksChanged = true;
        }

        final long all = sections == 64 ? -1L : (1L << sections) - 1L;
        return all & ~real;
    }

    /**
     * Sections at or above the surface of the chunk are never masked. They are open to the sky, so a player on the
     * surface gets them through the PVS anyway, while masking them for players underground would put filler into
     * the sky and the landscape they see when they come out of a cave. Only sections that lie below the highest
     * block of most columns can be masked.
     * <p>
     * A quantile of the column heights is used instead of the lowest column, so a single shaft or pit doesn't
     * unmask the rooms next to it.
     */
    private long surfaceSections(final LevelChunk chunk) {
        final Heightmap heightmap = chunk.heightmaps.get(Heightmap.Types.WORLD_SURFACE);
        if (heightmap == null) {
            return 0L;
        }
        final int[] heights = this.surfaceHeights;
        for (int z = 0; z < 16; ++z) {
            for (int x = 0; x < 16; ++x) {
                heights[z << 4 | x] = heightmap.getHighestTaken(x, z);
            }
        }
        Arrays.sort(heights);
        final int surface = heights[SURFACE_QUANTILE_COLUMN];
        // the section containing the surface block and every section above it
        final int from = Math.max(0, (surface >> 4) - chunk.getMinSectionY());
        final int sections = chunk.getSectionsCount();
        if (from >= sections) {
            return 0L;
        }
        final long all = sections == 64 ? -1L : (1L << sections) - 1L;
        return all & (-1L << from);
    }

    public void onChunkSent(final LevelChunk chunk, final ClientboundLevelChunkWithLightPacket packet) {
        final Pvs pvs = this.current;
        if (pvs == null || pvs.level != chunk.getLevel() || pvs.column(chunk.getPos().x(), chunk.getPos().z()) < 0) {
            // the PVS explores loaded chunks, so only chunks it didn't cover can change it
            this.chunksChanged = true;
        }
        final long masked = packet.chunkData().canvas$maskedSections;
        final long key = CoordinateUtils.getChunkKey(chunk.getPos().x(), chunk.getPos().z());
        SentChunk entry = this.sent.get(key);
        if (entry == null || entry.chunk != chunk) {
            if (entry != null) {
                this.remove(key);
            }
            if (masked == 0L) {
                return;
            }
            entry = new SentChunk(chunk);
            this.sent.put(key, entry);
        }
        entry.revealed |= ~masked;
        this.setMasked(entry, masked);
    }

    public void onChunkUnload(final int chunkX, final int chunkZ) {
        this.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    /**
     * Chunks are only sent once the PVS covers them, otherwise which of their sections may be sent real would have
     * to be guessed, and a wrong guess can't be taken back once the client has the data (joining, teleporting).
     * Requests a PVS covering the chunk if there is none yet.
     */
    public boolean mayChunkBeSent(final int chunkX, final int chunkZ) {
        final ServerPlayer player = this.owner.player;
        if (!this.isActive(this.owner.config())) {
            return true;
        }
        final Pvs pvs = this.current;
        if (pvs != null && pvs.level == player.level() && pvs.column(chunkX, chunkZ) >= 0) {
            return true;
        }
        this.chunksChanged = true;
        final long now = System.nanoTime();
        if (this.withheldSince == NOT_WITHHOLDING) {
            this.withheldSince = now;
            return false;
        }
        // no PVS arrived in time, send it fail closed (masked outside the start box) and reveal it later
        return now - this.withheldSince > MAX_WITHHOLD_NANOS;
    }

    private void setMasked(final SentChunk entry, final long masked) {
        if ((entry.masked != 0L) != (masked != 0L)) {
            this.maskedChunks += masked != 0L ? 1 : -1;
        }
        entry.masked = masked;
    }

    private void remove(final long key) {
        final SentChunk entry = this.sent.remove(key);
        if (entry != null) {
            this.setMasked(entry, 0L);
        }
    }

    /**
     * Called right before the client is told about a new position: reveals the destination synchronously, so the
     * client never stands inside filler. The PVS of the old position and a job computing one stay valid: they only
     * reveal what the player could see a moment ago, and chunks they don't cover are withheld or masked until the
     * PVS of the new position arrives (the start box changed, so it is computed right away). Throwing them away
     * instead would keep players that are teleported every tick (freeze plugins, movement corrections) from ever
     * getting a PVS, and with it any chunk.
     */
    public void onTeleport() {
        if (this.maskedChunks == 0) {
            return;
        }
        this.currentBox(this.teleportBox);
        this.revealBox(this.teleportBox);
    }

    /**
     * Called when a block of a chunk sent to the player changed between opaque and not opaque, which may change what
     * is potentially visible. May be called from any thread. Requests a check of the PVS within a few ticks instead
     * of at the next periodic update, the check itself is cheap when the change doesn't matter.
     */
    public void onOpacityChange() {
        if (!this.opacityChanged) {
            this.opacityChanged = true;
        }
    }

    // leak filters, callable from any thread

    /**
     * Whether the client currently has the given chunk section as filler. May be called from any thread, the answer
     * may then be slightly stale.
     *
     * @param sectionY the section coordinate, {@code blockY >> 4}
     */
    public boolean isSectionMasked(final int chunkX, final int sectionY, final int chunkZ) {
        if (this.maskedChunks == 0) {
            return false;
        }
        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        if (entry == null) {
            return false;
        }
        final int index = sectionY - entry.minSectionY;
        return index >= 0 && index < MAX_SECTIONS && (entry.masked >>> index & 1L) != 0L;
    }

    /**
     * Whether every section the entity's hitbox touches is filler on the client, in which case the entity can't be
     * visible either. Glowing entities are excluded, the client renders their outline through anything.
     */
    public boolean isEntityMasked(final Entity entity) {
        if (this.maskedChunks == 0 || entity.isCurrentlyGlowing()) {
            return false;
        }
        final AABB box = entity.getBoundingBox();
        final int minX = Mth.floor(box.minX) >> 4;
        final int minY = Mth.floor(box.minY) >> 4;
        final int minZ = Mth.floor(box.minZ) >> 4;
        final int maxX = Mth.floor(box.maxX) >> 4;
        final int maxY = Mth.floor(box.maxY) >> 4;
        final int maxZ = Mth.floor(box.maxZ) >> 4;
        for (int chunkX = minX; chunkX <= maxX; ++chunkX) {
            for (int chunkZ = minZ; chunkZ <= maxZ; ++chunkZ) {
                for (int sectionY = minY; sectionY <= maxY; ++sectionY) {
                    if (!this.isSectionMasked(chunkX, sectionY, chunkZ)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether the block lies in a section the client has as filler. May be called from any thread.
     */
    public boolean isMasked(final int x, final int y, final int z) {
        return this.isSectionMasked(x >> 4, y >> 4, z >> 4);
    }

    /**
     * Leaves the masked sections out of a light update. May be called from any thread.
     */
    public @Nullable ClientboundLightUpdatePacket filterLightUpdate(final ClientboundLightUpdatePacket packet) {
        if (this.maskedChunks == 0) {
            return packet;
        }
        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(packet.x(), packet.z()));
        final long masked = entry == null ? 0L : entry.masked;
        return masked == 0L ? packet : ChunkMasking.filterLight(packet, masked);
    }
}
