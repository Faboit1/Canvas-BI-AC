package io.canvasmc.canvas.anticheat.freecam;

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
 * Instances hold reusable working memory and are not thread safe.
 */
public final class CellVisibility {

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
    private int neighborBase;
    private final long[] layerSky = new long[WORDS];

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
     */
    public void compute(final Sections sections, final int width, final int depth, final int height,
                        final int originX, final int originZ, final int minSectionY, final long[] domain,
                        final boolean sky, final int[] cameras, final int cameraCount, final int[] box, final int @Nullable [] near) {
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
        }
        final int base = index * WORDS;
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
