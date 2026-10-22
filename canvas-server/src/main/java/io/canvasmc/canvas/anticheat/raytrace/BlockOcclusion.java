package io.canvasmc.canvas.anticheat.raytrace;

/**
 * Tells whether blocks occlude vision, see {@link OcclusionAccess#isOpaque}.
 */
public interface BlockOcclusion {

    boolean isOpaque(int x, int y, int z);
}
