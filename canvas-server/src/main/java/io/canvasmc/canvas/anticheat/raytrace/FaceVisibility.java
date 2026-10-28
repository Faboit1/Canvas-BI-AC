package io.canvasmc.canvas.anticheat.raytrace;

/**
 * Whether a block can be seen from a camera: whether any point of an exposed face of the block that faces the camera
 * has a clear line of sight to it. Each such face is sampled at its center and close to its corners, centers first
 * since they decide most blocks. Rays start at the block and end at the camera: hidden blocks sit in caves and bases,
 * so rays usually leave the block's surroundings through a wall within a few cells.
 */
public final class FaceVisibility {

    /**
     * Face samples are moved this far into the block, so rays start in the block's own cell, which isn't tested.
     */
    private static final double FACE_INSET = 1.0 / 1024.0;
    /**
     * Distance of the corner samples from the edges of a face.
     */
    private static final double CORNER_INSET = 1.0 / 16.0;
    private static final double[] CORNERS = {
        CORNER_INSET, CORNER_INSET,
        CORNER_INSET, 1.0 - CORNER_INSET,
        1.0 - CORNER_INSET, CORNER_INSET,
        1.0 - CORNER_INSET, 1.0 - CORNER_INSET
    };

    private FaceVisibility() {
    }

    /**
     * The cheap part of {@link #isVisibleFrom}, no rays are cast.
     *
     * @return whether a face of the block that faces the camera isn't covered by the block in front of it, or the
     * camera is inside the block
     */
    public static boolean isExposedTo(final BlockOcclusion access, final int x, final int y, final int z,
                                      final double camX, final double camY, final double camZ) {
        final int faceX = camX < x ? -1 : (camX > x + 1 ? 1 : 0);
        final int faceY = camY < y ? -1 : (camY > y + 1 ? 1 : 0);
        final int faceZ = camZ < z ? -1 : (camZ > z + 1 ? 1 : 0);
        return (faceX | faceY | faceZ) == 0
            || faceX != 0 && !access.isOpaque(x + faceX, y, z)
            || faceY != 0 && !access.isOpaque(x, y + faceY, z)
            || faceZ != 0 && !access.isOpaque(x, y, z + faceZ);
    }

    public static boolean isVisibleFrom(final BlockOcclusion access, final int x, final int y, final int z,
                                        final double camX, final double camY, final double camZ) {
        final int faceX = camX < x ? -1 : (camX > x + 1 ? 1 : 0);
        final int faceY = camY < y ? -1 : (camY > y + 1 ? 1 : 0);
        final int faceZ = camZ < z ? -1 : (camZ > z + 1 ? 1 : 0);
        if ((faceX | faceY | faceZ) == 0) {
            // the camera is inside the block
            return true;
        }
        // a face is only visible if the block in front of it doesn't cover it
        final boolean exposedX = faceX != 0 && !access.isOpaque(x + faceX, y, z);
        final boolean exposedY = faceY != 0 && !access.isOpaque(x, y + faceY, z);
        final boolean exposedZ = faceZ != 0 && !access.isOpaque(x, y, z + faceZ);
        if (!exposedX && !exposedY && !exposedZ) {
            return false;
        }

        final double planeX = faceX < 0 ? x + FACE_INSET : x + 1.0 - FACE_INSET;
        final double planeY = faceY < 0 ? y + FACE_INSET : y + 1.0 - FACE_INSET;
        final double planeZ = faceZ < 0 ? z + FACE_INSET : z + 1.0 - FACE_INSET;
        if (exposedX && isClear(access, planeX, y + 0.5, z + 0.5, camX, camY, camZ)
            || exposedY && isClear(access, x + 0.5, planeY, z + 0.5, camX, camY, camZ)
            || exposedZ && isClear(access, x + 0.5, y + 0.5, planeZ, camX, camY, camZ)) {
            return true;
        }
        for (int i = 0; i < CORNERS.length; i += 2) {
            final double u = CORNERS[i];
            final double v = CORNERS[i + 1];
            if (exposedX && isClear(access, planeX, y + u, z + v, camX, camY, camZ)
                || exposedY && isClear(access, x + u, planeY, z + v, camX, camY, camZ)
                || exposedZ && isClear(access, x + u, y + v, planeZ, camX, camY, camZ)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClear(final BlockOcclusion access, final double x, final double y, final double z,
                                   final double camX, final double camY, final double camZ) {
        // the block itself doesn't occlude, but the camera's cell does: a client whose camera is inside an opaque
        // block only renders that block, and glitching the head into a wall must not reveal what's behind it
        return VoxelRaycaster.isClear(access, x, y, z, camX, camY, camZ, false, true);
    }
}
