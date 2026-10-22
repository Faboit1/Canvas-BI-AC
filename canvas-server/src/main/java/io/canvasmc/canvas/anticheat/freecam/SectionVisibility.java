package io.canvasmc.canvas.anticheat.freecam;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Server side replica of the client's per-section visibility graph ({@code VisGraph}): which faces of a 16³ chunk
 * section are connected through blocks that don't render as full opaque cubes.
 * <p>
 * A visibility set is stored as 36 bits, bit {@code from * 6 + to} telling whether the face {@code from} sees the
 * face {@code to}, faces being indexed like {@link net.minecraft.core.Direction#ordinal()}. Sets are symmetric.
 * <p>
 * The set of a section is cached in the section itself and invalidated through a version counter that
 * {@link LevelChunkSection#setBlockState} bumps when a block changes between opaque and not opaque. It is computed
 * lazily by whichever anticheat worker needs it first, from racy reads of the block palette. Readers race the owning
 * region thread: a concurrent change is either seen, or bumps the version again so the next reader recomputes.
 */
public final class SectionVisibility {

    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    public static final long NONE = 0L;
    public static final long ALL = (1L << 36) - 1L;

    private static final int SECTION_VOLUME = 16 * 16 * 16;
    /**
     * The client treats sections with fewer opaque blocks than this as fully connected.
     */
    private static final int MIN_OPAQUE_FOR_GRAPH = 256;

    private static final int STAMP_SHIFT = 36;
    private static final int VERSION_MASK = (1 << 27) - 1;
    private static final int STAMP_VALID = 1 << 27;

    /**
     * Visibility set of every combination of faces, indexed by the face bit set.
     */
    private static final long[] PAIRS = new long[64];
    /**
     * Faces touched by each block index of a section, 0 for interior blocks.
     */
    private static final byte[] EDGE_FACES = new byte[SECTION_VOLUME];
    /**
     * The indices of all blocks touching at least one face.
     */
    private static final short[] EDGE_INDICES;

    private static final VarHandle OPACITY_VERSION;

    private static volatile boolean @Nullable [] globalOpaque;

    static {
        for (int faces = 0; faces < 64; ++faces) {
            long set = 0L;
            for (int from = 0; from < 6; ++from) {
                if ((faces & (1 << from)) == 0) {
                    continue;
                }
                for (int to = 0; to < 6; ++to) {
                    if ((faces & (1 << to)) != 0) {
                        set |= 1L << (from * 6 + to);
                    }
                }
            }
            PAIRS[faces] = set;
        }

        int edges = 0;
        for (int index = 0; index < SECTION_VOLUME; ++index) {
            final int x = index & 15;
            final int z = (index >>> 4) & 15;
            final int y = index >>> 8;
            int faces = 0;
            if (x == 0) {
                faces |= 1 << WEST;
            } else if (x == 15) {
                faces |= 1 << EAST;
            }
            if (y == 0) {
                faces |= 1 << DOWN;
            } else if (y == 15) {
                faces |= 1 << UP;
            }
            if (z == 0) {
                faces |= 1 << NORTH;
            } else if (z == 15) {
                faces |= 1 << SOUTH;
            }
            EDGE_FACES[index] = (byte) faces;
            if (faces != 0) {
                ++edges;
            }
        }
        EDGE_INDICES = new short[edges];
        for (int index = 0, i = 0; index < SECTION_VOLUME; ++index) {
            if (EDGE_FACES[index] != 0) {
                EDGE_INDICES[i++] = (short) index;
            }
        }

        try {
            OPACITY_VERSION = MethodHandles.lookup().findVarHandle(LevelChunkSection.class, "canvas$opacityVersion", int.class);
        } catch (final ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private SectionVisibility() {
    }

    /**
     * Working memory of the flood fill, owned by one thread.
     */
    public static final class Scratch {
        final long[] opaque = new long[SECTION_VOLUME / 64];
        final int[] queue = new int[SECTION_VOLUME];
        boolean[] paletteOpaque = new boolean[256];
    }

    /**
     * @return whether the face {@code from} sees the face {@code to}
     */
    public static boolean connects(final long set, final int from, final int to) {
        return (set >>> (from * 6 + to) & 1L) != 0L;
    }

    /**
     * @return the bit set of faces that see the given face
     */
    public static int facesSeeing(final long set, final int face) {
        return (int) (set >>> (face * 6)) & 63;
    }

    /**
     * Called by {@link LevelChunkSection#setBlockState} when a block changes between opaque and not opaque.
     * The release store pairs with the acquire load in {@link #get}, so a reader that sees the new version also
     * sees the new block.
     */
    public static void onOpacityChange(final LevelChunkSection section) {
        OPACITY_VERSION.setRelease(section, section.canvas$opacityVersion + 1);
    }

    /**
     * @return the opacity version of the section, which only ever grows while the section object lives
     */
    public static int version(final LevelChunkSection section) {
        return (int) OPACITY_VERSION.getAcquire(section);
    }

    /**
     * Returns the visibility set of the section, computing and caching it if the blocks changed since it was last
     * computed. May be called from any thread.
     */
    public static long get(final LevelChunkSection section, final Scratch scratch) {
        return get(section, scratch, version(section));
    }

    /**
     * Same as {@link #get(LevelChunkSection, Scratch)}, for a version the caller read with {@link #version} right
     * before. The returned set reflects the blocks of at least that version, so a caller that remembers the version
     * notices any later change by comparing it with the current one.
     */
    public static long get(final LevelChunkSection section, final Scratch scratch, final int version) {
        final long stamp = (version & VERSION_MASK) | STAMP_VALID;
        final long cached = section.canvas$visibility;
        if (cached >>> STAMP_SHIFT == stamp) {
            return cached & ALL;
        }
        final long set = compute(section, scratch);
        section.canvas$visibility = stamp << STAMP_SHIFT | set;
        return set;
    }

    private static long compute(final LevelChunkSection section, final Scratch scratch) {
        if (section.hasOnlyAir()) {
            return ALL;
        }
        final PalettedContainer.Data<BlockState> data = section.getStates().data;
        final Palette<BlockState> palette = data.palette();
        final BitStorage storage = data.storage();
        final int bits = storage.getBits();

        final boolean[] opaqueById;
        if (palette instanceof GlobalPalette<BlockState>) {
            opaqueById = globalOpaque();
        } else {
            final int size = palette.getSize();
            if (bits == 0 || size == 1) {
                final BlockState state = readPalette(palette, 0);
                return state != null && state.isSolidRender() ? NONE : ALL;
            }
            boolean[] local = scratch.paletteOpaque;
            if (local.length < size) {
                scratch.paletteOpaque = local = new boolean[size];
            }
            boolean anyOpaque = false;
            boolean allOpaque = true;
            for (int i = 0; i < size; ++i) {
                final BlockState state = readPalette(palette, i);
                final boolean opaque = state != null && state.isSolidRender();
                local[i] = opaque;
                anyOpaque |= opaque;
                allOpaque &= opaque;
            }
            if (!anyOpaque) {
                return ALL;
            }
            if (allOpaque) {
                return NONE;
            }
            // entries added concurrently are unknown and count as not opaque, which only adds connections
            for (int i = size; i < local.length; ++i) {
                local[i] = false;
            }
            opaqueById = local;
        }

        final long[] opaque = scratch.opaque;
        if (!decode(storage.getRaw(), bits, opaqueById, opaque)) {
            // storage of another size than expected, raced a resize: count everything as see-through
            return ALL;
        }
        return resolve(opaque, scratch.queue);
    }

    private static @Nullable BlockState readPalette(final Palette<BlockState> palette, final int index) {
        try {
            return palette.valueFor(index);
        } catch (final RuntimeException ignored) {
            // raced a palette change on the owning region thread
            return null;
        }
    }

    private static boolean[] globalOpaque() {
        boolean[] ret = globalOpaque;
        if (ret == null) {
            final int size = Block.BLOCK_STATE_REGISTRY.size();
            ret = new boolean[size];
            for (int id = 0; id < size; ++id) {
                final BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                ret[id] = state != null && state.isSolidRender();
            }
            globalOpaque = ret;
        }
        return ret;
    }

    /**
     * Unpacks the opacity of every block of a paletted storage into a bit set indexed like the storage.
     *
     * @return false if the storage doesn't hold exactly one section of data
     */
    private static boolean decode(final long[] raw, final int bits, final boolean[] opaqueById, final long[] out) {
        final int perLong = 64 / bits;
        if (raw.length != (SECTION_VOLUME + perLong - 1) / perLong) {
            return false;
        }
        final long mask = (1L << bits) - 1L;
        final int ids = opaqueById.length;
        long word = 0L;
        int left = 0;
        int rawIndex = 0;
        for (int i = 0; i < SECTION_VOLUME / 64; ++i) {
            long opaque = 0L;
            for (int bit = 0; bit < 64; ++bit) {
                if (left == 0) {
                    word = raw[rawIndex++];
                    left = perLong;
                }
                final int id = (int) (word & mask);
                word >>>= bits;
                --left;
                if (id < ids && opaqueById[id]) {
                    opaque |= 1L << bit;
                }
            }
            out[i] = opaque;
        }
        return true;
    }

    /**
     * Computes the visibility set of a section exactly like the client does, from the opacity of its blocks.
     * The given bit set is indexed by {@code x | z << 4 | y << 8} and is used as the visited set of the flood fill,
     * so it is destroyed.
     *
     * @param opaque 64 longs holding one bit per block
     * @param queue  scratch space for at least 4096 ints
     */
    public static long resolve(final long[] opaque, final int[] queue) {
        int opaqueCount = 0;
        for (int i = 0; i < SECTION_VOLUME / 64; ++i) {
            opaqueCount += Long.bitCount(opaque[i]);
        }
        if (opaqueCount < MIN_OPAQUE_FOR_GRAPH) {
            return ALL;
        }
        if (opaqueCount == SECTION_VOLUME) {
            return NONE;
        }

        long set = NONE;
        for (final short edge : EDGE_INDICES) {
            final int start = edge;
            if ((opaque[start >>> 6] & (1L << start)) != 0L) {
                continue;
            }
            set |= PAIRS[floodFill(opaque, queue, start)];
            if (set == ALL) {
                break;
            }
        }
        return set;
    }

    /**
     * Marks every block connected to the start block as visited.
     *
     * @return the faces touched by the connected blocks
     */
    private static int floodFill(final long[] visited, final int[] queue, final int start) {
        visited[start >>> 6] |= 1L << start;
        queue[0] = start;
        int head = 0;
        int tail = 1;
        int faces = 0;
        while (head < tail) {
            final int index = queue[head++];
            faces |= EDGE_FACES[index];
            final int x = index & 15;
            final int z = index & (15 << 4);
            final int y = index & (15 << 8);
            if (x != 0) {
                tail = visit(visited, queue, tail, index - 1);
            }
            if (x != 15) {
                tail = visit(visited, queue, tail, index + 1);
            }
            if (z != 0) {
                tail = visit(visited, queue, tail, index - 16);
            }
            if (z != 15 << 4) {
                tail = visit(visited, queue, tail, index + 16);
            }
            if (y != 0) {
                tail = visit(visited, queue, tail, index - 256);
            }
            if (y != 15 << 8) {
                tail = visit(visited, queue, tail, index + 256);
            }
        }
        return faces;
    }

    private static int visit(final long[] visited, final int[] queue, final int tail, final int index) {
        final long bit = 1L << index;
        final int word = index >>> 6;
        if ((visited[word] & bit) != 0L) {
            return tail;
        }
        visited[word] |= bit;
        queue[tail] = index;
        return tail + 1;
    }
}
