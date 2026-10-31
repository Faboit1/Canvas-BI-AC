package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.anticheat.raytrace.BlockOcclusion;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.VoxelRaycaster;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * An immutable copy of everything about a player's point of view that the asynchronous visibility work needs.
 * Taken on the player's region thread right before a job is submitted.
 * <p>
 * The only mutable part is the lazily computed third person camera positions. The jobs of all modules share the
 * snapshot, whichever job needs them first computes them, possibly more than one at the same time.
 */
public final class CameraSnapshot {

    /**
     * Distance the client keeps between a third person camera and the block it collides with.
     */
    private static final double CAMERA_CLIP_MARGIN = 0.1;
    /**
     * Directions of the points around the eyes, see {@link #pointsAround}: horizontal at 45 degree steps, and up.
     */
    private static final double[] AROUND;

    static {
        final double diagonal = Math.sqrt(0.5);
        AROUND = new double[] {
            1.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0,
            diagonal, 0.0, diagonal, diagonal, 0.0, -diagonal, -diagonal, 0.0, diagonal, -diagonal, 0.0, -diagonal,
            0.0, 1.0, 0.0
        };
    }

    public final ServerLevel level;
    /**
     * Incremented whenever the player's visibility state is reset (world change, respawn). Results computed for an
     * older epoch must be discarded.
     */
    public final long epoch;
    /**
     * The player's tick count when this snapshot was taken.
     */
    public final int tick;

    public final double eyeX;
    public final double eyeY;
    public final double eyeZ;
    /**
     * Unit vector of the view direction.
     */
    public final double lookX;
    public final double lookY;
    public final double lookZ;
    public final float yRot;
    public final float xRot;

    public final double velocityX;
    public final double velocityY;
    public final double velocityZ;

    /**
     * Maximum distance of a third person camera from the eyes, the "camera_distance" attribute.
     */
    public final double cameraDistance;

    // volatile publishes the contents of the array to the other jobs
    private volatile double @Nullable [] cameras;
    private volatile double @Nullable [] around;
    private volatile double aroundMargin = Double.NaN;

    public CameraSnapshot(final ServerLevel level, final long epoch, final int tick,
                          final double eyeX, final double eyeY, final double eyeZ,
                          final float yRot, final float xRot,
                          final double lookX, final double lookY, final double lookZ,
                          final double velocityX, final double velocityY, final double velocityZ,
                          final double cameraDistance) {
        this.level = level;
        this.epoch = epoch;
        this.tick = tick;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.yRot = yRot;
        this.xRot = xRot;
        this.lookX = lookX;
        this.lookY = lookY;
        this.lookZ = lookZ;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.cameraDistance = cameraDistance;
    }

    /**
     * Squared distance between the eyes and the given point.
     */
    public double distanceSquared(final double x, final double y, final double z) {
        final double dx = x - this.eyeX;
        final double dy = y - this.eyeY;
        final double dz = z - this.eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Whether the point is in front of the plane through the eyes perpendicular to the view direction, with the
     * plane moved back by {@code margin} blocks. A half space is used instead of a real frustum since the field of
     * view and aspect ratio of the client are unknown.
     */
    public boolean isInFront(final double x, final double y, final double z, final double margin) {
        return (x - this.eyeX) * this.lookX + (y - this.eyeY) * this.lookY + (z - this.eyeZ) * this.lookZ >= -margin;
    }

    /**
     * Returns the camera positions a client can render from: the eyes, followed by the third person back camera and
     * the third person front camera, each pulled in front of the first occluding block like the client does.
     * The array holds {@code x, y, z} triples.
     * <p>
     * Only blocks that occlude for the client's camera are considered, so this must be called before options like
     * {@link OcclusionAccess#setLavaObscures} are enabled on the access.
     */
    public double[] cameras(final OcclusionAccess access) {
        double[] ret = this.cameras;
        if (ret != null) {
            return ret;
        }

        final double back = clip(access, -this.lookX, -this.lookY, -this.lookZ);
        final double front = clip(access, this.lookX, this.lookY, this.lookZ);
        ret = new double[] {
            this.eyeX, this.eyeY, this.eyeZ,
            this.eyeX - this.lookX * back, this.eyeY - this.lookY * back, this.eyeZ - this.lookZ * back,
            this.eyeX + this.lookX * front, this.eyeY + this.lookY * front, this.eyeZ + this.lookZ * front
        };
        return this.cameras = ret;
    }

    /**
     * @return the points of {@link #pointsAround} the eyes, computed once per margin
     */
    public double[] aroundEyes(final BlockOcclusion access, final double margin) {
        final double[] ret = this.around;
        if (ret != null && this.aroundMargin == margin) {
            return ret;
        }
        final double[] points = pointsAround(access, this.eyeX, this.eyeY, this.eyeZ, margin);
        this.around = points;
        this.aroundMargin = margin;
        return points;
    }

    /**
     * Points the eyes of a player at the given position can get to soon, so what can be seen from there can be sent
     * before the client needs it: {@code margin} blocks away in the horizontal directions at 45 degree steps and up,
     * each pulled in front of the first block in the way, like the client pulls in its camera.
     *
     * @return the points as {@code x, y, z} triples, none if the eyes are inside a block or the margin is 0
     */
    public static double[] pointsAround(final BlockOcclusion access, final double eyeX, final double eyeY, final double eyeZ, final double margin) {
        if (margin <= 0.0 || access.isOpaque(Mth.floor(eyeX), Mth.floor(eyeY), Mth.floor(eyeZ))) {
            return new double[0];
        }
        final double[] ret = new double[AROUND.length];
        int count = 0;
        for (int i = 0; i < AROUND.length; i += 3) {
            final double clip = Math.min(margin,
                VoxelRaycaster.clipDistance(access, eyeX, eyeY, eyeZ, AROUND[i], AROUND[i + 1], AROUND[i + 2], margin + CAMERA_CLIP_MARGIN) - CAMERA_CLIP_MARGIN);
            if (clip > 0.05) {
                ret[count++] = eyeX + AROUND[i] * clip;
                ret[count++] = eyeY + AROUND[i + 1] * clip;
                ret[count++] = eyeZ + AROUND[i + 2] * clip;
            }
        }
        return count == ret.length ? ret : java.util.Arrays.copyOf(ret, count);
    }

    private double clip(final OcclusionAccess access, final double dirX, final double dirY, final double dirZ) {
        if (access.isOpaque(Mth.floor(this.eyeX), Mth.floor(this.eyeY), Mth.floor(this.eyeZ))) {
            // the client's camera collision starts at the eyes, inside a block it can't move away from them
            return 0.0;
        }
        final double hit = VoxelRaycaster.clipDistance(
            access, this.eyeX, this.eyeY, this.eyeZ, dirX, dirY, dirZ, this.cameraDistance + CAMERA_CLIP_MARGIN
        );
        return Math.max(0.0, Math.min(this.cameraDistance, hit - CAMERA_CLIP_MARGIN));
    }
}
