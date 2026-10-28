package io.canvasmc.canvas.anticheat.freecam;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.AnticheatExecutor;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.disguise.ChunkRewrite;
import io.canvasmc.canvas.anticheat.antixray.RayTraceAntiXray;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.VoxelRaycaster;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 * <b>Per-block mode</b> ({@code per-block}) goes further and masks single blocks: within the sections of the PVS,
 * only the blocks a straight line from the camera box could reach are sent real ({@link CellVisibility}), along with
 * everything open to the sky, which is public anyway. Every other block is filler and dark, so caves, rooms and ores
 * are hidden even right next to the player, unless they can be seen. The camera box is only entered from the eyes,
 * so the start box sections stop being sent real as a whole. Inside it, where the player may dig, a second layer of
 * blocks is visible: the client shows what is behind a block as soon as it breaks it, that must never be filler.
 * Blocks that become visible are revealed by re-sending their chunk; a block opening up inside the camera box
 * reveals what it uncovers there in the same tick, a few opaque blocks as block updates instead. Updates of blocks
 * outside the camera box that can't currently be seen are withheld, the block counting as filler until the chunk is
 * re-sent, so a player that saw a place once doesn't keep receiving what happens there.
 * <p>
 * Threading: everything runs on the player's region thread, except {@link #runAsync} (PVS computation on an
 * anticheat worker) and the leak filters {@link #isSectionMasked}, {@link #isMasked} and {@link #filterLightUpdate},
 * which read the sent chunks through a concurrent map and volatile state per chunk. Withholding updates and the
 * per-block entity check only happen on the player's thread, other threads only see what the client has.
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
    private static final int OPACITY_CHECK_INTERVAL = 5;

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
    /**
     * Set when a block the player may see opened up, requests a new PVS right away.
     */
    private volatile boolean urgentCheck;
    /**
     * Whether the state is kept per block, the {@code per-block} option it was built for.
     */
    private boolean perBlock;

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
    /**
     * The start box of the last snapshot in block coordinates, the camera box of per-block mode.
     */
    private final int[] blockBox = new int[6];
    /**
     * Per-block mode: the part of the camera box around the eyes the last job must still cover, otherwise a new one
     * is computed.
     */
    private final int[] requiredBox = new int[6];
    private @Nullable Level boxLevel;
    private final int[] lastJobBox = new int[6];
    private final int[] lastJobBlockBox = new int[6];
    /**
     * Per-block mode: what is visible around the eyes, computed on the spot when no PVS covers them (teleports, a
     * lagging worker pool, blocks opening up), until a PVS computed later is applied.
     */
    private @Nullable Overlay overlay;
    /**
     * Overlays computed this tick because blocks opened up, and whether one more is due at the next tick.
     */
    private int openings;
    private boolean openingsPending;
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
    private final int[] jobBlockBox = new int[6];

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
         * Per-block mode: per section index, the blocks the client has real of the sections it has partly as filler,
         * {@code null} for other sections, or {@code null} if there are none. Replaced as a whole, the arrays are
         * never modified. Read from any thread.
         */
        volatile long @Nullable [] @Nullable [] cells;
        /**
         * Sections the client got real since the chunk was sent, which are never masked again (section mode).
         */
        long revealed;

        SentChunk(final LevelChunk chunk) {
            this.chunk = chunk;
            this.minSectionY = chunk.getMinSectionY();
        }

        boolean hidesAny() {
            return this.masked != 0L || this.cells != null;
        }

        /**
         * @return the blocks of a section the client has real: {@code null} for none,
         * {@link CellVisibility#EVERY_BLOCK} for all
         */
        long @Nullable [] realBlocks(final int sectionIndex) {
            if ((this.masked >>> sectionIndex & 1L) != 0L) {
                return null;
            }
            final long[][] cells = this.cells;
            final long[] bits = cells == null || sectionIndex >= cells.length ? null : cells[sectionIndex];
            return bits == null ? CellVisibility.EVERY_BLOCK : bits;
        }

        /**
         * Whether the client has the block as filler. May be called from any thread.
         */
        boolean hidesBlock(final int x, final int y, final int z) {
            final int index = (y >> 4) - this.minSectionY;
            if (index < 0 || index >= MAX_SECTIONS) {
                return false;
            }
            if ((this.masked >>> index & 1L) != 0L) {
                return true;
            }
            final long[][] cells = this.cells;
            final long[] bits = cells == null || index >= cells.length ? null : cells[index];
            return bits != null && !isSet(bits, x, y, z);
        }
    }

    private static boolean isSet(final long[] bits, final int x, final int y, final int z) {
        final int index = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        return (bits[index >>> 6] >>> index & 1L) != 0L;
    }

    /**
     * Whether a section with the given visible blocks shows blocks a client with the given real blocks doesn't have,
     * see {@link SentChunk#realBlocks}.
     */
    private static boolean showsMore(final long @Nullable [] visible, final long @Nullable [] real) {
        if (visible == null || visible == real || real == CellVisibility.EVERY_BLOCK) {
            return false;
        }
        if (real == null || visible == CellVisibility.EVERY_BLOCK) {
            return true;
        }
        for (int w = 0; w < CellVisibility.WORDS; ++w) {
            if ((visible[w] & ~real[w]) != 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the blocks visible in either set, {@code null} standing for none and
     * {@link CellVisibility#EVERY_BLOCK} for all; one of the sets itself if it contains the other
     */
    private static long @Nullable [] union(final long @Nullable [] a, final long @Nullable [] b) {
        if (a == null || b == CellVisibility.EVERY_BLOCK || a == b) {
            return b;
        }
        if (b == null || a == CellVisibility.EVERY_BLOCK) {
            return a;
        }
        if (!showsMore(b, a)) {
            return a;
        }
        if (!showsMore(a, b)) {
            return b;
        }
        final long[] ret = new long[CellVisibility.WORDS];
        long all = -1L;
        for (int w = 0; w < CellVisibility.WORDS; ++w) {
            all &= ret[w] = a[w] | b[w];
        }
        return all == -1L ? CellVisibility.EVERY_BLOCK : ret;
    }

    /**
     * A potentially visible set, one bit per section for every column of a grid centered on the player.
     */
    private static final class Pvs {
        @Nullable Level level;
        long generation;
        boolean unchanged;
        /**
         * Whether this is a per-block result: {@link #visible} then holds the sections with at least one visible
         * block, and {@link #full} and {@link #cells} which of their blocks.
         */
        boolean perBlock;
        /**
         * The player's tick when the job was prepared.
         */
        int preparedTick;
        int originX;
        int originZ;
        int width;
        int depth;
        int height;
        int minSectionY;
        long[] visible = new long[0];
        /**
         * Per-block mode: per column, the sections whose every block is visible.
         */
        long[] full = new long[0];
        /**
         * Per column, the sections whose blocks the result was computed from, for the fingerprint.
         */
        long[] read = new long[0];
        /**
         * Per-block mode: per section of the grid ({@code column * height + index}), the visible blocks of sections
         * that are only partly visible. The arrays are never modified, results share them when they are equal.
         */
        long @Nullable [] @Nullable [] cells = new long[0][];
        /**
         * Per-block mode: the camera box in block coordinates.
         */
        final int[] blockBox = new int[6];
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
                this.full = new long[columns];
                this.read = new long[columns];
                this.covered = new boolean[columns];
            }
            if (this.perBlock) {
                final int nodes = columns * height;
                if (this.cells.length < nodes) {
                    this.cells = new long[nodes][];
                } else {
                    Arrays.fill(this.cells, 0, nodes, null);
                }
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

        /**
         * @return the visible blocks of a section of a covered column: {@code null} for none,
         * {@link CellVisibility#EVERY_BLOCK} for all
         */
        long @Nullable [] visibleBlocks(final int column, final int sectionIndex) {
            if ((this.visible[column] >>> sectionIndex & 1L) == 0L) {
                return null;
            }
            if (!this.perBlock || (this.full[column] >>> sectionIndex & 1L) != 0L) {
                return CellVisibility.EVERY_BLOCK;
            }
            final long[] ret = this.cells[column * this.height + sectionIndex];
            return ret == null ? CellVisibility.EVERY_BLOCK : ret;
        }

        /**
         * Per-block mode: whether the block may be visible. Blocks this result knows nothing about may be.
         */
        boolean mayShow(final int x, final int y, final int z) {
            final int column = this.column(x >> 4, z >> 4);
            final int index = (y >> 4) - this.minSectionY;
            if (column < 0 || index < 0 || index >= this.height) {
                return true;
            }
            final long[] visible = this.visibleBlocks(column, index);
            return visible != null && (visible == CellVisibility.EVERY_BLOCK || isSet(visible, x, y, z));
        }

        /**
         * @return the visible blocks of a section that are only partly visible, or {@code null}
         */
        long @Nullable [] partialBlocks(final Level level, final int chunkX, final int chunkZ, final int sectionIndex) {
            if (!this.perBlock || this.level != level) {
                return null;
            }
            final int column = this.column(chunkX, chunkZ);
            return column < 0 ? null : this.cells[column * this.height + sectionIndex];
        }
    }

    /**
     * Per-block mode: the blocks visible from the camera box right now, computed on the player's thread from the
     * eyes alone (see {@link #computeOverlay}).
     */
    private static final class Overlay {
        final Level level;
        final int tick;
        final int originX;
        final int originZ;
        final int width;
        final int depth;
        final int height;
        final int minSectionY;
        final int[] box;
        /**
         * Per section of the grid, like {@link Pvs#visibleBlocks}.
         */
        final long @Nullable [] @Nullable [] blocks;

        Overlay(final Level level, final int tick, final int originX, final int originZ, final int width, final int depth,
                final int height, final int minSectionY, final int[] box) {
            this.level = level;
            this.tick = tick;
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.depth = depth;
            this.height = height;
            this.minSectionY = minSectionY;
            this.box = box;
            this.blocks = new long[width * depth * height][];
        }

        long @Nullable [] visibleBlocks(final Level level, final int chunkX, final int chunkZ, final int sectionIndex) {
            final int x = chunkX - this.originX;
            final int z = chunkZ - this.originZ;
            if (level != this.level || x < 0 || x >= this.width || z < 0 || z >= this.depth || sectionIndex < 0 || sectionIndex >= this.height) {
                return null;
            }
            return this.blocks[(z * this.width + x) * this.height + sectionIndex];
        }

        boolean shows(final Level level, final int x, final int y, final int z) {
            final long[] visible = this.visibleBlocks(level, x >> 4, z >> 4, (y >> 4) - this.minSectionY);
            return visible != null && (visible == CellVisibility.EVERY_BLOCK || isSet(visible, x, y, z));
        }
    }

    /**
     * Working memory of a worker thread.
     */
    private static final class Worker implements OcclusionGraph.Sections, CellVisibility.Sections {
        private static final int UNRECORDED = Integer.MIN_VALUE;

        final OcclusionGraph graph = new OcclusionGraph();
        final CellVisibility cells = new CellVisibility();
        final SectionVisibility.Scratch scratch = new SectionVisibility.Scratch();
        final int[] cameras = new int[9];
        LevelChunkSection[] @Nullable [] columns = new LevelChunkSection[0][];
        int height;
        /**
         * Per column, the sections the occlusion graph may enter in per-block mode.
         */
        long[] domain = new long[0];
        /**
         * Per column, the sections whose visibility or opacity was read while recording.
         */
        long[] read = new long[0];
        /**
         * Per section of the grid: the opacity version its visibility set was computed for while recording.
         */
        int[] versions = new int[0];
        boolean recording;

        void load(final OcclusionAccess access, final Pvs pvs) {
            this.load(access, pvs.originX, pvs.originZ, pvs.width, pvs.depth, pvs.height);
        }

        void load(final OcclusionAccess access, final int originX, final int originZ, final int width, final int depth, final int height) {
            final int count = width * depth;
            if (this.columns.length < count) {
                this.columns = new LevelChunkSection[count][];
            }
            if (this.domain.length < count) {
                this.domain = new long[count];
                this.read = new long[count];
            }
            Arrays.fill(this.domain, 0, count, 0L);
            Arrays.fill(this.read, 0, count, 0L);
            this.height = height;
            for (int z = 0; z < depth; ++z) {
                for (int x = 0; x < width; ++x) {
                    final LevelChunkSection[] sections = access.sections(originX + x, originZ + z);
                    this.columns[z * width + x] = sections != null && sections.length == height ? sections : null;
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
            this.cells.release();
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
                for (long visible = pvs.read[column]; visible != 0L; visible &= visible - 1L) {
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
            return SectionVisibility.get(section, this.scratch, this.record(column, sectionIndex, section));
        }

        @Override
        public long[] opacity(final int column, final int sectionIndex) {
            final LevelChunkSection section = this.columns[column][sectionIndex];
            return SectionVisibility.opacity(section, this.scratch, this.record(column, sectionIndex, section));
        }

        /**
         * @return the current opacity version of the section, remembering the first one read while recording
         */
        private int record(final int column, final int sectionIndex, final LevelChunkSection section) {
            final int version = SectionVisibility.version(section);
            if (this.recording) {
                final int node = column * this.height + sectionIndex;
                if (this.versions[node] == UNRECORDED) {
                    this.versions[node] = version;
                }
                this.read[column] |= 1L << sectionIndex;
            }
            return version;
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
        this.overlay = null;
        this.urgentCheck = false;

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
            } else if (!active && entry.hidesAny()) {
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
            if (entry != null && entry.hidesAny()) {
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
        this.updateMode(config);
        this.cameraDistance = snapshot.cameraDistance;
        final double ahead = this.lookahead(snapshot);
        final double speed = Math.sqrt(snapshot.velocityX * snapshot.velocityX + snapshot.velocityY * snapshot.velocityY + snapshot.velocityZ * snapshot.velocityZ);
        final double scale = ahead == 0.0 ? 0.0 : ahead / speed;
        startBox(this.blockBox, this.box, snapshot.level, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ, this.margin(config, snapshot.cameraDistance),
            snapshot.velocityX * scale, snapshot.velocityY * scale, snapshot.velocityZ * scale);
        this.boxLevel = snapshot.level;

        if (this.perBlock) {
            startBox(this.requiredBox, null, snapshot.level, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ,
                cameraReach(snapshot.cameraDistance) + config.revealMargin * 0.5, 0.0, 0.0, 0.0);
            this.openings = 0;
            if (this.openingsPending) {
                this.openingsPending = false;
                this.overlay = this.computeOverlay(snapshot.level, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ, this.blockBox.clone());
                this.revealOverlay(this.overlay);
            }
            this.revealAround(snapshot.level, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ, snapshot.cameraDistance);
        } else {
            this.revealBox(this.box);
        }
        this.processRevealQueue(config.maxResendsPerTick);
    }

    /**
     * Switches between section and per-block mode when the configuration changed. What was computed for the other
     * mode is dropped, the client keeps what it has: in section mode, sections it has partly as filler count as
     * masked, so they are re-sent once visible.
     */
    private void updateMode(final WorldConfig.Anticheat.AntiFreecam config) {
        if (config.perBlock == this.perBlock) {
            return;
        }
        this.perBlock = config.perBlock;
        ++this.generation;
        this.current = null;
        this.spare = null;
        this.overlay = null;
        this.chunksChanged = true;
        this.jobScheduled = false;
        if (this.perBlock) {
            return;
        }
        for (final Iterator<SentChunk> iterator = this.sent.valueIterator(); iterator.hasNext(); ) {
            final SentChunk entry = iterator.next();
            final long[][] cells = entry.cells;
            if (cells == null) {
                continue;
            }
            long masked = entry.masked;
            for (int i = 0; i < cells.length; ++i) {
                if (cells[i] != null) {
                    masked |= 1L << i;
                }
            }
            this.setState(entry, masked, null);
        }
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
        this.updateMode(config);
        final ServerLevel level = snapshot.level;
        boolean boxChanged = !this.jobScheduled || !Arrays.equals(this.box, this.lastJobBox);
        if (this.perBlock && !boxChanged) {
            // the player moved half the margin away from where the last job was computed
            boxChanged = !contains(this.lastJobBlockBox, this.requiredBox);
        }
        final boolean urgent = this.urgentCheck;
        final int sinceLastJob = snapshot.tick - this.lastJobTick;
        final boolean periodic = sinceLastJob >= config.updateIntervalTicks || sinceLastJob < 0
            || (this.opacityChanged && sinceLastJob >= OPACITY_CHECK_INTERVAL);
        if (this.current != null && !boxChanged && !this.chunksChanged && !periodic && !urgent) {
            return false;
        }
        this.opacityChanged = false;
        this.urgentCheck = false;

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
        result.perBlock = this.perBlock;
        result.preparedTick = snapshot.tick;
        result.setGrid(centerX - radius, centerZ - radius, 2 * radius + 1, 2 * radius + 1, sections, level.getMinSectionY());

        this.jobCheckOnly = this.current != null && !boxChanged && !this.chunksChanged && !urgent;
        this.jobPrevious = this.current;
        this.jobResult = result;
        System.arraycopy(this.box, 0, this.jobBox, 0, 6);
        System.arraycopy(this.box, 0, this.lastJobBox, 0, 6);
        System.arraycopy(this.blockBox, 0, this.jobBlockBox, 0, 6);
        System.arraycopy(this.blockBox, 0, this.lastJobBlockBox, 0, 6);
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
            // per-block mode starts from the sections the client could render and narrows them down to blocks
            final long[] sections = result.perBlock ? worker.domain : result.visible;
            worker.graph.compute(
                worker, result.width, result.depth, result.height,
                box[0] - result.originX, box[1] - result.minSectionY, box[2] - result.originZ,
                box[3] - result.originX, box[4] - result.minSectionY, box[5] - result.originZ,
                // a camera outside the world makes the client start from the whole top or bottom layer
                box[4] > maxSectionY, box[1] < result.minSectionY,
                sections
            );
            final int columns = result.width * result.depth;
            for (int column = 0; column < columns; ++column) {
                result.covered[column] = worker.columns[column] != null;
            }
            if (result.perBlock) {
                this.computeBlocks(snapshot, access, worker, result);
            }
            for (int column = 0; column < columns; ++column) {
                result.read[column] = sections[column] | worker.read[column];
            }
            result.fingerprint = worker.fingerprint(result);
        } finally {
            worker.release();
        }
    }

    /**
     * Narrows the sections the client could render down to the blocks visible from the camera box. Sections that
     * are only partly visible get the same array as in the previous result if their blocks didn't change, so
     * comparing them later is cheap and chunks sent with them share it.
     */
    private void computeBlocks(final CameraSnapshot snapshot, final OcclusionAccess access, final Worker worker, final Pvs result) {
        final double[] cameras = snapshot.cameras(access);
        for (int i = 0; i < 9; ++i) {
            worker.cameras[i] = Mth.floor(cameras[i]);
        }
        System.arraycopy(this.jobBlockBox, 0, result.blockBox, 0, 6);
        final CellVisibility cells = worker.cells;
        // the player may dig anywhere in the box, two layers of blocks are visible there
        cells.compute(worker, result.width, result.depth, result.height, result.originX, result.originZ, result.minSectionY,
            worker.domain, true, worker.cameras, 3, result.blockBox, result.blockBox);

        final Pvs previous = this.jobPrevious;
        final Level level = result.level;
        for (int column = 0, columns = result.width * result.depth; column < columns; ++column) {
            long visible = 0L;
            long full = 0L;
            if (result.covered[column]) {
                final int chunkX = result.originX + column % result.width;
                final int chunkZ = result.originZ + column / result.width;
                for (int index = 0; index < result.height; ++index) {
                    final byte kind = cells.kind(column, index);
                    if (kind == CellVisibility.NOTHING) {
                        continue;
                    }
                    visible |= 1L << index;
                    if (kind == CellVisibility.EVERYTHING) {
                        full |= 1L << index;
                        continue;
                    }
                    final long[] old = previous == null || level == null ? null : previous.partialBlocks(level, chunkX, chunkZ, index);
                    result.cells[column * result.height + index] = old != null && cells.visibleEquals(column, index, old)
                        ? old : cells.copyVisible(column, index);
                }
            }
            result.visible[column] = visible;
            result.full[column] = full;
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
        final Overlay overlay = this.overlay;
        if (overlay != null && result.perBlock && result.preparedTick > overlay.tick) {
            this.overlay = null;
        }
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
            if (!entry.hidesAny()) {
                continue;
            }
            final int chunkX = entry.chunk.getPos().x();
            final int chunkZ = entry.chunk.getPos().z();
            if (!reveals(pvs, entry)) {
                continue;
            }
            this.enqueueReveal(chunkX, chunkZ, centerX, centerZ);
        }
        Arrays.sort(this.revealQueue, 0, this.revealQueueSize);
    }

    /**
     * Whether the PVS makes blocks visible that the client has as filler in the chunk.
     */
    private static boolean reveals(final Pvs pvs, final SentChunk entry) {
        final int column = entry.chunk.getLevel() == pvs.level ? pvs.column(entry.chunk.getPos().x(), entry.chunk.getPos().z()) : -1;
        if (column < 0) {
            return false;
        }
        if (!pvs.perBlock) {
            return (entry.masked & pvs.visible[column]) != 0L;
        }
        if (entry.cells == null && (entry.masked & pvs.visible[column]) == 0L) {
            return false;
        }
        for (int index = 0; index < pvs.height; ++index) {
            if (showsMore(pvs.visibleBlocks(column, index), entry.realBlocks(index))) {
                return true;
            }
        }
        return false;
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
            if (reveals(pvs, entry)) {
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

    private double margin(final WorldConfig.Anticheat.AntiFreecam config, final double cameraDistance) {
        if (this.perBlock) {
            // the visible blocks are only recomputed once the player moved half the margin, the cameras must stay in
            // the box until the result arrives
            return config.revealMargin + cameraReach(cameraDistance);
        }
        // third person cameras are pulled back up to the camera distance
        return Math.max(config.revealMargin, cameraReach(cameraDistance));
    }

    /**
     * How far from the eyes a camera can be.
     */
    private static double cameraReach(final double cameraDistance) {
        return cameraDistance + 1.0;
    }

    /**
     * Computes the blocks, and the sections, a camera within {@code margin} blocks of the eyes, or along the given
     * movement, could be in. The result may extend outside the world.
     *
     * @param blocks   receives the box in block coordinates: min x, y, z, max x, y, z
     * @param sections if not {@code null}, receives the box in section coordinates
     */
    private static void startBox(final int[] blocks, final int @Nullable [] sections, final Level level, final double eyeX, final double eyeY, final double eyeZ,
                                 final double margin, final double aheadX, final double aheadY, final double aheadZ) {
        blocks[0] = Mth.floor(eyeX - margin + Math.min(0.0, aheadX));
        blocks[1] = Mth.floor(eyeY - margin + Math.min(0.0, aheadY));
        blocks[2] = Mth.floor(eyeZ - margin + Math.min(0.0, aheadZ));
        blocks[3] = Mth.floor(eyeX + margin + Math.max(0.0, aheadX));
        blocks[4] = Mth.floor(eyeY + margin + Math.max(0.0, aheadY));
        blocks[5] = Mth.floor(eyeZ + margin + Math.max(0.0, aheadZ));
        // keep the box finite for players far outside the world
        blocks[1] = Mth.clamp(blocks[1], level.getMinY() - 16, level.getMaxY() + 16);
        blocks[4] = Mth.clamp(blocks[4], level.getMinY() - 16, level.getMaxY() + 16);
        if (sections != null) {
            for (int i = 0; i < 6; ++i) {
                sections[i] = blocks[i] >> 4;
            }
        }
    }

    private static boolean contains(final int[] outer, final int[] inner) {
        return outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] <= inner[2]
            && outer[3] >= inner[3] && outer[4] >= inner[4] && outer[5] >= inner[5];
    }

    private static boolean contains(final int[] box, final int x, final int y, final int z) {
        return box[0] <= x && box[1] <= y && box[2] <= z && box[3] >= x && box[4] >= y && box[5] >= z;
    }

    private double currentCameraDistance() {
        // before the first snapshot only the attribute is known, the snapshot also accounts for scale and vehicles
        return Double.isNaN(this.cameraDistance) ? this.owner.player.getAttributeValue(Attributes.CAMERA_DISTANCE) : this.cameraDistance;
    }

    private void currentBox(final int @Nullable [] blocks, final int @Nullable [] sections) {
        final ServerPlayer player = this.owner.player;
        startBox(blocks == null ? new int[6] : blocks, sections, player.level(), player.getX(), player.getEyeY(), player.getZ(),
            this.margin(this.owner.config().antiFreecam, this.currentCameraDistance()), 0.0, 0.0, 0.0);
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
     * Creates the chunk packet for this player, with every section, or in per-block mode every block, that can't be
     * visible to them masked.
     */
    /**
     * @param disguises the blocks sent as something else, see {@link io.canvasmc.canvas.anticheat.disguise.BlockDisguises#prepareChunk}
     */
    public ClientboundLevelChunkWithLightPacket createChunkPacket(final LevelChunk chunk, final boolean modifyBlocks, final @Nullable ChunkRewrite disguises) {
        final WorldConfig.Anticheat config = this.owner.config();
        if (!this.isActive(config)) {
            return new ClientboundLevelChunkWithLightPacket(chunk, chunk.getLevel().getLightEngine(), null, null, modifyBlocks, 0L, null, 0L, disguises);
        }
        this.updateMode(config.antiFreecam);
        if (this.perBlock) {
            return this.createBlockMaskedPacket(chunk, modifyBlocks, disguises);
        }
        final long masked = this.computeMask(chunk);
        return new ClientboundLevelChunkWithLightPacket(chunk, chunk.getLevel().getLightEngine(), null, null, modifyBlocks, masked, null,
            masked & ~this.nearVisible(chunk, ~masked), disguises);
    }

    private ClientboundLevelChunkWithLightPacket createBlockMaskedPacket(final LevelChunk chunk, final boolean modifyBlocks, final @Nullable ChunkRewrite disguises) {
        final Level level = chunk.getLevel();
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        final int sections = chunk.getSectionsCount();
        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        final SentChunk client = entry != null && entry.chunk == chunk ? entry : null;
        final Pvs pvs = this.current;
        final int column = pvs == null || !pvs.perBlock || pvs.level != level ? -1 : pvs.column(chunkX, chunkZ);
        long @Nullable [] @Nullable [] fallback = null;
        if (column < 0) {
            // fail closed: only what is open to the sky, the PVS covering the chunk reveals the rest right after
            this.chunksChanged = true;
            fallback = this.publicBlocks(chunk);
        }
        final Overlay overlay = this.overlay;

        long masked = 0L;
        long @Nullable [] @Nullable [] cells = null;
        for (int index = 0; index < sections; ++index) {
            long[] visible = column >= 0 ? pvs.visibleBlocks(column, index) : fallback[index];
            if (overlay != null) {
                visible = union(visible, overlay.visibleBlocks(level, chunkX, chunkZ, index));
            }
            if (client != null) {
                // what the client got real stays real, re-sending a chunk never takes anything away
                visible = union(visible, client.realBlocks(index));
            }
            if (visible == null) {
                masked |= 1L << index;
            } else if (visible != CellVisibility.EVERY_BLOCK) {
                if (cells == null) {
                    cells = new long[sections][];
                }
                cells[index] = visible;
            }
        }
        final long all = sections == 64 ? -1L : (1L << sections) - 1L;
        return new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, modifyBlocks, masked, cells,
            masked & ~this.nearVisible(chunk, all & ~masked), disguises);
    }

    /**
     * The sections of a chunk next to (or in) a section that may be visible, given the sections of the chunk itself
     * that are sent with real blocks. Their biomes stay real even if they are masked: the client spreads biomes over
     * a few blocks (biome lookup jitter, color blending), so a visible block can show the biome of the neighboring
     * section. Sections of columns without a PVS count as visible.
     */
    private long nearVisible(final LevelChunk chunk, final long real) {
        long near = real;
        final Pvs pvs = this.current;
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                if ((dx | dz) == 0) {
                    continue;
                }
                final int column = pvs == null || pvs.level != chunk.getLevel() ? -1 : pvs.column(chunkX + dx, chunkZ + dz);
                near |= column < 0 ? -1L : pvs.visible[column];
            }
        }
        return near | near << 1 | near >>> 1;
    }

    /**
     * The blocks of a chunk that are open to the sky, or next to such a block, for chunks no PVS covers yet.
     */
    private long @Nullable [] @Nullable [] publicBlocks(final LevelChunk chunk) {
        final ServerLevel level = (ServerLevel) chunk.getLevel();
        final int sections = chunk.getSectionsCount();
        final long[][] ret = new long[sections][];
        final Worker worker = WORKERS.get();
        try {
            // the neighbors too, for the faces of cliffs on the chunk's edges
            worker.load(this.access(level), chunk.getPos().x() - 1, chunk.getPos().z() - 1, 3, 3, sections);
            if (!worker.hasColumn(4)) {
                return ret;
            }
            worker.cells.compute(worker, 3, 3, sections, chunk.getPos().x() - 1, chunk.getPos().z() - 1, chunk.getMinSectionY(),
                worker.domain, true, worker.cameras, 0, this.blockBox, null);
            for (int index = 0; index < sections; ++index) {
                ret[index] = blocksOf(worker.cells, 4, index);
            }
            return ret;
        } finally {
            worker.release();
        }
    }

    /**
     * @return the visible blocks of a section of a computed {@link CellVisibility}, like {@link Pvs#visibleBlocks}
     */
    private static long @Nullable [] blocksOf(final CellVisibility cells, final int column, final int index) {
        return switch (cells.kind(column, index)) {
            case CellVisibility.NOTHING -> null;
            case CellVisibility.EVERYTHING -> CellVisibility.EVERY_BLOCK;
            default -> cells.copyVisible(column, index);
        };
    }

    private OcclusionAccess access(final ServerLevel level) {
        OcclusionAccess access = this.tickAccess;
        if (access == null || access.level() != level) {
            this.tickAccess = access = new OcclusionAccess(level);
        }
        return access;
    }

    private long computeMask(final LevelChunk chunk) {
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        final int sections = chunk.getSectionsCount();
        final int minSectionY = chunk.getMinSectionY();

        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        long real = entry == null || entry.chunk != chunk ? 0L : entry.revealed;

        this.currentBox(null, this.maskBox);
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
        final long[][] cells = packet.chunkData().canvas$visibleCells;
        final long key = CoordinateUtils.getChunkKey(chunk.getPos().x(), chunk.getPos().z());
        SentChunk entry = this.sent.get(key);
        if (entry == null || entry.chunk != chunk) {
            if (entry != null) {
                this.remove(key);
            }
            // per-block mode tracks every chunk, updates of any of them may be withheld
            if (masked == 0L && cells == null && !(this.perBlock && this.isActive(this.owner.config()))) {
                return;
            }
            entry = new SentChunk(chunk);
            this.sent.put(key, entry);
        }
        entry.revealed |= ~masked;
        this.setState(entry, masked, cells);
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

    /**
     * Updates what the client has as filler. Readers on other threads may see the fields change one after the
     * other, so whatever is masked in either state is masked first: they never take a block for real that is filler
     * in the old or the new state.
     */
    private void setState(final SentChunk entry, final long masked, final long @Nullable [] @Nullable [] cells) {
        final boolean hid = entry.hidesAny();
        entry.masked |= masked;
        entry.cells = cells;
        entry.masked = masked;
        final boolean hides = entry.hidesAny();
        if (hid != hides) {
            this.maskedChunks += hides ? 1 : -1;
        }
    }

    /**
     * Per-block mode: records that the client has the block as filler, or real, from now on.
     */
    private void setBlock(final SentChunk entry, final int x, final int y, final int z, final boolean real) {
        final int index = (y >> 4) - entry.minSectionY;
        final int sections = entry.chunk.getSectionsCount();
        if (index < 0 || index >= sections) {
            return;
        }
        final long[] bits = entry.realBlocks(index);
        final boolean wasReal = bits != null && (bits == CellVisibility.EVERY_BLOCK || isSet(bits, x, y, z));
        if (wasReal == real) {
            return;
        }
        final long[] copy = bits == null ? new long[CellVisibility.WORDS] : bits.clone();
        final int bit = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        if (real) {
            copy[bit >>> 6] |= 1L << bit;
        } else {
            copy[bit >>> 6] &= ~(1L << bit);
        }
        long all = -1L;
        for (final long word : copy) {
            all &= word;
        }
        final long[][] old = entry.cells;
        final long[][] cells = old == null ? new long[sections][] : old.clone();
        cells[index] = all == -1L ? null : copy;
        boolean any = false;
        for (final long[] section : cells) {
            any |= section != null;
        }
        this.setState(entry, entry.masked & ~(1L << index), any ? cells : null);
    }

    private void remove(final long key) {
        final SentChunk entry = this.sent.remove(key);
        if (entry != null) {
            this.setState(entry, 0L, null);
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
        if (this.perBlock && this.isActive(this.owner.config())) {
            // only what can be seen from the destination, not everything around it: ender pearls must not probe
            final ServerPlayer player = this.owner.player;
            final int[] box = new int[6];
            this.currentBox(box, null);
            this.overlay = this.computeOverlay(player.level(), player.getX(), player.getEyeY(), player.getZ(), box);
            this.revealOverlay(this.overlay);
            return;
        }
        if (this.maskedChunks == 0) {
            return;
        }
        this.currentBox(null, this.teleportBox);
        this.revealBox(this.teleportBox);
    }

    /**
     * Per-block mode: makes sure the blocks the cameras can see right now are real, even if no PVS covering them
     * arrived yet (teleports, joining, a lagging worker pool), from a flood around the eyes computed right here.
     */
    private void revealAround(final ServerLevel level, final double eyeX, final double eyeY, final double eyeZ, final double cameraDistance) {
        final int reach = Mth.ceil(cameraReach(cameraDistance));
        final int x = Mth.floor(eyeX);
        final int y = Mth.floor(eyeY);
        final int z = Mth.floor(eyeZ);
        final int[] core = {x - reach, y - reach, z - reach, x + reach, y + reach, z + reach};
        final Pvs pvs = this.current;
        if (pvs != null && pvs.perBlock && pvs.level == level && contains(pvs.blockBox, core)) {
            return;
        }
        final Overlay overlay = this.overlay;
        if (overlay != null && overlay.level == level && contains(overlay.box, core)) {
            return;
        }
        this.overlay = this.computeOverlay(level, eyeX, eyeY, eyeZ, this.blockBox.clone());
        this.revealOverlay(this.overlay);
    }

    /**
     * Computes the blocks visible from the given box, flooding from the eyes through the sections it overlaps. The
     * result is a subset of what a PVS for the same box contains (no sky, fewer sections), cheap enough to compute
     * on the player's thread.
     */
    private Overlay computeOverlay(final ServerLevel level, final double eyeX, final double eyeY, final double eyeZ, final int[] box) {
        final int originX = box[0] >> 4;
        final int originZ = box[2] >> 4;
        final int width = (box[3] >> 4) - originX + 1;
        final int depth = (box[5] >> 4) - originZ + 1;
        final int height = level.getSectionsCount();
        final int minSectionY = level.getMinSectionY();
        final Overlay ret = new Overlay(level, this.owner.player.tickCount, originX, originZ, width, depth, height, minSectionY, box);
        final int from = Math.max(0, (box[1] >> 4) - minSectionY);
        final int to = Math.min(height - 1, (box[4] >> 4) - minSectionY);
        if (from > to) {
            return ret;
        }
        final Worker worker = WORKERS.get();
        try {
            worker.load(this.access(level), originX, originZ, width, depth, height);
            final long sections = (to == 63 ? -1L : (1L << (to + 1)) - 1L) & (-1L << from);
            for (int column = 0; column < width * depth; ++column) {
                worker.domain[column] = sections;
            }
            worker.cameras[0] = Mth.floor(eyeX);
            worker.cameras[1] = Mth.floor(eyeY);
            worker.cameras[2] = Mth.floor(eyeZ);
            worker.cells.compute(worker, width, depth, height, originX, originZ, minSectionY, worker.domain, false, worker.cameras, 1, box, box);
            // visible blocks can lie one section beyond the box
            for (int column = 0; column < width * depth; ++column) {
                if (!worker.hasColumn(column)) {
                    continue;
                }
                for (int index = Math.max(0, from - 1); index <= Math.min(height - 1, to + 1); ++index) {
                    ret.blocks[column * height + index] = blocksOf(worker.cells, column, index);
                }
            }
            return ret;
        } finally {
            worker.release();
        }
    }

    /**
     * Immediately gives the client every block the overlay shows that it has as filler.
     */
    private void revealOverlay(final Overlay overlay) {
        if (this.maskedChunks == 0) {
            return;
        }
        for (int z = 0; z < overlay.depth; ++z) {
            for (int x = 0; x < overlay.width; ++x) {
                final int chunkX = overlay.originX + x;
                final int chunkZ = overlay.originZ + z;
                final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
                if (entry != null && entry.chunk.getLevel() == overlay.level && entry.hidesAny()) {
                    this.reveal(entry, overlay, chunkX, chunkZ);
                }
            }
        }
    }

    /**
     * Most overlays computed per tick for blocks opening up, see {@link #onOpacityChange}.
     */
    private static final int MAX_OPENINGS_PER_TICK = 4;

    /**
     * Most blocks to send as block updates instead of re-sending their chunk.
     */
    private static final int MAX_REVEALED_BLOCKS = 128;

    /**
     * Gives the client the blocks of a chunk the overlay shows that it has as filler. A few opaque blocks, like
     * those uncovered by digging, are sent as block updates: digging must not re-send a chunk for every block, and
     * the client must get them before it digs on. Anything else re-sends the chunk: see-through blocks need their
     * light, and blocks anti-xray may disguise only get disguised in chunk packets.
     */
    private void reveal(final SentChunk entry, final Overlay overlay, final int chunkX, final int chunkZ) {
        final LevelChunk chunk = entry.chunk;
        final Level level = chunk.getLevel();
        final ServerPlayer player = this.owner.player;
        final boolean disguised = !this.owner.bypassesAntiXray() && level.chunkPacketBlockController.shouldModify(player, chunk);
        final int sections = chunk.getSectionsCount();
        long[][] revealed = null;
        int count = 0;
        for (int index = 0; index < sections && index < overlay.height; ++index) {
            final long[] visible = overlay.visibleBlocks(level, chunkX, chunkZ, index);
            final long[] real = entry.realBlocks(index);
            if (!showsMore(visible, real)) {
                continue;
            }
            if (real == null || visible == CellVisibility.EVERY_BLOCK) {
                this.resend(entry);
                return;
            }
            final LevelChunkSection section = chunk.getSections()[index];
            final long[] add = new long[CellVisibility.WORDS];
            for (int w = 0; w < CellVisibility.WORDS; ++w) {
                add[w] = visible[w] & ~real[w];
                count += Long.bitCount(add[w]);
                if (count > MAX_REVEALED_BLOCKS) {
                    this.resend(entry);
                    return;
                }
                for (long bits = add[w]; bits != 0L; bits &= bits - 1L) {
                    final int bit = w << 6 | Long.numberOfTrailingZeros(bits);
                    final BlockState state = section.getBlockState(bit & 15, bit >>> 8, (bit >>> 4) & 15);
                    if (!state.isSolidRender() || (disguised && level.chunkPacketBlockController.canvas$mayObfuscate(level, state))) {
                        this.resend(entry);
                        return;
                    }
                }
            }
            if (revealed == null) {
                revealed = new long[sections][];
            }
            revealed[index] = add;
        }
        if (revealed == null) {
            return;
        }

        // the client counts them as real first, so filtering the updates below doesn't withhold them
        final long[][] old = entry.cells;
        final long[][] cells = old == null ? new long[sections][] : old.clone();
        for (int index = 0; index < sections; ++index) {
            final long[] add = revealed[index];
            if (add == null) {
                continue;
            }
            final long[] merged = entry.realBlocks(index).clone();
            long all = -1L;
            for (int w = 0; w < CellVisibility.WORDS; ++w) {
                all &= merged[w] |= add[w];
            }
            cells[index] = all == -1L ? null : merged;
        }
        boolean any = false;
        for (final long[] section : cells) {
            any |= section != null;
        }
        this.setState(entry, entry.masked, any ? cells : null);

        for (int index = 0; index < sections; ++index) {
            final long[] add = revealed[index];
            if (add == null) {
                continue;
            }
            final SectionPos sectionPos = SectionPos.of(chunkX, chunk.getMinSectionY() + index, chunkZ);
            final ShortArraySet positions = new ShortArraySet();
            for (int w = 0; w < CellVisibility.WORDS; ++w) {
                for (long bits = add[w]; bits != 0L; bits &= bits - 1L) {
                    final int bit = w << 6 | Long.numberOfTrailingZeros(bits);
                    // section relative position: x << 8 | z << 4 | y
                    positions.add((short) ((bit & 15) << 8 | ((bit >>> 4) & 15) << 4 | bit >>> 8));
                }
            }
            final LevelChunkSection section = chunk.getSections()[index];
            final ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(sectionPos, positions, section)
                .canvas$filter(this.owner);
            // storage hiding withholds hidden storage, whose filler the client must get instead of this filler
            for (final ShortIterator iterator = positions.iterator(); iterator.hasNext(); ) {
                final short packed = iterator.nextShort();
                final int x = sectionPos.relativeToBlockX(packed);
                final int y = sectionPos.relativeToBlockY(packed);
                final int z = sectionPos.relativeToBlockZ(packed);
                if (this.owner.storage.isHiddenAt(x, y, z)) {
                    final BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                    player.connection.send(new ClientboundBlockUpdatePacket(new BlockPos(x, y, z), this.owner.hiddenStateOf((ServerLevel) level, x, y, z, state)));
                }
            }
            if (packet == null) {
                continue;
            }
            player.connection.send(packet);
            packet.runUpdates((pos, state) -> {
                if (state.hasBlockEntity()) {
                    final BlockEntity blockEntity = level.getBlockEntity(pos);
                    final Packet<?> update = blockEntity == null ? null : blockEntity.getUpdatePacket();
                    if (update != null) {
                        player.connection.send(update);
                    }
                }
            });
        }
    }

    /**
     * Called when a block of a chunk sent to the player changed between opaque and not opaque, which may change what
     * is potentially visible. May be called from any thread. Requests a check of the PVS within a few ticks instead
     * of at the next periodic update, the check itself is cheap when the change doesn't matter.
     * <p>
     * Per-block mode reacts right away to a block the player may see becoming see-through: a new PVS is computed
     * at once, and inside the camera box, where the player may be digging, what it uncovers within the box is
     * revealed on the spot.
     */
    public void onOpacityChange(final ServerLevel level, final BlockPos pos, final boolean opaque) {
        if (!this.perBlock) {
            if (!this.isSectionMasked(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)) {
                // no view leads into masked sections
                this.opacityChanged = true;
            }
            return;
        }
        this.opacityChanged = true;
        final ServerPlayer player = this.owner.player;
        if (opaque || player.level() != level || !TickThread.isTickThreadFor(player)) {
            return;
        }
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();
        final Pvs pvs = this.current;
        final Overlay overlay = this.overlay;
        if (!(pvs != null && pvs.perBlock && pvs.level == level && pvs.mayShow(x, y, z)) && !(overlay != null && overlay.shows(level, x, y, z))) {
            return;
        }
        this.urgentCheck = true;
        final int[] box = this.blockBox;
        if (this.boxLevel == level && contains(box, x, y, z)) {
            if (this.openings >= MAX_OPENINGS_PER_TICK) {
                // many blocks at once (explosions): the rest is handled together at the next tick
                this.openingsPending = true;
                return;
            }
            ++this.openings;
            // the player may be digging: what the change uncovers is shown right away, before they dig on
            this.overlay = this.computeOverlay(level, player.getX(), player.getEyeY(), player.getZ(), box.clone());
            this.revealOverlay(this.overlay);
        }
    }

    // leak filters, callable from any thread

    /**
     * Whether the client has any block in the given box (block coordinates, inclusive) as filler that matches the
     * filter. Same thread rules as {@link #isMasked}.
     */
    public boolean anyHidden(final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ,
                             final RayTraceAntiXray.PositionFilter filter) {
        if (this.maskedChunks == 0) {
            return false;
        }
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; ++chunkX) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; ++chunkZ) {
                final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
                if (entry == null || !entry.hidesAny()) {
                    continue;
                }
                for (int sectionY = minY >> 4; sectionY <= maxY >> 4; ++sectionY) {
                    final int index = sectionY - entry.minSectionY;
                    if (index < 0 || index >= MAX_SECTIONS) {
                        continue;
                    }
                    final long[] real = entry.realBlocks(index);
                    if (real == CellVisibility.EVERY_BLOCK) {
                        continue;
                    }
                    for (int y = Math.max(minY, sectionY << 4); y <= Math.min(maxY, (sectionY << 4) + 15); ++y) {
                        for (int z = Math.max(minZ, chunkZ << 4); z <= Math.min(maxZ, (chunkZ << 4) + 15); ++z) {
                            for (int x = Math.max(minX, chunkX << 4); x <= Math.min(maxX, (chunkX << 4) + 15); ++x) {
                                if ((real == null || !isSet(real, x, y, z)) && filter.test(x, y, z)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

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
     * Cheap pre-check for {@link #withholdsUpdate}: {@code false} if no block of the section is filler for the
     * client and none of its updates would be withheld.
     */
    public boolean mayHideIn(final int chunkX, final int sectionY, final int chunkZ) {
        if (this.maskedChunks != 0) {
            final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
            final int index = entry == null ? -1 : sectionY - entry.minSectionY;
            if (index >= 0 && index < MAX_SECTIONS && entry.realBlocks(index) != CellVisibility.EVERY_BLOCK) {
                return true;
            }
        }
        if (!this.perBlock) {
            return false;
        }
        final Pvs pvs = this.current;
        if (pvs == null || !pvs.perBlock) {
            return false;
        }
        final int column = pvs.column(chunkX, chunkZ);
        final int index = sectionY - pvs.minSectionY;
        return column >= 0 && index >= 0 && index < pvs.height && pvs.visibleBlocks(column, index) != CellVisibility.EVERY_BLOCK;
    }

    /**
     * Whether the client has the entity's surroundings as filler, or in per-block mode, on the player's thread,
     * whether none of the blocks around the entity may be visible. Either way the entity can't be visible. Glowing
     * entities are excluded, the client renders their outline through anything.
     */
    public boolean isEntityMasked(final Entity entity) {
        if (entity.isCurrentlyGlowing()
            || entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss || entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
            // bosses show a boss bar while tracked, it would flicker
            return false;
        }
        final AABB box = entity.getBoundingBox();
        if (this.perBlock && entity.level() == this.owner.player.level() && TickThread.isTickThreadFor(this.owner.player)) {
            final Pvs pvs = this.current;
            if (pvs != null && pvs.perBlock && pvs.level == entity.level()) {
                return !this.mayShowAround(pvs, entity.level(), box);
            }
        }
        if (this.maskedChunks == 0) {
            return false;
        }
        if (this.perBlock) {
            return this.hidesAround(box);
        }
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
     * Blocks around an entity whose visibility matters: those its hitbox touches, plus half a block for models
     * reaching out of it. Huge entities are never hidden.
     */
    private static final double ENTITY_MARGIN = 0.5;
    private static final int MAX_ENTITY_BLOCKS = 4096;

    private boolean mayShowAround(final Pvs pvs, final Level level, final AABB box) {
        final Overlay overlay = this.overlay;
        // most entities stand in plain sight, check their center first
        final int centerX = Mth.floor((box.minX + box.maxX) * 0.5);
        final int centerY = Mth.floor((box.minY + box.maxY) * 0.5);
        final int centerZ = Mth.floor((box.minZ + box.maxZ) * 0.5);
        if (pvs.mayShow(centerX, centerY, centerZ) || (overlay != null && overlay.shows(level, centerX, centerY, centerZ))) {
            return true;
        }
        final int minX = Mth.floor(box.minX - ENTITY_MARGIN);
        final int minY = Mth.floor(box.minY - ENTITY_MARGIN);
        final int minZ = Mth.floor(box.minZ - ENTITY_MARGIN);
        final int maxX = Mth.floor(box.maxX + ENTITY_MARGIN);
        final int maxY = Mth.floor(box.maxY + ENTITY_MARGIN);
        final int maxZ = Mth.floor(box.maxZ + ENTITY_MARGIN);
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > MAX_ENTITY_BLOCKS) {
            return true;
        }
        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                for (int x = minX; x <= maxX; ++x) {
                    if (pvs.mayShow(x, y, z) || (overlay != null && overlay.shows(level, x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hidesAround(final AABB box) {
        final int minX = Mth.floor(box.minX - ENTITY_MARGIN);
        final int minY = Mth.floor(box.minY - ENTITY_MARGIN);
        final int minZ = Mth.floor(box.minZ - ENTITY_MARGIN);
        final int maxX = Mth.floor(box.maxX + ENTITY_MARGIN);
        final int maxY = Mth.floor(box.maxY + ENTITY_MARGIN);
        final int maxZ = Mth.floor(box.maxZ + ENTITY_MARGIN);
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > MAX_ENTITY_BLOCKS) {
            return false;
        }
        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                for (int x = minX; x <= maxX; ++x) {
                    if (!this.isMasked(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether the client has the block as filler. May be called from any thread.
     */
    public boolean isMasked(final int x, final int y, final int z) {
        if (this.maskedChunks == 0) {
            return false;
        }
        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        return entry != null && entry.hidesBlock(x, y, z);
    }

    /**
     * Whether an update of the block (its state, block entity, events) must not be sent to the player: the client
     * has it as filler, or, in per-block mode, it can't currently be seen. Such blocks count as filler from then on,
     * they are re-sent with their chunk once they may be visible again. Blocks in the camera box are never withheld,
     * the player may be interacting with them. Withholding only happens on the player's thread, other threads only
     * filter what the client has as filler.
     */
    public boolean withholdsUpdate(final int x, final int y, final int z) {
        if (this.isMasked(x, y, z)) {
            return true;
        }
        if (!this.perBlock) {
            return false;
        }
        final ServerPlayer player = this.owner.player;
        final Pvs pvs = this.current;
        final ServerLevel level = player.level();
        if (pvs == null || !pvs.perBlock || pvs.level != level || this.boxLevel != level || contains(this.blockBox, x, y, z)
            || !TickThread.isTickThreadFor(player) || pvs.mayShow(x, y, z)) {
            return false;
        }
        final Overlay overlay = this.overlay;
        if (overlay != null && overlay.shows(level, x, y, z)) {
            return false;
        }
        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        if (entry == null || entry.chunk.getLevel() != level) {
            return false;
        }
        this.setBlock(entry, x, y, z, false);
        return true;
    }

    /**
     * Whether the player can't see the position: the client has it as filler, or, in per-block mode, it can't be seen
     * from any camera right now. Unlike {@link #withholdsUpdate} this changes nothing. Off the player's thread, only
     * filler counts.
     */
    public boolean hidesPosition(final int x, final int y, final int z) {
        if (this.isMasked(x, y, z)) {
            return true;
        }
        if (!this.perBlock) {
            return false;
        }
        final ServerPlayer player = this.owner.player;
        final Pvs pvs = this.current;
        final ServerLevel level = player.level();
        if (pvs == null || !pvs.perBlock || pvs.level != level || this.boxLevel != level || contains(this.blockBox, x, y, z)
            || !TickThread.isTickThreadFor(player) || pvs.mayShow(x, y, z)) {
            return false;
        }
        final Overlay overlay = this.overlay;
        return overlay == null || !overlay.shows(level, x, y, z);
    }

    /**
     * Leaves the masked sections and the light of filler blocks out of a light update. May be called from any thread.
     */
    public @Nullable ClientboundLightUpdatePacket filterLightUpdate(final ClientboundLightUpdatePacket packet) {
        if (this.maskedChunks == 0) {
            return packet;
        }
        final SentChunk entry = this.sent.get(CoordinateUtils.getChunkKey(packet.x(), packet.z()));
        if (entry == null) {
            return packet;
        }
        // masked first, see setState
        final long masked = entry.masked;
        final long[][] cells = entry.cells;
        return masked == 0L && cells == null ? packet : ChunkMasking.filterLight(packet, masked, cells);
    }
}
