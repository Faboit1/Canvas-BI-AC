package io.canvasmc.canvas.anticheat.raytrace;

import net.minecraft.util.Mth;

/**
 * Exact voxel traversal (Amanatides &amp; Woo, "A Fast Voxel Traversal Algorithm for Ray Tracing") against
 * {@link BlockOcclusion}, usually an {@link OcclusionAccess} or the {@link OcclusionCache} of a pass.
 * <p>
 * Unlike fixed step ray marching this visits every cell the segment passes through, so thin walls and diagonal
 * corners are never skipped. The traversal steps one axis at a time, which means a segment passing exactly
 * through an edge between two occluding blocks is blocked, just like it is visually.
 */
public final class VoxelRaycaster {

    private VoxelRaycaster() {
    }

    /**
     * Tests whether the segment between the two points is free of occluding blocks.
     *
     * @param testStart whether the cell containing the start point is tested
     * @param testEnd whether the cell containing the end point is tested
     * @return {@code true} if none of the tested cells occlude
     */
    public static boolean isClear(final BlockOcclusion access,
                                  final double x0, final double y0, final double z0,
                                  final double x1, final double y1, final double z1,
                                  final boolean testStart, final boolean testEnd) {
        int x = Mth.floor(x0);
        int y = Mth.floor(y0);
        int z = Mth.floor(z0);

        if (testStart && access.isOpaque(x, y, z)) {
            return false;
        }

        final int endX = Mth.floor(x1);
        final int endY = Mth.floor(y1);
        final int endZ = Mth.floor(z1);

        int remainingX = Math.abs(endX - x);
        int remainingY = Math.abs(endY - y);
        int remainingZ = Math.abs(endZ - z);
        int remaining = remainingX + remainingY + remainingZ;
        if (remaining == 0) {
            return !(testEnd && !testStart && access.isOpaque(x, y, z));
        }

        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double dz = z1 - z0;

        final int stepX = dx >= 0.0 ? 1 : -1;
        final int stepY = dy >= 0.0 ? 1 : -1;
        final int stepZ = dz >= 0.0 ? 1 : -1;

        // t is the fraction of the segment, tDelta the fraction needed to cross one cell on that axis
        final double tDeltaX = dx == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        final double tDeltaY = dy == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        final double tDeltaZ = dz == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        double tMaxX = dx == 0.0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1.0 - x0) : (x0 - x)) * tDeltaX;
        double tMaxY = dy == 0.0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1.0 - y0) : (y0 - y)) * tDeltaY;
        double tMaxZ = dz == 0.0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1.0 - z0) : (z0 - z)) * tDeltaZ;

        for (;;) {
            // only step axes that still need to move, so rounding can never make us miss the end cell
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

            if (--remaining == 0) {
                return !(testEnd && access.isOpaque(x, y, z));
            }
            if (access.isOpaque(x, y, z)) {
                return false;
            }
        }
    }

    /**
     * Walks from the origin along the given unit direction and returns the distance at which the first
     * occluding cell is entered, or {@code maxDistance} if there is none. The cell containing the origin is
     * not tested. Used to place third person cameras, which the client pulls in front of blocks.
     */
    public static double clipDistance(final BlockOcclusion access,
                                      final double x0, final double y0, final double z0,
                                      final double dirX, final double dirY, final double dirZ,
                                      final double maxDistance) {
        int x = Mth.floor(x0);
        int y = Mth.floor(y0);
        int z = Mth.floor(z0);

        final int stepX = dirX >= 0.0 ? 1 : -1;
        final int stepY = dirY >= 0.0 ? 1 : -1;
        final int stepZ = dirZ >= 0.0 ? 1 : -1;

        final double tDeltaX = dirX == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirX);
        final double tDeltaY = dirY == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirY);
        final double tDeltaZ = dirZ == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirZ);

        double tMaxX = dirX == 0.0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1.0 - x0) : (x0 - x)) * tDeltaX;
        double tMaxY = dirY == 0.0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1.0 - y0) : (y0 - y)) * tDeltaY;
        double tMaxZ = dirZ == 0.0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1.0 - z0) : (z0 - z)) * tDeltaZ;

        for (;;) {
            final double t;
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    t = tMaxX;
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    t = tMaxZ;
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else if (tMaxY < tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (t >= maxDistance) {
                return maxDistance;
            }
            if (access.isOpaque(x, y, z)) {
                return t;
            }
        }
    }
}
