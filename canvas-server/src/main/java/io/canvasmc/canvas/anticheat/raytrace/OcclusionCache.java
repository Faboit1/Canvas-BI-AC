package io.canvasmc.canvas.anticheat.raytrace;

import java.util.Arrays;
import java.util.Objects;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Remembers which blocks around a camera are opaque while the rays of one visibility pass are traced.
 * <p>
 * All rays of a pass start at the same few camera positions, so the cells near the cameras are visited over and
 * over, and that is also where most rays end when the player is enclosed. Reading those cells from this cache
 * instead of the chunk's paletted containers roughly halves the cost of a pass. Cells outside the cached region are
 * read directly.
 * <p>
 * One instance per thread ({@link #get}), reused for every pass. Entries are stamped with a generation instead of
 * being cleared, so starting a pass is free. Pass it to {@link VoxelRaycaster} in place of the access.
 */
public final class OcclusionCache implements BlockOcclusion {

    private static final int SIZE_SHIFT = 6;
    private static final int HEIGHT_SHIFT = 5;
    /**
     * Cached cells per horizontal axis, centred on the camera.
     */
    private static final int SIZE = 1 << SIZE_SHIFT;
    /**
     * Cached cells on the vertical axis, centred on the camera.
     */
    private static final int HEIGHT = 1 << HEIGHT_SHIFT;
    private static final int OPAQUE = 1;
    private static final int GENERATION_SHIFT = 1;
    private static final int MAX_GENERATION = 0xFF >>> GENERATION_SHIFT;

    private static final ThreadLocal<OcclusionCache> CACHES = ThreadLocal.withInitial(OcclusionCache::new);

    /**
     * Per cell: the generation it was read in, and whether it is opaque.
     */
    private final byte[] cells = new byte[SIZE * SIZE * HEIGHT];
    private int generation;
    private @Nullable OcclusionAccess access;
    private int originX;
    private int originY;
    private int originZ;

    private OcclusionCache() {
    }

    /**
     * @return the cache of the current thread, prepared for a pass reading through the given access around the
     * given camera position
     */
    public static OcclusionCache get(final OcclusionAccess access, final double cameraX, final double cameraY, final double cameraZ) {
        final OcclusionCache cache = CACHES.get();
        cache.begin(access, cameraX, cameraY, cameraZ);
        return cache;
    }

    private void begin(final OcclusionAccess access, final double cameraX, final double cameraY, final double cameraZ) {
        if (++this.generation > MAX_GENERATION) {
            Arrays.fill(this.cells, (byte) 0);
            this.generation = 1;
        }
        this.access = access;
        this.originX = Mth.floor(cameraX) - SIZE / 2;
        this.originY = Mth.floor(cameraY) - HEIGHT / 2;
        this.originZ = Mth.floor(cameraZ) - SIZE / 2;
    }

    /**
     * Same as {@link OcclusionAccess#isOpaque}.
     */
    @Override
    public boolean isOpaque(final int x, final int y, final int z) {
        final OcclusionAccess access = Objects.requireNonNull(this.access);
        final int localX = x - this.originX;
        final int localY = y - this.originY;
        final int localZ = z - this.originZ;
        if (((localX | localZ) >>> SIZE_SHIFT | localY >>> HEIGHT_SHIFT) != 0) {
            return access.isOpaque(x, y, z);
        }
        final int index = (((localY << SIZE_SHIFT) | localZ) << SIZE_SHIFT) | localX;
        final int cell = this.cells[index] & 0xFF;
        if (cell >>> GENERATION_SHIFT == this.generation) {
            return (cell & OPAQUE) != 0;
        }
        final boolean opaque = access.isOpaque(x, y, z);
        this.cells[index] = (byte) (this.generation << GENERATION_SHIFT | (opaque ? OPAQUE : 0));
        return opaque;
    }
}
