package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.VoxelRaycaster;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * An immutable copy of everything about a player's point of view that the asynchronous visibility work needs.
 * Taken on the player's region thread right before a job is submitted.
 * <p>
 * The only mutable part is the lazily computed third person camera positions, which are computed on the worker
 * thread that owns the snapshot.
 */
public final class CameraSnapshot {

    /**
     * Distance the client keeps between a third person camera and the block it collides with.
     */
    private static final double CAMERA_CLIP_MARGIN = 0.1;

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

    private double @Nullable [] cameras;

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

    private double clip(final OcclusionAccess access, final double dirX, final double dirY, final double dirZ) {
        final double hit = VoxelRaycaster.clipDistance(
            access, this.eyeX, this.eyeY, this.eyeZ, dirX, dirY, dirZ, this.cameraDistance + CAMERA_CLIP_MARGIN
        );
        return Math.max(0.0, Math.min(this.cameraDistance, hit - CAMERA_CLIP_MARGIN));
    }
}
