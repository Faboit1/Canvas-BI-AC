package io.canvasmc.canvas.anticheat.freecam;

import java.util.Arrays;

/**
 * A superset of the client's section occlusion graph ({@code SectionOcclusionGraph}): the chunk sections a vanilla
 * client could render with its camera anywhere inside a start box.
 * <p>
 * The client walks the sections breadth first from the camera section. It moves from a section to its neighbor
 * through face {@code d} only if it never travelled in the opposite direction of {@code d} on the way there (paths
 * are monotone per axis), and, except in the camera section, only if the visibility set of the section connects a
 * face it was entered through to {@code d}. The client merges the entry faces of all arrivals and keeps the travel
 * directions of the first one, so this replica keeps, per section, the union of all entry faces and the
 * intersection of all travel direction sets seen so far, and expands a section again whenever either becomes more
 * permissive. Both only change monotonically (a monotone path has at most 3 directions), so every section is
 * expanded at most 9 times. Every section of the start box is a start section that may be left through every face.
 * Frustum culling and the client's distant line check only remove sections, so they are ignored.
 * <p>
 * A straight unobstructed line of sight from the start box never reverses its direction on an axis and passes
 * from face to face through connected see-through blocks, so every section such a line reaches is in the result.
 * <p>
 * Sections are addressed in a grid of {@code width * depth} columns of {@code height} sections, columns indexed by
 * {@code z * width + x}. Instances hold reusable working memory and are not thread safe.
 */
public final class OcclusionGraph {

    /**
     * Supplies the sections of the grid.
     */
    public interface Sections {

        /**
         * @return whether the column exists, sections of missing columns are never entered
         */
        boolean hasColumn(int column);

        /**
         * @return the visibility set of a section of an existing column, see {@link SectionVisibility}
         */
        long visibility(int column, int sectionIndex);
    }

    private static final int FREE = 1 << 6;
    private static final int QUEUED = 1 << 7;

    /**
     * Per section: the faces it was entered through, {@link #FREE} for start sections, {@link #QUEUED}. 0 means
     * not reached.
     */
    private byte[] entry = new byte[0];
    /**
     * Per reached section: the directions travelled on every path that reached it so far.
     */
    private byte[] travel = new byte[0];
    private int[] queue = new int[0];
    private int height;

    /**
     * Computes the potentially visible sections. The start box may extend outside the grid.
     *
     * @param seedTop    also start from the top section of every column, like the client does when the camera is
     *                   above the world
     * @param seedBottom also start from the bottom section of every column
     * @param out        receives one bit per section index for every column, cleared first
     * @return the amount of section expansions, for statistics
     */
    public int compute(final Sections sections, final int width, final int depth, final int height,
                       final int startMinX, final int startMinY, final int startMinZ,
                       final int startMaxX, final int startMaxY, final int startMaxZ,
                       final boolean seedTop, final boolean seedBottom, final long[] out) {
        if (width > 256 || depth > 256 || height > 64) {
            throw new IllegalArgumentException("Grid too large: " + width + "x" + depth + "x" + height);
        }
        final int columns = width * depth;
        final int nodes = columns * height;
        this.height = height;
        if (this.entry.length < nodes) {
            this.entry = new byte[nodes];
            this.travel = new byte[nodes];
            this.queue = new int[nodes];
        }
        final byte[] entry = this.entry;
        final byte[] travel = this.travel;
        final int[] queue = this.queue;
        Arrays.fill(entry, 0, nodes, (byte) 0);
        Arrays.fill(out, 0, columns, 0L);

        int head = 0;
        int size = 0;
        for (int z = Math.max(0, startMinZ), maxZ = Math.min(depth - 1, startMaxZ); z <= maxZ; ++z) {
            for (int x = Math.max(0, startMinX), maxX = Math.min(width - 1, startMaxX); x <= maxX; ++x) {
                final int column = z * width + x;
                if (!sections.hasColumn(column)) {
                    continue;
                }
                for (int y = Math.max(0, startMinY), maxY = Math.min(height - 1, startMaxY); y <= maxY; ++y) {
                    size = this.seed(column, x, z, y, size, out);
                }
            }
        }
        if (seedTop || seedBottom) {
            for (int z = 0; z < depth; ++z) {
                for (int x = 0; x < width; ++x) {
                    final int column = z * width + x;
                    if (!sections.hasColumn(column)) {
                        continue;
                    }
                    if (seedTop) {
                        size = this.seed(column, x, z, height - 1, size, out);
                    }
                    if (seedBottom) {
                        size = this.seed(column, x, z, 0, size, out);
                    }
                }
            }
        }

        int expansions = 0;
        while (size != 0) {
            final int packed = queue[head];
            if (++head == nodes) {
                head = 0;
            }
            --size;
            ++expansions;

            final int x = packed & 0xFF;
            final int z = (packed >>> 8) & 0xFF;
            final int y = packed >>> 16;
            final int column = z * width + x;
            final int node = column * height + y;
            final int faces = entry[node] & ~QUEUED & 0xFF;
            entry[node] = (byte) faces;
            final int directions = travel[node];
            // the client never turns back on an axis
            int exits = ~(((directions & 0b010101) << 1) | ((directions & 0b101010) >>> 1)) & 63;
            if ((faces & FREE) == 0) {
                final long visibility = sections.visibility(column, y);
                int seen = 0;
                for (int entered = faces; entered != 0; entered &= entered - 1) {
                    seen |= SectionVisibility.facesSeeing(visibility, Integer.numberOfTrailingZeros(entered));
                }
                exits &= seen;
            }

            for (; exits != 0; exits &= exits - 1) {
                final int direction = Integer.numberOfTrailingZeros(exits);
                int nx = x;
                int ny = y;
                int nz = z;
                switch (direction) {
                    case SectionVisibility.DOWN -> --ny;
                    case SectionVisibility.UP -> ++ny;
                    case SectionVisibility.NORTH -> --nz;
                    case SectionVisibility.SOUTH -> ++nz;
                    case SectionVisibility.WEST -> --nx;
                    default -> ++nx;
                }
                if (nx < 0 || nx >= width || nz < 0 || nz >= depth || ny < 0 || ny >= height) {
                    continue;
                }
                final int neighborColumn = nz * width + nx;
                if (neighborColumn != column && !sections.hasColumn(neighborColumn)) {
                    continue;
                }
                final int neighbor = neighborColumn * height + ny;
                final int entryFace = 1 << (direction ^ 1);
                final int neighborDirections = directions | (1 << direction);
                final int old = entry[neighbor] & 0xFF;
                if (old == 0) {
                    entry[neighbor] = (byte) (entryFace | QUEUED);
                    travel[neighbor] = (byte) neighborDirections;
                    out[neighborColumn] |= 1L << ny;
                } else if ((old & FREE) == 0) {
                    final int oldDirections = travel[neighbor];
                    final int newDirections = oldDirections & neighborDirections;
                    final int faces2 = old | entryFace;
                    if (faces2 == old && newDirections == oldDirections) {
                        continue;
                    }
                    travel[neighbor] = (byte) newDirections;
                    entry[neighbor] = (byte) (faces2 | QUEUED);
                    if ((old & QUEUED) != 0) {
                        continue;
                    }
                } else {
                    continue;
                }
                int tail = head + size;
                if (tail >= nodes) {
                    tail -= nodes;
                }
                queue[tail] = nx | nz << 8 | ny << 16;
                ++size;
            }
        }
        return expansions;
    }

    private int seed(final int column, final int x, final int z, final int y, final int size, final long[] out) {
        if ((out[column] & (1L << y)) != 0L) {
            return size;
        }
        final int node = column * this.height + y;
        this.entry[node] = (byte) (FREE | QUEUED);
        this.travel[node] = 0;
        this.queue[size] = x | z << 8 | y << 16;
        out[column] |= 1L << y;
        return size + 1;
    }
}
