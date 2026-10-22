package io.canvasmc.canvas.anticheat.antiesp;

import io.canvasmc.canvas.anticheat.raytrace.OcclusionCache;
import io.canvasmc.canvas.anticheat.raytrace.VoxelRaycaster;

/**
 * Decides whether any part of an axis aligned box can be seen from a set of camera positions, by tracing rays
 * through the voxel grid to sample points of the box.
 * <p>
 * The sample points are the centre and the corners of the box, pulled slightly inwards so they never lie exactly on
 * a block boundary. Boxes larger than {@link #GRID_THRESHOLD} blocks on an axis additionally get a grid of points
 * with at most {@link #GRID_SPACING} blocks between them on each face turned towards the camera (the idea of the MIT
 * licensed OcclusionCulling library), so a large entity seen through a small gap is not missed. The cell containing
 * a sample point is never treated as an occluder, since the point belongs to the entity itself.
 * <p>
 * Stateless, safe to use from any thread with the {@link OcclusionCache} of that thread.
 */
final class BoxOcclusion {

    static final double INSET = 0.05;
    static final double GRID_THRESHOLD = 2.0;
    static final double GRID_SPACING = 1.0;
    static final int MAX_GRID = 8;
    /**
     * Upper bound of the amount of points {@link #sample} produces.
     */
    static final int MAX_POINTS = 1 + 8 + 3 * MAX_GRID * MAX_GRID;

    /**
     * Corner visiting order as bit masks of "far side" axes (bit 0 = x, 1 = y, 2 = z): nearest corner first, the
     * corner on the far side of all three axes last.
     */
    private static final int[] CORNER_ORDER = {0, 1, 2, 4, 3, 5, 6, 7};

    private BoxOcclusion() {
    }

    /**
     * @param cache   the block access of the current pass
     * @param cameras camera positions as {@code x, y, z} triples. The cells containing them must not be opaque, as
     *                they are not tested
     * @param boxes   boxes as {@code minX, minY, minZ, maxX, maxY, maxZ}
     * @param points  scratch buffer of at least {@code 3 * MAX_POINTS} elements
     * @return whether a ray from any camera reaches any sample point of the box
     */
    static boolean isVisible(final OcclusionCache cache, final double[] cameras, final int cameraCount,
                             final double[] boxes, final int boxOffset, final double[] points) {
        for (int camera = 0; camera < cameraCount; ++camera) {
            final double camX = cameras[camera * 3];
            final double camY = cameras[camera * 3 + 1];
            final double camZ = cameras[camera * 3 + 2];
            final int count = sample(camX, camY, camZ, boxes, boxOffset, points);
            if (count < 0) {
                return true;
            }
            for (int i = 0; i < count; ++i) {
                if (VoxelRaycaster.isClear(cache, camX, camY, camZ, points[i * 3], points[i * 3 + 1], points[i * 3 + 2], false, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the points of the box that rays from the camera are traced to into {@code out}, most promising first.
     *
     * @return the amount of points written as {@code x, y, z} triples, or {@code -1} if the camera is inside the box
     */
    static int sample(final double camX, final double camY, final double camZ,
                      final double[] boxes, final int boxOffset, final double[] out) {
        final double minX = boxes[boxOffset];
        final double minY = boxes[boxOffset + 1];
        final double minZ = boxes[boxOffset + 2];
        final double maxX = boxes[boxOffset + 3];
        final double maxY = boxes[boxOffset + 4];
        final double maxZ = boxes[boxOffset + 5];

        // which side of the box the camera is on per axis: -1 below, 0 within, 1 above
        final int sideX = camX < minX ? -1 : (camX > maxX ? 1 : 0);
        final int sideY = camY < minY ? -1 : (camY > maxY ? 1 : 0);
        final int sideZ = camZ < minZ ? -1 : (camZ > maxZ ? 1 : 0);
        if (sideX == 0 && sideY == 0 && sideZ == 0) {
            return -1;
        }

        // pull the points inwards, but never past the middle of thin boxes such as item frames
        final double insetX = Math.min(INSET, (maxX - minX) * 0.25);
        final double insetY = Math.min(INSET, (maxY - minY) * 0.25);
        final double insetZ = Math.min(INSET, (maxZ - minZ) * 0.25);
        final double nearX = sideX > 0 ? maxX - insetX : minX + insetX;
        final double nearY = sideY > 0 ? maxY - insetY : minY + insetY;
        final double nearZ = sideZ > 0 ? maxZ - insetZ : minZ + insetZ;
        final double farX = sideX > 0 ? minX + insetX : maxX - insetX;
        final double farY = sideY > 0 ? minY + insetY : maxY - insetY;
        final double farZ = sideZ > 0 ? minZ + insetZ : maxZ - insetZ;

        int count = 0;
        count = put(out, count, (minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);

        // seen from outside on every axis, the corner on the far side of all three is hidden behind the box itself
        final int corners = sideX != 0 && sideY != 0 && sideZ != 0 ? 7 : 8;
        for (int i = 0; i < corners; ++i) {
            final int corner = CORNER_ORDER[i];
            count = put(out, count,
                (corner & 1) == 0 ? nearX : farX,
                (corner & 2) == 0 ? nearY : farY,
                (corner & 4) == 0 ? nearZ : farZ
            );
        }

        final double extentX = maxX - minX;
        final double extentY = maxY - minY;
        final double extentZ = maxZ - minZ;
        if (extentX <= GRID_THRESHOLD && extentY <= GRID_THRESHOLD && extentZ <= GRID_THRESHOLD) {
            return count;
        }

        // grids on the faces turned towards the camera, the corners of each grid were added above
        if (sideX != 0 && (extentY > GRID_THRESHOLD || extentZ > GRID_THRESHOLD)) {
            final int countY = gridSize(extentY - 2.0 * insetY);
            final int countZ = gridSize(extentZ - 2.0 * insetZ);
            for (int y = 0; y < countY; ++y) {
                final double pointY = lerp(minY + insetY, maxY - insetY, y, countY);
                for (int z = 0; z < countZ; ++z) {
                    if ((y == 0 || y == countY - 1) && (z == 0 || z == countZ - 1)) {
                        continue;
                    }
                    count = put(out, count, nearX, pointY, lerp(minZ + insetZ, maxZ - insetZ, z, countZ));
                }
            }
        }
        if (sideY != 0 && (extentX > GRID_THRESHOLD || extentZ > GRID_THRESHOLD)) {
            final int countX = gridSize(extentX - 2.0 * insetX);
            final int countZ = gridSize(extentZ - 2.0 * insetZ);
            for (int x = 0; x < countX; ++x) {
                final double pointX = lerp(minX + insetX, maxX - insetX, x, countX);
                for (int z = 0; z < countZ; ++z) {
                    if ((x == 0 || x == countX - 1) && (z == 0 || z == countZ - 1)) {
                        continue;
                    }
                    count = put(out, count, pointX, nearY, lerp(minZ + insetZ, maxZ - insetZ, z, countZ));
                }
            }
        }
        if (sideZ != 0 && (extentX > GRID_THRESHOLD || extentY > GRID_THRESHOLD)) {
            final int countX = gridSize(extentX - 2.0 * insetX);
            final int countY = gridSize(extentY - 2.0 * insetY);
            for (int x = 0; x < countX; ++x) {
                final double pointX = lerp(minX + insetX, maxX - insetX, x, countX);
                for (int y = 0; y < countY; ++y) {
                    if ((x == 0 || x == countX - 1) && (y == 0 || y == countY - 1)) {
                        continue;
                    }
                    count = put(out, count, pointX, lerp(minY + insetY, maxY - insetY, y, countY), nearZ);
                }
            }
        }
        return count;
    }

    private static int gridSize(final double extent) {
        return Math.clamp((int) Math.ceil(extent / GRID_SPACING) + 1, 2, MAX_GRID);
    }

    private static double lerp(final double from, final double to, final int step, final int count) {
        return from + (to - from) * step / (count - 1);
    }

    private static int put(final double[] out, final int count, final double x, final double y, final double z) {
        out[count * 3] = x;
        out[count * 3 + 1] = y;
        out[count * 3 + 2] = z;
        return count + 1;
    }
}
