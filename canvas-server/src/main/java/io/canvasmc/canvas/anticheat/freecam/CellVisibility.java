package io.canvasmc.canvas.anticheat.freecam;

import io.canvasmc.canvas.anticheat.raytrace.VoxelRaycaster;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The blocks a client could see from a camera box, for per-block anti-freecam: a superset of every block that a
 * straight line from a camera anywhere inside the box reaches without passing through a block that renders as a
 * full opaque cube.
 * <p>
 * Such a line steps from block to block through faces (the client's own culling treats corners and edges the same
 * way) and never reverses its direction on an axis. So every block it passes through is reachable from the camera
 * by a chain of see-through, face adjacent blocks that, outside the camera box, only ever moves away from it on each
 * axis, and the block it ends at is face adjacent to such a chain. The see-through blocks reachable like that
 * ({@code reach}), plus every block touching one of them, are the visible set. Inside the box a camera can be
 * anywhere, so moves are unrestricted there, but the camera can only be where it can get to from the eyes without
 * passing through blocks, so only blocks connected to the eyes are entered. Whether a move is allowed only depends
 * on the block it starts from, never on the path that led there, which keeps this a plain flood fill.
 * <p>
 * Blocks open to the sky (no opaque block above them in their column) are public: they are visible no matter where
 * the player is, along with the blocks next to them, and they also seed the flood. This keeps the landscape
 * stable for players walking around and only hides what lies underground or inside the terrain.
 * <p>
 * The flood only enters the sections of a given domain, the section level visible set ({@link OcclusionGraph}),
 * which already contains every section a line from the box can reach. Each section is processed as 64 longs with
 * one bit per block, indexed by {@code x | z << 4 | y << 8} like chunk storage and light data: a long holds four
 * rows of 16 blocks along x, four longs make a horizontal layer. Runs of see-through blocks are filled with a few
 * shifts per long (Kogge-Stone), so a section converges after a handful of passes no matter how large its caves
 * are. Sections whose blocks are all reachable, like the open sky, are flagged instead of stored.
 * <p>
 * <b>Line of sight</b> ({@link Sight}) narrows the result down to what straight lines from a few camera points really
 * reach, see {@link #restrictToSight}: the flood only bounds where to look.
 * <p>
 * Instances hold reusable working memory and are not thread safe.
 */
public final class CellVisibility {

    /**
     * The camera points of line of sight mode and how far from the first one lines are traced.
     */
    public static final class Sight {
        public static final int MAX_CAMERAS = 32;
        /**
         * Camera points closer than this to one already added see next to the same, they are left out.
         */
        private static final double MIN_SPACING_SQUARED = 0.5 * 0.5;

        /**
         * Camera points as {@code x, y, z} triples. The first one is the eyes.
         */
        public final double[] cameras = new double[3 * MAX_CAMERAS];
        public int cameraCount;
        /**
         * How many of the first cameras faces farther than {@link #GRID_DISTANCE_SQUARED} are tested from, see
         * {@link #primaryDone}.
         */
        int primaryCount = MAX_CAMERAS;
        /**
         * Blocks farther than this from the first camera aren't traced, they keep what the flood reached.
         */
        public double maxDistanceSquared = Double.POSITIVE_INFINITY;

        public void clear() {
            this.cameraCount = 0;
            this.primaryCount = MAX_CAMERAS;
        }

        /**
         * Marks the cameras added so far as the ones faces far away are tested from. The ones added afterwards stand
         * for where the cameras may get to soon: from far away they see about the same, and the lines of sight they
         * get through nearby faces are followed far anyway.
         */
        public void primaryDone() {
            this.primaryCount = this.cameraCount;
        }

        /**
         * Adds a camera point, unless there are {@link #MAX_CAMERAS} already.
         */
        public void add(final double x, final double y, final double z) {
            if (this.cameraCount == MAX_CAMERAS) {
                return;
            }
            for (int i = 0; i < this.cameraCount * 3; i += 3) {
                final double dx = this.cameras[i] - x;
                final double dy = this.cameras[i + 1] - y;
                final double dz = this.cameras[i + 2] - z;
                if (dx * dx + dy * dy + dz * dz < MIN_SPACING_SQUARED) {
                    return;
                }
            }
            final int i = this.cameraCount++ * 3;
            this.cameras[i] = x;
            this.cameras[i + 1] = y;
            this.cameras[i + 2] = z;
        }
    }

    /**
     * Where line of sight tests a face from the first camera: its center first, then a grid over it, inset a little
     * so the end point lies inside the face. Other cameras, and faces farther than {@link #GRID_DISTANCE_SQUARED},
     * only test the center: the blocks touching a visible block are sent too, which covers the rest.
     */
    private static final double[] FACE_SAMPLES = {0.5, 0.5, 0.05, 0.05, 0.95, 0.05, 0.05, 0.95, 0.95, 0.95,
        0.5, 0.05, 0.5, 0.95, 0.05, 0.5, 0.95, 0.5};
    private static final double GRID_DISTANCE_SQUARED = 32.0 * 32.0;

    /**
     * Supplies the sections of the grid.
     */
    public interface Sections {

        /**
         * @return whether the column exists, sections of missing columns are never entered or made visible
         */
        boolean hasColumn(int column);

        /**
         * @return the opacity of a section of an existing column, see {@link SectionVisibility#opacity}
         */
        long[] opacity(int column, int sectionIndex);
    }

    private static final Sections NO_SECTIONS = new Sections() {
        @Override
        public boolean hasColumn(final int column) {
            return false;
        }

        @Override
        public long[] opacity(final int column, final int sectionIndex) {
            return SectionVisibility.EVERYTHING_OPAQUE;
        }
    };

    public static final int WORDS = 64;
    /**
     * Every block of a section. Must not be modified.
     */
    public static final long[] EVERY_BLOCK = new long[WORDS];

    /**
     * Result kinds, see {@link #kind}.
     */
    public static final byte NOTHING = 0;
    public static final byte PARTIAL = 1;
    public static final byte EVERYTHING = 2;

    /**
     * Blocks at x = 0 and x = 15 of each row.
     */
    private static final long X0 = 0x0001_0001_0001_0001L;
    private static final long X15 = 0x8000_8000_8000_8000L;
    /**
     * The first row (lowest z) of a long.
     */
    private static final long ROW0 = 0xFFFFL;
    private static final long LANES = 0x0001_0001_0001_0001L;
    /**
     * Per set of rows of a long (4 bits), the mask selecting them.
     */
    private static final long[] ROWS = new long[16];

    // node states
    private static final byte NONE = 0;
    /**
     * The node has a slot in {@link #reach} and {@link #visible}.
     */
    private static final byte STORED = 1;
    /**
     * Every block of the node is reachable, it has no slot.
     */
    private static final byte FULL = 2;
    private static final byte QUEUED = 4;

    static {
        Arrays.fill(EVERY_BLOCK, -1L);
        for (int rows = 0; rows < 16; ++rows) {
            long mask = 0L;
            for (int row = 0; row < 4; ++row) {
                if ((rows >>> row & 1) != 0) {
                    mask |= ROW0 << (row * 16);
                }
            }
            ROWS[rows] = mask;
        }
    }

    private int width;
    private int depth;
    private int height;
    private int originX;
    private int originZ;
    private int minSectionY;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;
    private Sections sections = NO_SECTIONS;
    private long[] domain = new long[0];
    private int pushTarget;
    private int pushBase;
    private boolean pushChanged;

    private byte[] state = new byte[0];
    private int[] slot = new int[0];
    private long[][] opacity = new long[0][];
    private int[] queue = new int[0];
    private int slots;
    private int[] slotNode = new int[64];
    private long[] reach = new long[64 * WORDS];
    private long[] stageA = new long[64 * WORDS];
    private long[] stageB = new long[64 * WORDS];
    private long[] visible = new long[64 * WORDS];
    /**
     * Per slot, the blocks open to the sky.
     */
    private long[] open = new long[64 * WORDS];
    private int neighborBase;
    private final long[] layerSky = new long[WORDS];

    // line of sight
    private @Nullable Sight sight;
    private long[] cellQueue = new long[1024];
    private int cellHead;
    private int cellTail;
    private long[] seen = new long[0];

    /**
     * Computes the visible blocks. The results are read with {@link #kind} and {@link #copyVisible} until the next
     * computation.
     *
     * @param width       columns of the grid along x
     * @param depth       columns of the grid along z
     * @param height      sections per column
     * @param originX     chunk x of the first column
     * @param originZ     chunk z of the first column
     * @param minSectionY section y of the first section of a column
     * @param domain      per column, the sections the flood may enter
     * @param sky         whether blocks open to the sky are visible and seed the flood
     * @param cameras     block coordinates the flood starts from, as {@code x, y, z} triples, may lie in opaque
     *                    blocks (a player with their head in a wall)
     * @param box         the camera box in block coordinates: min x, y, z, max x, y, z
     * @param near        a box (block coordinates) in which the blocks touching visible blocks are visible as
     *                    well, or {@code null}: blocks the player may break must not have filler behind them, the
     *                    client shows what is behind a block before the server confirms it was broken
     * @param sight       line of sight mode, or {@code null}
     */
    public void compute(final Sections sections, final int width, final int depth, final int height,
                        final int originX, final int originZ, final int minSectionY, final long[] domain,
                        final boolean sky, final int[] cameras, final int cameraCount, final int[] box, final int @Nullable [] near,
                        final @Nullable Sight sight) {
        this.sections = sections;
        this.domain = domain;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.originX = originX;
        this.originZ = originZ;
        this.minSectionY = minSectionY;
        this.minX = box[0];
        this.minY = box[1];
        this.minZ = box[2];
        this.maxX = box[3];
        this.maxY = box[4];
        this.maxZ = box[5];

        final int nodes = width * depth * height;
        if (this.state.length < nodes) {
            this.state = new byte[nodes];
            this.slot = new int[nodes];
            this.opacity = new long[nodes][];
            this.queue = new int[nodes];
        } else {
            Arrays.fill(this.state, 0, nodes, NONE);
        }
        this.slots = 0;

        int queued = 0;
        if (sky) {
            queued = this.seedSky(queued);
        }
        for (int i = 0; i < cameraCount; ++i) {
            queued = this.seedCamera(cameras[i * 3], cameras[i * 3 + 1], cameras[i * 3 + 2], queued);
        }
        this.flood(queued);
        if (sight != null) {
            this.restrictToSight(sight);
        }
        this.dilate();
        if (near != null) {
            this.dilateNear(near);
        }
    }

    /**
     * Drops the references to sections and their data, so they don't stay loaded through this instance.
     */
    public void release() {
        this.sections = NO_SECTIONS;
        Arrays.fill(this.opacity, null);
        this.sight = null;
    }

    // results

    /**
     * @return {@link #NOTHING}, {@link #PARTIAL} or {@link #EVERYTHING} visible in the given section
     */
    public byte kind(final int column, final int sectionIndex) {
        final int node = column * this.height + sectionIndex;
        final byte state = (byte) (this.state[node] & (STORED | FULL));
        if (state == FULL) {
            return EVERYTHING;
        }
        if (state != STORED) {
            return NOTHING;
        }
        final long[] visible = this.visible;
        final int base = this.slot[node] * WORDS;
        long and = -1L;
        long or = 0L;
        for (int w = 0; w < WORDS; ++w) {
            and &= visible[base + w];
            or |= visible[base + w];
        }
        return and == -1L ? EVERYTHING : or == 0L ? NOTHING : PARTIAL;
    }

    /**
     * @return whether the visible blocks of a {@link #PARTIAL} section equal the given set
     */
    public boolean visibleEquals(final int column, final int sectionIndex, final long[] bits) {
        final int base = this.slot[column * this.height + sectionIndex] * WORDS;
        for (int w = 0; w < WORDS; ++w) {
            if (this.visible[base + w] != bits[w]) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return a copy of the visible blocks of a {@link #PARTIAL} section
     */
    public long[] copyVisible(final int column, final int sectionIndex) {
        final int base = this.slot[column * this.height + sectionIndex] * WORDS;
        return Arrays.copyOfRange(this.visible, base, base + WORDS);
    }

    // node bookkeeping

    private boolean inDomain(final int column, final int sectionIndex) {
        return (this.domain[column] >>> sectionIndex & 1L) != 0L;
    }

    private long[] opacityOf(final int node) {
        long[] ret = this.opacity[node];
        if (ret == null) {
            ret = this.opacity[node] = this.sections.opacity(node / this.height, node % this.height);
        }
        return ret;
    }

    private int store(final int node) {
        final int index = this.slots++;
        if (index == this.slotNode.length) {
            this.slotNode = Arrays.copyOf(this.slotNode, index * 2);
            this.reach = Arrays.copyOf(this.reach, index * 2 * WORDS);
            this.stageA = Arrays.copyOf(this.stageA, index * 2 * WORDS);
            this.stageB = Arrays.copyOf(this.stageB, index * 2 * WORDS);
            this.visible = Arrays.copyOf(this.visible, index * 2 * WORDS);
            this.open = Arrays.copyOf(this.open, index * 2 * WORDS);
        }
        final int base = index * WORDS;
        Arrays.fill(this.open, base, base + WORDS, 0L);
        Arrays.fill(this.reach, base, base + WORDS, 0L);
        Arrays.fill(this.stageA, base, base + WORDS, 0L);
        Arrays.fill(this.stageB, base, base + WORDS, 0L);
        Arrays.fill(this.visible, base, base + WORDS, 0L);
        this.slotNode[index] = node;
        this.slot[node] = index;
        this.state[node] = (byte) ((this.state[node] & QUEUED) | STORED);
        return index;
    }

    private int enqueue(final int node, final int queued) {
        if ((this.state[node] & QUEUED) != 0) {
            return queued;
        }
        this.state[node] |= QUEUED;
        this.queue[queued] = node;
        return queued + 1;
    }

    /**
     * @return the node of the neighbor of a node in the given direction, or -1 if it lies outside the grid or in a
     * missing column
     */
    private int neighbor(final int node, final int direction) {
        final int ret = this.neighborInGrid(node, direction);
        if (ret < 0 || ret / this.height == node / this.height || this.sections.hasColumn(ret / this.height)) {
            return ret;
        }
        return -1;
    }

    /**
     * Same as {@link #neighbor}, including sections of missing columns. Visible blocks spread through those, so
     * the blocks diagonally across them are still found.
     */
    private int neighborInGrid(final int node, final int direction) {
        final int height = this.height;
        final int column = node / height;
        final int index = node - column * height;
        int x = column % this.width;
        int z = column / this.width;
        int y = index;
        switch (direction) {
            case SectionVisibility.DOWN -> --y;
            case SectionVisibility.UP -> ++y;
            case SectionVisibility.NORTH -> --z;
            case SectionVisibility.SOUTH -> ++z;
            case SectionVisibility.WEST -> --x;
            default -> ++x;
        }
        if (x < 0 || x >= this.width || z < 0 || z >= this.depth || y < 0 || y >= height) {
            return -1;
        }
        return (z * this.width + x) * height + y;
    }

    // seeding

    /**
     * Marks the blocks open to the sky as reachable, column by column from the top down until every column of a
     * chunk is covered by an opaque block. Sections that are open to the sky entirely are flagged {@link #FULL}.
     */
    private int seedSky(int queued) {
        final int height = this.height;
        final long[] sky = this.layerSky;
        for (int column = 0, columns = this.width * this.depth; column < columns; ++column) {
            if (!this.sections.hasColumn(column)) {
                continue;
            }
            // which of the 256 columns of blocks are covered, laid out like a layer of a section
            long covered0 = 0L;
            long covered1 = 0L;
            long covered2 = 0L;
            long covered3 = 0L;
            for (int index = height - 1; index >= 0; --index) {
                if ((covered0 & covered1 & covered2 & covered3) == -1L) {
                    break;
                }
                final int node = column * height + index;
                final long[] opaque = this.opacityOf(node);
                if (opaque == SectionVisibility.NOTHING_OPAQUE && (covered0 | covered1 | covered2 | covered3) == 0L) {
                    this.state[node] = FULL;
                    queued = this.enqueue(node, queued);
                    continue;
                }
                long any = 0L;
                for (int y = 15; y >= 0; --y) {
                    final int w = y << 2;
                    any |= sky[w] = ~covered0 & ~opaque[w];
                    any |= sky[w + 1] = ~covered1 & ~opaque[w + 1];
                    any |= sky[w + 2] = ~covered2 & ~opaque[w + 2];
                    any |= sky[w + 3] = ~covered3 & ~opaque[w + 3];
                    covered0 |= opaque[w];
                    covered1 |= opaque[w + 1];
                    covered2 |= opaque[w + 2];
                    covered3 |= opaque[w + 3];
                }
                if (any != 0L) {
                    final int base = this.store(node) * WORDS;
                    System.arraycopy(sky, 0, this.reach, base, WORDS);
                    System.arraycopy(sky, 0, this.open, base, WORDS);
                    queued = this.enqueue(node, queued);
                }
            }
        }
        return queued;
    }

    private int seedCamera(final int x, final int y, final int z, final int queued) {
        final int columnX = (x >> 4) - this.originX;
        final int columnZ = (z >> 4) - this.originZ;
        final int index = (y >> 4) - this.minSectionY;
        if (columnX < 0 || columnX >= this.width || columnZ < 0 || columnZ >= this.depth || index < 0 || index >= this.height) {
            return queued;
        }
        final int column = columnZ * this.width + columnX;
        if (!this.sections.hasColumn(column) || !this.inDomain(column, index)) {
            return queued;
        }
        final int node = column * this.height + index;
        final byte state = this.state[node];
        if ((state & FULL) != 0) {
            return queued;
        }
        final int base = ((state & STORED) != 0 ? this.slot[node] : this.store(node)) * WORDS;
        final int bit = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        this.reach[base + (bit >>> 6)] |= 1L << bit;
        return this.enqueue(node, queued);
    }

    // flood

    private void flood(int queued) {
        final int height = this.height;
        int head = 0;
        while (head < queued) {
            final int node = this.queue[head++];
            final byte state = (byte) (this.state[node] & ~QUEUED);
            this.state[node] = state;
            final int column = node / height;
            final int index = node - column * height;
            final int blockX = (this.originX + column % this.width) << 4;
            final int blockY = (this.minSectionY + index) << 4;
            final int blockZ = (this.originZ + column / this.width) << 4;
            final long[] from;
            int fromBase = 0;
            if (state == FULL) {
                from = EVERY_BLOCK;
            } else {
                from = this.reach;
                fromBase = this.slot[node] * WORDS;
                if (this.inDomain(column, index)) {
                    this.fill(fromBase, this.opacityOf(node), blockX, blockY, blockZ);
                }
            }
            // pushes into the neighbors, compacting the queue when it runs full
            if (queued + 6 > this.queue.length) {
                System.arraycopy(this.queue, head, this.queue, 0, queued - head);
                queued -= head;
                head = 0;
            }
            for (int direction = 0; direction < 6; ++direction) {
                if (this.mayLeave(direction, blockX, blockY, blockZ)) {
                    queued = this.push(from, fromBase, node, direction, queued);
                }
            }
        }
    }

    /**
     * Whether the blocks on the face of a section in the given direction may move across it.
     */
    private boolean mayLeave(final int direction, final int blockX, final int blockY, final int blockZ) {
        return switch (direction) {
            case SectionVisibility.DOWN -> blockY <= this.maxY;
            case SectionVisibility.UP -> blockY + 15 >= this.minY;
            case SectionVisibility.NORTH -> blockZ <= this.maxZ;
            case SectionVisibility.SOUTH -> blockZ + 15 >= this.minZ;
            case SectionVisibility.WEST -> blockX <= this.maxX;
            default -> blockX + 15 >= this.minX;
        };
    }

    /**
     * Moves the reachable blocks on a face of a section into the neighbor, if the neighbor may be entered.
     */
    private int push(final long[] from, final int fromBase, final int node, final int direction, final int queued) {
        final int neighbor = this.neighbor(node, direction);
        if (neighbor < 0) {
            return queued;
        }
        final int column = neighbor / this.height;
        final byte state = this.state[neighbor];
        if ((state & FULL) != 0 || !this.inDomain(column, neighbor - column * this.height)) {
            return queued;
        }
        final long[] opaque = this.opacityOf(neighbor);
        if (opaque == SectionVisibility.EVERYTHING_OPAQUE) {
            return queued;
        }
        this.pushTarget = neighbor;
        this.pushBase = (state & STORED) != 0 ? this.slot[neighbor] * WORDS : -1;
        this.pushChanged = false;
        switch (direction) {
            case SectionVisibility.DOWN -> {
                for (int k = 0; k < 4; ++k) {
                    this.enter(60 + k, from[fromBase + k], opaque);
                }
            }
            case SectionVisibility.UP -> {
                for (int k = 0; k < 4; ++k) {
                    this.enter(k, from[fromBase + 60 + k], opaque);
                }
            }
            case SectionVisibility.NORTH -> {
                for (int y = 0; y < 16; ++y) {
                    this.enter((y << 2) + 3, from[fromBase + (y << 2)] << 48, opaque);
                }
            }
            case SectionVisibility.SOUTH -> {
                for (int y = 0; y < 16; ++y) {
                    this.enter(y << 2, from[fromBase + (y << 2) + 3] >>> 48, opaque);
                }
            }
            case SectionVisibility.WEST -> {
                for (int w = 0; w < WORDS; ++w) {
                    this.enter(w, (from[fromBase + w] & X0) << 15, opaque);
                }
            }
            default -> {
                for (int w = 0; w < WORDS; ++w) {
                    this.enter(w, (from[fromBase + w] & X15) >>> 15, opaque);
                }
            }
        }
        return this.pushChanged ? this.enqueue(neighbor, queued) : queued;
    }

    /**
     * Adds blocks entered from a neighbor to a word of {@link #pushTarget}, storing it on first use.
     */
    private void enter(final int word, final long in, final long[] opaque) {
        final long add = in & ~opaque[word];
        if (add == 0L) {
            return;
        }
        if (this.pushBase < 0) {
            this.pushBase = this.store(this.pushTarget) * WORDS;
        }
        final long old = this.reach[this.pushBase + word];
        if ((add & ~old) != 0L) {
            this.reach[this.pushBase + word] = old | add;
            this.pushChanged = true;
        }
    }

    /**
     * Bits of the 16 positions along an axis that are at least {@code from}.
     */
    private static int atLeast(final int from) {
        if (from <= 0) {
            return 0xFFFF;
        }
        return from > 15 ? 0 : (0xFFFF << from) & 0xFFFF;
    }

    /**
     * Bits of the 16 positions along an axis that are at most {@code to}.
     */
    private static int atMost(final int to) {
        if (to >= 15) {
            return 0xFFFF;
        }
        return to < 0 ? 0 : (1 << (to + 1)) - 1;
    }

    /**
     * Floods a section until nothing changes anymore. Each round fills along every allowed direction of every axis,
     * through runs of see-through blocks.
     */
    private void fill(final int base, final long[] opaque, final int blockX, final int blockY, final int blockZ) {
        final long[] reach = this.reach;
        // per axis, the positions moves in the positive and negative direction may start from
        final long xUp = atLeast(this.minX - blockX) * LANES;
        final long xDown = atMost(this.maxX - blockX) * LANES;
        final int zUp = atLeast(this.minZ - blockZ);
        final int zDown = atMost(this.maxZ - blockZ);
        final int yUp = atLeast(this.minY - blockY);
        final int yDown = atMost(this.maxY - blockY);

        boolean changed = true;
        while (changed) {
            changed = false;

            // x, within the rows of each long
            for (int w = 0; w < WORDS; ++w) {
                final long old = reach[base + w];
                if (old == 0L) {
                    continue;
                }
                final long open = ~opaque[w];
                long up = old & xUp;
                long propagate = open & ~X0;
                up |= propagate & (up << 1);
                propagate &= propagate << 1;
                up |= propagate & (up << 2);
                propagate &= propagate << 2;
                up |= propagate & (up << 4);
                propagate &= propagate << 4;
                up |= propagate & (up << 8);
                long down = old & xDown;
                propagate = open & ~X15;
                down |= propagate & (down >>> 1);
                propagate &= propagate >>> 1;
                down |= propagate & (down >>> 2);
                propagate &= propagate >>> 2;
                down |= propagate & (down >>> 4);
                propagate &= propagate >>> 4;
                down |= propagate & (down >>> 8);
                final long now = old | up | down;
                if (now != old) {
                    reach[base + w] = now;
                    changed = true;
                }
            }

            // z, within the rows of each long and from long to long of a layer
            for (int y = 0; y < 16; ++y) {
                final int layer = base + (y << 2);
                long carry = 0L;
                for (int k = 0; k < 4; ++k) {
                    final long old = reach[layer + k];
                    final long open = ~opaque[(y << 2) + k];
                    long up = (old & ROWS[(zUp >>> (k << 2)) & 15]) | (carry & open);
                    long propagate = open & ~ROW0;
                    up |= propagate & (up << 16);
                    propagate &= propagate << 16;
                    up |= propagate & (up << 32);
                    carry = up >>> 48;
                    if ((up & ~old) != 0L) {
                        reach[layer + k] = old | up;
                        changed = true;
                    }
                }
                carry = 0L;
                for (int k = 3; k >= 0; --k) {
                    final long old = reach[layer + k];
                    final long open = ~opaque[(y << 2) + k];
                    long down = (old & ROWS[(zDown >>> (k << 2)) & 15]) | (carry & open);
                    long propagate = open & ~(ROW0 << 48);
                    down |= propagate & (down >>> 16);
                    propagate &= propagate >>> 16;
                    down |= propagate & (down >>> 32);
                    carry = down << 48;
                    if ((down & ~old) != 0L) {
                        reach[layer + k] = old | down;
                        changed = true;
                    }
                }
            }

            // y, from layer to layer
            for (int y = 1; y < 16; ++y) {
                if ((yUp >>> (y - 1) & 1) == 0) {
                    continue;
                }
                for (int k = 0; k < 4; ++k) {
                    final int w = (y << 2) + k;
                    final long add = reach[base + w - 4] & ~opaque[w] & ~reach[base + w];
                    if (add != 0L) {
                        reach[base + w] |= add;
                        changed = true;
                    }
                }
            }
            for (int y = 14; y >= 0; --y) {
                if ((yDown >>> (y + 1) & 1) == 0) {
                    continue;
                }
                for (int k = 0; k < 4; ++k) {
                    final int w = (y << 2) + k;
                    final long add = reach[base + w + 4] & ~opaque[w] & ~reach[base + w];
                    if (add != 0L) {
                        reach[base + w] |= add;
                        changed = true;
                    }
                }
            }
        }
    }

    // line of sight

    /**
     * Narrows the reachable blocks down to the see-through blocks that a straight line from a camera point reaches.
     * <p>
     * Such a line enters each block it passes through by one of its faces, coming from the block before it, which
     * the line reached too. So the blocks it reaches are found by a search from the cameras: a block is entered from
     * a reached neighbor only if a line from a camera reaches the face between them, tested with a few points on
     * the face ({@link #FACE_SAMPLES}). Blocks open to the sky are public and stay reachable without a test, the
     * blocks next to them are entered from them the same way. Only blocks the flood reached can be visible (it
     * covers every straight line from the camera box), so only those are entered, and blocks farther than
     * {@link Sight#maxDistanceSquared} keep what the flood found.
     */
    private void restrictToSight(final Sight sight) {
        this.sight = sight;
        final long[] seen = this.seen = this.stageA;
        Arrays.fill(seen, 0, this.slots * WORDS, 0L);
        this.cellHead = 0;
        this.cellTail = 0;
        // the blocks of the cameras themselves
        for (int i = 0; i < sight.cameraCount; ++i) {
            final int x = (int) Math.floor(sight.cameras[i * 3]);
            final int y = (int) Math.floor(sight.cameras[i * 3 + 1]);
            final int z = (int) Math.floor(sight.cameras[i * 3 + 2]);
            final int base = this.storedBase(x, y, z);
            if (base >= 0) {
                final int bit = bitOf(x, y, z);
                seen[base + (bit >>> 6)] |= 1L << bit;
            }
            this.queueCell(x, y, z);
        }
        this.seedNextToOpen();
        this.spread();
        if (sight.maxDistanceSquared != Double.POSITIVE_INFINITY) {
            this.keepFar(seen, sight);
        }
        final long[] reach = this.reach;
        final long[] open = this.open;
        for (int i = 0, words = this.slots * WORDS; i < words; ++i) {
            reach[i] = open[i] | seen[i];
        }
    }

    /**
     * Enters the reachable blocks next to blocks open to the sky from them.
     */
    private void seedNextToOpen() {
        final Sight sight = this.sight;
        final long[] seen = this.seen;
        final long[] next = this.stageB;
        for (int i = 0, slots = this.slots; i < slots; ++i) {
            final int base = i * WORDS;
            this.faceNeighbors(i, this.open, next);
            final int node = this.slotNode[i];
            final int column = node / this.height;
            final int blockX = (this.originX + column % this.width) << 4;
            final int blockY = (this.minSectionY + node % this.height) << 4;
            final int blockZ = (this.originZ + column / this.width) << 4;
            for (int w = 0; w < WORDS; ++w) {
                for (long bits = next[base + w] & this.reach[base + w] & ~this.open[base + w]; bits != 0L; bits &= bits - 1L) {
                    final int bit = w << 6 | Long.numberOfTrailingZeros(bits);
                    if ((seen[base + w] >>> bit & 1L) != 0L) {
                        // an extended line of sight got there already
                        continue;
                    }
                    final int x = blockX | bit & 15;
                    final int y = blockY | bit >>> 8;
                    final int z = blockZ | (bit >>> 4) & 15;
                    if (!this.withinTraceDistance(sight, x, y, z)) {
                        continue;
                    }
                    for (int direction = 0; direction < 6; ++direction) {
                        // from the open neighbor towards this block
                        final int fromX = x - stepX(direction);
                        final int fromY = y - stepY(direction);
                        final int fromZ = z - stepZ(direction);
                        if (this.isOpen(fromX, fromY, fromZ) && this.faceVisible(fromX, fromY, fromZ, direction)) {
                            seen[base + w] |= 1L << bit;
                            this.queueCell(x, y, z);
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Searches outwards from the queued blocks, entering every reachable neighbor whose shared face a camera sees.
     */
    private void spread() {
        final Sight sight = this.sight;
        final long[] seen = this.seen;
        final int gridX = this.originX << 4;
        final int gridY = this.minSectionY << 4;
        final int gridZ = this.originZ << 4;
        while (this.cellHead < this.cellTail) {
            final long cell = this.cellQueue[this.cellHead++];
            final int x = gridX + (int) (cell & 0xFFFFL);
            final int z = gridZ + (int) (cell >>> 16 & 0xFFFFL);
            final int y = gridY + (int) (cell >>> 32);
            for (int direction = 0; direction < 6; ++direction) {
                final int nx = x + stepX(direction);
                final int ny = y + stepY(direction);
                final int nz = z + stepZ(direction);
                final int base = this.storedBase(nx, ny, nz);
                if (base < 0) {
                    continue;
                }
                final int bit = bitOf(nx, ny, nz);
                final int word = base + (bit >>> 6);
                final long mask = 1L << bit;
                if ((this.reach[word] & ~this.open[word] & ~seen[word] & mask) == 0L
                    || !this.withinTraceDistance(sight, nx, ny, nz) || !this.faceVisible(x, y, z, direction)) {
                    continue;
                }
                seen[word] |= mask;
                this.queueCell(nx, ny, nz);
            }
        }
    }

    /**
     * Follows a line of sight from a camera on past the point a test found it reaching, marking every reachable
     * block it passes before it hits a block that renders as a full opaque cube: those are seen too. In open caves
     * a handful of such lines reach most blocks, which then need no test of their own.
     */
    private void extend(final double camX, final double camY, final double camZ, final double pointX, final double pointY, final double pointZ) {
        final double dx = pointX - camX;
        final double dy = pointY - camY;
        final double dz = pointZ - camZ;
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6) {
            return;
        }
        final double maxT = Math.min(Math.sqrt(this.sight.maxDistanceSquared) + 2.0, 1024.0) / length;
        // the block the line enters right after the point
        int x = (int) Math.floor(pointX + dx * 1.0E-6);
        int y = (int) Math.floor(pointY + dy * 1.0E-6);
        int z = (int) Math.floor(pointZ + dz * 1.0E-6);
        final int stepX = dx >= 0.0 ? 1 : -1;
        final int stepY = dy >= 0.0 ? 1 : -1;
        final int stepZ = dz >= 0.0 ? 1 : -1;
        final double tDeltaX = dx == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        final double tDeltaY = dy == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        final double tDeltaZ = dz == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        // t is measured along the camera to point segment, the point is at 1
        double tMaxX = dx == 0.0 ? Double.POSITIVE_INFINITY : 1.0 + (stepX > 0 ? (x + 1.0 - pointX) : (pointX - x)) * tDeltaX;
        double tMaxY = dy == 0.0 ? Double.POSITIVE_INFINITY : 1.0 + (stepY > 0 ? (y + 1.0 - pointY) : (pointY - y)) * tDeltaY;
        double tMaxZ = dz == 0.0 ? Double.POSITIVE_INFINITY : 1.0 + (stepZ > 0 ? (z + 1.0 - pointZ) : (pointZ - z)) * tDeltaZ;
        final long[] seen = this.seen;
        int sectionX = Integer.MIN_VALUE;
        int sectionY = 0;
        int sectionZ = 0;
        int node = -1;
        long[] opaque = SectionVisibility.NOTHING_OPAQUE;
        for (;;) {
            if (x >> 4 != sectionX || y >> 4 != sectionY || z >> 4 != sectionZ) {
                sectionX = x >> 4;
                sectionY = y >> 4;
                sectionZ = z >> 4;
                node = this.nodeAt(x, y, z);
                if (node < 0) {
                    // left the grid or entered a missing column, nothing to mark beyond
                    return;
                }
                opaque = this.opacityOf(node);
            }
            final int bit = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
            if ((opaque[bit >>> 6] >>> bit & 1L) != 0L) {
                return;
            }
            if ((this.state[node] & STORED) != 0) {
                final int word = this.slot[node] * WORDS + (bit >>> 6);
                final long mask = 1L << bit;
                if ((this.reach[word] & ~this.open[word] & ~seen[word] & mask) != 0L) {
                    seen[word] |= mask;
                    this.queueCell(x, y, z);
                }
            }
            final double t;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (t > maxT) {
                return;
            }
        }
    }

    /**
     * Blocks beyond the trace distance keep what the flood reached.
     */
    private void keepFar(final long[] seen, final Sight sight) {
        final double eyeX = sight.cameras[0];
        final double eyeY = sight.cameras[1];
        final double eyeZ = sight.cameras[2];
        final double max = sight.maxDistanceSquared;
        for (int i = 0, slots = this.slots; i < slots; ++i) {
            final int node = this.slotNode[i];
            final int column = node / this.height;
            final int blockX = (this.originX + column % this.width) << 4;
            final int blockY = (this.minSectionY + node % this.height) << 4;
            final int blockZ = (this.originZ + column / this.width) << 4;
            final double nearX = Math.max(0.0, Math.max(blockX - eyeX, eyeX - (blockX + 16)));
            final double nearY = Math.max(0.0, Math.max(blockY - eyeY, eyeY - (blockY + 16)));
            final double nearZ = Math.max(0.0, Math.max(blockZ - eyeZ, eyeZ - (blockZ + 16)));
            if (nearX * nearX + nearY * nearY + nearZ * nearZ > max) {
                final int base = i * WORDS;
                for (int w = 0; w < WORDS; ++w) {
                    seen[base + w] |= this.reach[base + w];
                }
                continue;
            }
            final double farX = Math.max(Math.abs(blockX - eyeX), Math.abs(blockX + 16 - eyeX));
            final double farY = Math.max(Math.abs(blockY - eyeY), Math.abs(blockY + 16 - eyeY));
            final double farZ = Math.max(Math.abs(blockZ - eyeZ), Math.abs(blockZ + 16 - eyeZ));
            if (farX * farX + farY * farY + farZ * farZ <= max) {
                continue;
            }
            final int base = i * WORDS;
            for (int w = 0; w < WORDS; ++w) {
                for (long bits = this.reach[base + w] & ~seen[base + w]; bits != 0L; bits &= bits - 1L) {
                    final int bit = w << 6 | Long.numberOfTrailingZeros(bits);
                    if (!this.withinTraceDistance(sight, blockX | bit & 15, blockY | bit >>> 8, blockZ | (bit >>> 4) & 15)) {
                        seen[base + w] |= 1L << bit;
                    }
                }
            }
        }
    }

    private boolean withinTraceDistance(final Sight sight, final int x, final int y, final int z) {
        final double dx = x + 0.5 - sight.cameras[0];
        final double dy = y + 0.5 - sight.cameras[1];
        final double dz = z + 0.5 - sight.cameras[2];
        return dx * dx + dy * dy + dz * dz <= sight.maxDistanceSquared;
    }

    /**
     * Whether a straight line from a camera reaches the face of the block in the given direction, coming through
     * the block. Cameras on the other side of the face can't.
     */
    private boolean faceVisible(final int x, final int y, final int z, final int direction) {
        final Sight sight = this.sight;
        final double[] cameras = sight.cameras;
        // the face: its plane on the axis of the direction, spanned by the two other axes
        final int axis = axisOf(direction);
        final boolean positive = stepX(direction) + stepY(direction) + stepZ(direction) > 0;
        final double plane = (axis == 0 ? x : axis == 1 ? y : z) + (positive ? 1 : 0);
        final double ex = x + 0.5 - cameras[0];
        final double ey = y + 0.5 - cameras[1];
        final double ez = z + 0.5 - cameras[2];
        final boolean far = ex * ex + ey * ey + ez * ez > GRID_DISTANCE_SQUARED;
        final int samples = far ? 1 : FACE_SAMPLES.length / 2;
        final int cameraCount = far ? Math.min(sight.cameraCount, sight.primaryCount) : sight.cameraCount;
        for (int sample = 0; sample < samples; ++sample) {
            final double u = FACE_SAMPLES[sample * 2];
            final double v = FACE_SAMPLES[sample * 2 + 1];
            final double px;
            final double py;
            final double pz;
            switch (axis) {
                case 0 -> {
                    px = plane;
                    py = y + u;
                    pz = z + v;
                }
                case 1 -> {
                    px = x + u;
                    py = plane;
                    pz = z + v;
                }
                default -> {
                    px = x + u;
                    py = y + v;
                    pz = plane;
                }
            }
            // every camera tests the center, only the first one the rest
            for (int camera = 0, count = sample == 0 ? cameraCount : 1; camera < count; ++camera) {
                final double cx = cameras[camera * 3];
                final double cy = cameras[camera * 3 + 1];
                final double cz = cameras[camera * 3 + 2];
                final double side = axis == 0 ? cx : axis == 1 ? cy : cz;
                if (positive ? side >= plane : side <= plane) {
                    continue;
                }
                if (this.isClear(cx, cy, cz, px, py, pz)) {
                    this.extend(cx, cy, cz, px, py, pz);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether no block between the two points renders as a full opaque cube, not counting the blocks the points are
     * in: {@link VoxelRaycaster#isClear}, reading the opacity of the grid one section at a time.
     */
    private boolean isClear(final double x0, final double y0, final double z0, final double x1, final double y1, final double z1) {
        int x = (int) Math.floor(x0);
        int y = (int) Math.floor(y0);
        int z = (int) Math.floor(z0);
        int remainingX = Math.abs((int) Math.floor(x1) - x);
        int remainingY = Math.abs((int) Math.floor(y1) - y);
        int remainingZ = Math.abs((int) Math.floor(z1) - z);
        int remaining = remainingX + remainingY + remainingZ;
        if (remaining <= 1) {
            return true;
        }
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double dz = z1 - z0;
        final int stepX = dx >= 0.0 ? 1 : -1;
        final int stepY = dy >= 0.0 ? 1 : -1;
        final int stepZ = dz >= 0.0 ? 1 : -1;
        final double tDeltaX = dx == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        final double tDeltaY = dy == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        final double tDeltaZ = dz == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double tMaxX = dx == 0.0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1.0 - x0) : (x0 - x)) * tDeltaX;
        double tMaxY = dy == 0.0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1.0 - y0) : (y0 - y)) * tDeltaY;
        double tMaxZ = dz == 0.0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1.0 - z0) : (z0 - z)) * tDeltaZ;
        int sectionX = Integer.MIN_VALUE;
        int sectionY = 0;
        int sectionZ = 0;
        long[] opaque = SectionVisibility.NOTHING_OPAQUE;
        // the end block isn't tested
        while (--remaining > 0) {
            if (remainingX > 0 && (remainingY == 0 || tMaxX < tMaxY) && (remainingZ == 0 || tMaxX < tMaxZ)) {
                x += stepX;
                tMaxX += tDeltaX;
                --remainingX;
            } else if (remainingY > 0 && (remainingZ == 0 || tMaxY < tMaxZ)) {
                y += stepY;
                tMaxY += tDeltaY;
                --remainingY;
            } else if (remainingZ > 0) {
                z += stepZ;
                tMaxZ += tDeltaZ;
                --remainingZ;
            } else {
                x += stepX;
                tMaxX += tDeltaX;
                --remainingX;
            }
            if (x >> 4 != sectionX || y >> 4 != sectionY || z >> 4 != sectionZ) {
                sectionX = x >> 4;
                sectionY = y >> 4;
                sectionZ = z >> 4;
                final int node = this.nodeAt(x, y, z);
                opaque = node < 0 ? SectionVisibility.NOTHING_OPAQUE : this.opacityOf(node);
            }
            final int bit = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
            if ((opaque[bit >>> 6] >>> bit & 1L) != 0L) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the section of the grid holding the block, or -1 if it lies outside the grid or in a missing column
     */
    private int nodeAt(final int x, final int y, final int z) {
        final int columnX = (x >> 4) - this.originX;
        final int columnZ = (z >> 4) - this.originZ;
        final int index = (y >> 4) - this.minSectionY;
        if (columnX < 0 || columnX >= this.width || columnZ < 0 || columnZ >= this.depth || index < 0 || index >= this.height) {
            return -1;
        }
        final int column = columnZ * this.width + columnX;
        return this.sections.hasColumn(column) ? column * this.height + index : -1;
    }

    /**
     * @return the base of the block's section in the slot arrays, or -1 if it has no slot
     */
    private int storedBase(final int x, final int y, final int z) {
        final int node = this.nodeAt(x, y, z);
        return node < 0 || (this.state[node] & STORED) == 0 ? -1 : this.slot[node] * WORDS;
    }

    private boolean isOpen(final int x, final int y, final int z) {
        final int node = this.nodeAt(x, y, z);
        if (node < 0) {
            return false;
        }
        final byte state = this.state[node];
        if ((state & FULL) != 0) {
            return true;
        }
        if ((state & STORED) == 0) {
            return false;
        }
        final int bit = bitOf(x, y, z);
        return (this.open[this.slot[node] * WORDS + (bit >>> 6)] >>> bit & 1L) != 0L;
    }

    private void queueCell(final int x, final int y, final int z) {
        final int rx = x - (this.originX << 4);
        final int ry = y - (this.minSectionY << 4);
        final int rz = z - (this.originZ << 4);
        if (rx < 0 || rx >= this.width << 4 || rz < 0 || rz >= this.depth << 4 || ry < 0 || ry >= this.height << 4) {
            return;
        }
        if (this.cellTail == this.cellQueue.length) {
            if (this.cellHead > this.cellQueue.length >> 1) {
                System.arraycopy(this.cellQueue, this.cellHead, this.cellQueue, 0, this.cellTail - this.cellHead);
                this.cellTail -= this.cellHead;
                this.cellHead = 0;
            } else {
                this.cellQueue = Arrays.copyOf(this.cellQueue, this.cellQueue.length * 2);
            }
        }
        this.cellQueue[this.cellTail++] = rx | (long) rz << 16 | (long) ry << 32;
    }

    /**
     * The blocks sharing a face with the given blocks of a section, from its neighbors too.
     */
    private void faceNeighbors(final int slot, final long[] from, final long[] to) {
        final int node = this.slotNode[slot];
        final int base = slot * WORDS;
        for (int w = 0; w < WORDS; ++w) {
            final long b = from[base + w];
            long n = ((b << 1) & ~X0) | ((b >>> 1) & ~X15) | (b << 16) | (b >>> 16);
            final int k = w & 3;
            if (k != 0) {
                n |= from[base + w - 1] >>> 48;
            }
            if (k != 3) {
                n |= from[base + w + 1] << 48;
            }
            if (w >= 4) {
                n |= from[base + w - 4];
            }
            if (w < 60) {
                n |= from[base + w + 4];
            }
            to[base + w] = n;
        }
        final long[] west = this.neighborStage(node, SectionVisibility.WEST, from);
        if (west != null) {
            for (int w = 0; w < WORDS; ++w) {
                to[base + w] |= (west[this.neighborBase + w] & X15) >>> 15;
            }
        }
        final long[] east = this.neighborStage(node, SectionVisibility.EAST, from);
        if (east != null) {
            for (int w = 0; w < WORDS; ++w) {
                to[base + w] |= (east[this.neighborBase + w] & X0) << 15;
            }
        }
        final long[] north = this.neighborStage(node, SectionVisibility.NORTH, from);
        if (north != null) {
            for (int y = 0; y < 16; ++y) {
                to[base + (y << 2)] |= north[this.neighborBase + (y << 2) + 3] >>> 48;
            }
        }
        final long[] south = this.neighborStage(node, SectionVisibility.SOUTH, from);
        if (south != null) {
            for (int y = 0; y < 16; ++y) {
                to[base + (y << 2) + 3] |= south[this.neighborBase + (y << 2)] << 48;
            }
        }
        final long[] below = this.neighborStage(node, SectionVisibility.DOWN, from);
        if (below != null) {
            for (int k = 0; k < 4; ++k) {
                to[base + k] |= below[this.neighborBase + 60 + k];
            }
        }
        final long[] above = this.neighborStage(node, SectionVisibility.UP, from);
        if (above != null) {
            for (int k = 0; k < 4; ++k) {
                to[base + 60 + k] |= above[this.neighborBase + k];
            }
        }
    }

    private static int bitOf(final int x, final int y, final int z) {
        return (x & 15) | (z & 15) << 4 | (y & 15) << 8;
    }

    private static int axisOf(final int direction) {
        return switch (direction) {
            case SectionVisibility.WEST, SectionVisibility.EAST -> 0;
            case SectionVisibility.DOWN, SectionVisibility.UP -> 1;
            default -> 2;
        };
    }

    private static int stepX(final int direction) {
        return direction == SectionVisibility.WEST ? -1 : direction == SectionVisibility.EAST ? 1 : 0;
    }

    private static int stepY(final int direction) {
        return direction == SectionVisibility.DOWN ? -1 : direction == SectionVisibility.UP ? 1 : 0;
    }

    private static int stepZ(final int direction) {
        return direction == SectionVisibility.NORTH ? -1 : direction == SectionVisibility.SOUTH ? 1 : 0;
    }

    // visible blocks

    /**
     * Makes every block touching a reachable block visible, through a face, an edge or a corner, including the
     * blocks of neighboring sections. The client's smooth lighting and ambient occlusion of a visible face read the
     * blocks around the block in front of it, so those must be real too. Dilates along x, then z, then y, which
     * covers the whole 3 x 3 x 3 cube around each reachable block.
     */
    private void dilate() {
        final int nodes = this.width * this.depth * this.height;
        // along x, from the reachable blocks into a
        this.storeTouched(this.reach, SectionVisibility.WEST, SectionVisibility.EAST, nodes, null);
        for (int i = 0; i < this.slots; ++i) {
            this.dilateX(i, this.reach, this.stageA);
        }
        // along z, from a into b
        this.storeTouched(this.stageA, SectionVisibility.NORTH, SectionVisibility.SOUTH, nodes, null);
        for (int i = 0; i < this.slots; ++i) {
            this.dilateZ(i, this.stageA, this.stageB);
        }
        // along y, from b into the visible blocks
        this.storeTouched(this.stageB, SectionVisibility.DOWN, SectionVisibility.UP, nodes, null);
        for (int i = 0; i < this.slots; ++i) {
            this.dilateY(i, this.stageB, this.visible);
        }
    }

    /**
     * Dilates the visible blocks once more inside the given box, the same way {@link #dilate} dilates the reachable
     * blocks. Only sections overlapping the box take part, nothing outside of it can spread into it.
     */
    private void dilateNear(final int[] near) {
        final int nodes = this.width * this.depth * this.height;
        final int[] grown = {near[0] - 1, near[1] - 1, near[2] - 1, near[3] + 1, near[4] + 1, near[5] + 1};
        for (int i = 0; i < this.slots; ++i) {
            if (this.overlaps(this.slotNode[i], grown)) {
                System.arraycopy(this.visible, i * WORDS, this.reach, i * WORDS, WORDS);
            } else {
                Arrays.fill(this.reach, i * WORDS, i * WORDS + WORDS, 0L);
            }
        }
        this.storeTouched(this.reach, SectionVisibility.WEST, SectionVisibility.EAST, nodes, near);
        for (int i = 0; i < this.slots; ++i) {
            if (this.overlaps(this.slotNode[i], grown)) {
                this.dilateX(i, this.reach, this.stageA);
            }
        }
        this.storeTouched(this.stageA, SectionVisibility.NORTH, SectionVisibility.SOUTH, nodes, near);
        for (int i = 0; i < this.slots; ++i) {
            if (this.overlaps(this.slotNode[i], grown)) {
                this.dilateZ(i, this.stageA, this.stageB);
            }
        }
        this.storeTouched(this.stageB, SectionVisibility.DOWN, SectionVisibility.UP, nodes, near);
        for (int i = 0; i < this.slots; ++i) {
            final int node = this.slotNode[i];
            if (!this.overlaps(node, near)) {
                continue;
            }
            this.dilateY(i, this.stageB, this.reach);
            // only the blocks inside the box
            final int column = node / this.height;
            final int blockX = (this.originX + column % this.width) << 4;
            final int blockY = (this.minSectionY + node % this.height) << 4;
            final int blockZ = (this.originZ + column / this.width) << 4;
            final long x = (long) (atLeast(near[0] - blockX) & atMost(near[3] - blockX)) * LANES;
            final int z = atLeast(near[2] - blockZ) & atMost(near[5] - blockZ);
            final int y = atLeast(near[1] - blockY) & atMost(near[4] - blockY);
            final int base = i * WORDS;
            for (int w = 0; w < WORDS; ++w) {
                if ((y >>> (w >>> 2) & 1) != 0) {
                    this.visible[base + w] |= this.reach[base + w] & x & ROWS[(z >>> ((w & 3) << 2)) & 15];
                }
            }
        }
    }

    /**
     * Whether the section overlaps the box (block coordinates).
     */
    private boolean overlaps(final int node, final int[] box) {
        final int column = node / this.height;
        final int blockX = (this.originX + column % this.width) << 4;
        final int blockY = (this.minSectionY + node % this.height) << 4;
        final int blockZ = (this.originZ + column / this.width) << 4;
        return blockX + 15 >= box[0] && blockX <= box[3] && blockY + 15 >= box[1] && blockY <= box[4]
            && blockZ + 15 >= box[2] && blockZ <= box[5];
    }

    /**
     * Stores the neighbors along an axis of every section with blocks on the shared face in the given stage, so the
     * next stage can spread into them. Neighbors of {@link #FULL} sections are always stored.
     */
    private void storeTouched(final long[] stage, final int negative, final int positive, final int nodes, final int @Nullable [] near) {
        for (int i = 0, stored = this.slots; i < stored; ++i) {
            final int node = this.slotNode[i];
            final int base = i * WORDS;
            if (touchesFace(stage, base, negative)) {
                this.storeNeighbor(node, negative, near);
            }
            if (touchesFace(stage, base, positive)) {
                this.storeNeighbor(node, positive, near);
            }
        }
        if (near != null) {
            // the neighbors of full sections were stored by the first dilation already
            return;
        }
        for (int node = 0; node < nodes; ++node) {
            if ((this.state[node] & FULL) != 0) {
                this.storeNeighbor(node, negative, null);
                this.storeNeighbor(node, positive, null);
            }
        }
    }

    private void storeNeighbor(final int node, final int direction, final int @Nullable [] near) {
        final int neighbor = this.neighborInGrid(node, direction);
        if (neighbor >= 0 && (this.state[neighbor] & (STORED | FULL)) == 0 && (near == null || this.overlaps(neighbor, near))) {
            this.store(neighbor);
        }
    }

    /**
     * @return the data of the neighbor in the given stage, {@link #EVERY_BLOCK} for {@link #FULL} sections, or
     * {@code null} if it has none; the base is left in {@link #neighborBase}
     */
    private long @Nullable [] neighborStage(final int node, final int direction, final long[] stage) {
        final int neighbor = this.neighborInGrid(node, direction);
        if (neighbor < 0) {
            return null;
        }
        final byte state = this.state[neighbor];
        if ((state & FULL) != 0) {
            this.neighborBase = 0;
            return EVERY_BLOCK;
        }
        if ((state & STORED) == 0) {
            return null;
        }
        this.neighborBase = this.slot[neighbor] * WORDS;
        return stage;
    }

    private void dilateX(final int slot, final long[] from, final long[] to) {
        final int node = this.slotNode[slot];
        final int base = slot * WORDS;
        for (int w = 0; w < WORDS; ++w) {
            final long r = from[base + w];
            to[base + w] = r | ((r << 1) & ~X0) | ((r >>> 1) & ~X15);
        }
        final long[] west = this.neighborStage(node, SectionVisibility.WEST, from);
        if (west != null) {
            for (int w = 0; w < WORDS; ++w) {
                to[base + w] |= (west[this.neighborBase + w] & X15) >>> 15;
            }
        }
        final long[] east = this.neighborStage(node, SectionVisibility.EAST, from);
        if (east != null) {
            for (int w = 0; w < WORDS; ++w) {
                to[base + w] |= (east[this.neighborBase + w] & X0) << 15;
            }
        }
    }

    private void dilateZ(final int slot, final long[] from, final long[] to) {
        final int node = this.slotNode[slot];
        final int base = slot * WORDS;
        for (int w = 0; w < WORDS; ++w) {
            final long a = from[base + w];
            long b = a | (a << 16) | (a >>> 16);
            final int k = w & 3;
            if (k != 0) {
                b |= from[base + w - 1] >>> 48;
            }
            if (k != 3) {
                b |= from[base + w + 1] << 48;
            }
            to[base + w] = b;
        }
        final long[] north = this.neighborStage(node, SectionVisibility.NORTH, from);
        if (north != null) {
            for (int y = 0; y < 16; ++y) {
                to[base + (y << 2)] |= north[this.neighborBase + (y << 2) + 3] >>> 48;
            }
        }
        final long[] south = this.neighborStage(node, SectionVisibility.SOUTH, from);
        if (south != null) {
            for (int y = 0; y < 16; ++y) {
                to[base + (y << 2) + 3] |= south[this.neighborBase + (y << 2)] << 48;
            }
        }
    }

    private void dilateY(final int slot, final long[] from, final long[] to) {
        final int node = this.slotNode[slot];
        final int base = slot * WORDS;
        for (int w = 0; w < WORDS; ++w) {
            long v = from[base + w];
            if (w >= 4) {
                v |= from[base + w - 4];
            }
            if (w < 60) {
                v |= from[base + w + 4];
            }
            to[base + w] = v;
        }
        final long[] below = this.neighborStage(node, SectionVisibility.DOWN, from);
        if (below != null) {
            for (int k = 0; k < 4; ++k) {
                to[base + k] |= below[this.neighborBase + 60 + k];
            }
        }
        final long[] above = this.neighborStage(node, SectionVisibility.UP, from);
        if (above != null) {
            for (int k = 0; k < 4; ++k) {
                to[base + 60 + k] |= above[this.neighborBase + k];
            }
        }
    }

    /**
     * Whether any block of a section lies on its face in the given direction.
     */
    private static boolean touchesFace(final long[] bits, final int base, final int direction) {
        long any = 0L;
        switch (direction) {
            case SectionVisibility.DOWN -> {
                for (int k = 0; k < 4; ++k) {
                    any |= bits[base + k];
                }
            }
            case SectionVisibility.UP -> {
                for (int k = 0; k < 4; ++k) {
                    any |= bits[base + 60 + k];
                }
            }
            case SectionVisibility.NORTH -> {
                for (int y = 0; y < 16; ++y) {
                    any |= bits[base + (y << 2)] & ROW0;
                }
            }
            case SectionVisibility.SOUTH -> {
                for (int y = 0; y < 16; ++y) {
                    any |= bits[base + (y << 2) + 3] >>> 48;
                }
            }
            case SectionVisibility.WEST -> {
                for (int w = 0; w < WORDS; ++w) {
                    any |= bits[base + w] & X0;
                }
            }
            default -> {
                for (int w = 0; w < WORDS; ++w) {
                    any |= bits[base + w] & X15;
                }
            }
        }
        return any != 0L;
    }
}
