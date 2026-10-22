package io.canvasmc.canvas.anticheat.antiesp;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionCache;
import io.canvasmc.canvas.anticheat.raytrace.VoxelRaycaster;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

/**
 * Per-player ray traced anti-ESP, consulted by the entity tracker.
 * <p>
 * {@link #canSee} is called by the tracker every tick for every entity in tracking range that vanilla would send to
 * the player, and answers from a per-player table in O(1) without allocating. Returning {@code false} makes the
 * tracker remove the entity from the client, returning {@code true} again re-adds it with a full spawn. The table is
 * filled by the asynchronous job: every tick, {@link #prepareAsync} copies the boxes of the entities due for a check,
 * {@link #runAsync} traces rays from the player's cameras to them, and the results are applied as soon as the job
 * is done, normally before the next tracker pass, so they take effect within one tick.
 * <p>
 * Scheduling is asymmetric: hidden entities are re-checked every tick while they or the camera move, so they appear
 * as early as possible, while visible ones are only re-checked every {@code visible-recheck-interval-ticks} and stay
 * visible for {@code hide-grace-ticks} after the line of sight was lost, which avoids flickering at corners. Entities
 * that appear without any result yet (spawned, entered tracking range, after a reset) are never sent blindly: close
 * ones are traced synchronously within a small budget, the rest waits for the worker. Vehicle stacks are one unit,
 * traced with the box enclosing all of them, so a rider is never shown without its vehicle or the other way round.
 */
public final class EntityVisibility implements VisibilityModule {

    /**
     * Synchronous first sight checks per player and tracker pass. Beyond that, new entities wait for the worker.
     */
    private static final int SYNC_BUDGET = 64;
    /**
     * New entities further away than this wait for the worker instead of being traced synchronously, a tick of delay
     * is not noticeable there and long rays are the expensive ones.
     */
    private static final double SYNC_DISTANCE_SQ = 32.0 * 32.0;
    /**
     * Hidden entities are re-checked this often while neither they nor the camera move, to pick up block changes.
     */
    private static final int HIDDEN_RECHECK_INTERVAL = 2;
    /**
     * Movement per tick above this is a teleport: nothing is predicted and visible entities lose their grace period.
     */
    private static final double TELEPORT_DISTANCE_SQ = 8.0 * 8.0;
    /**
     * Movement per tick below this is not predicted.
     */
    private static final double MIN_PREDICTED_SPEED_SQ = 0.01 * 0.01;
    private static final int MAX_PREDICTION_TICKS = 10;
    private static final double MAX_PREDICTION_DISTANCE = 3.0;
    /**
     * Distance kept between a predicted camera and the block it runs into, like the client does for its cameras.
     */
    private static final double CAMERA_CLIP_MARGIN = 0.1;
    private static final double MIN_CAMERA_SEPARATION_SQ = 0.05 * 0.05;
    /**
     * Eyes, third person back and front camera, predicted eyes.
     */
    private static final int MAX_CAMERAS = 4;
    /**
     * Vanilla clients render name tags up to 64 blocks away.
     */
    private static final double NAME_TAG_DISTANCE_SQ = 64.0 * 64.0;

    /**
     * Entities whose visibility doesn't depend on line of sight on the client, or whose tracking has side effects:
     * the wither's boss bar is shown to the players tracking it, the dragon and lightning are visible from far away,
     * end crystal beams, potion clouds and leashes are drawn between entities.
     */
    private static final List<EntityType<?>> ALWAYS_VISIBLE_TYPES = List.of(
        EntityTypes.ENDER_DRAGON, EntityTypes.WITHER, EntityTypes.LIGHTNING_BOLT, EntityTypes.END_CRYSTAL,
        EntityTypes.AREA_EFFECT_CLOUD, EntityTypes.LEASH_KNOT
    );

    private final PlayerVisibility owner;
    private final EntityTable table = new EntityTable();
    private final Job job = new Job();

    /**
     * Id of the current tracker pass. {@link PlayerVisibility} ticks the module once per tick right after the entity
     * tracker ran, which is when this is incremented.
     */
    private int pass;
    private int startedPass = EntityTable.NEVER;
    private boolean jobPending;

    // settings, refreshed at the start of every pass
    private WorldConfig.Anticheat.@Nullable AntiEsp config;
    private Set<EntityType<?>> alwaysVisibleTypes = Set.of();
    private boolean passActive;
    private double alwaysVisibleDistanceSq;
    private double maxTraceDistanceSq;
    private boolean rayTraceThirdPerson;
    private boolean hidePlayers;
    private boolean respectVanillaNameTags;
    private boolean hideProjectiles;
    private int visibleRecheckInterval;

    // camera movement, makes hidden entities due for a re-check
    private int cameraMoved = EntityTable.NEVER;
    private double lastCameraX = Double.NaN;
    private double lastCameraY;
    private double lastCameraZ;
    private float lastYRot;
    private float lastXRot;

    // synchronous first sight checks
    private int syncBudget;
    private @Nullable OcclusionAccess syncAccess;
    private int syncPass = EntityTable.NEVER;
    private double syncEyeX;
    private double syncEyeY;
    private double syncEyeZ;
    private int syncCameraCount;
    private final double[] syncCameras = new double[MAX_CAMERAS * 3];
    private final double[] syncPoints = new double[BoxOcclusion.MAX_POINTS * 3];
    private final double[] scratchBox = new double[EntityTable.BOX_STRIDE];

    public EntityVisibility(final PlayerVisibility owner) {
        this.owner = owner;
    }

    @Override
    public boolean isActive(final WorldConfig.Anticheat config) {
        return config.antiEsp.enabled && !this.owner.bypassesAntiEsp();
    }

    @Override
    public void tick(final CameraSnapshot snapshot) {
        // the tracker just ran, which ends the current tracker pass
        ++this.pass;
    }

    @Override
    public void reset() {
        // stop answering from the table until the next pass of an active module starts
        this.passActive = false;
        this.startedPass = this.pass;
        // a running job never reads the entity references, and its results are dropped through the epoch
        this.table.clear();
        Arrays.fill(this.job.entities, null);
        this.jobPending = false;
        this.cameraMoved = EntityTable.NEVER;
        this.lastCameraX = Double.NaN;
        this.syncAccess = null;
    }

    /**
     * Whether anti-ESP currently hides the entity from the player. Unlike {@link #canSee} this never changes any state,
     * entities the entity tracker didn't ask about recently (out of tracking range) aren't hidden by anti-ESP.
     */
    public boolean isHidden(final Entity entity) {
        if (!this.passActive) {
            return false;
        }
        final Entity root = entity.getRootVehicle();
        final EntityTable table = this.table;
        final int slot = table.find(root.getId());
        return slot >= 0 && table.entity[slot] == root && !table.always[slot]
            && table.state[slot] == EntityTable.HIDDEN && this.pass - table.seen[slot] <= 1;
    }

    /**
     * Whether the player's client currently has the entity because anti-ESP found it visible: its entry was refreshed
     * by the entity tracker during the last tick and is visible. Entities outside tracking range aren't. Never changes
     * any state.
     */
    public boolean isKnownVisible(final Entity entity) {
        if (!this.passActive) {
            return true;
        }
        final Entity root = entity.getRootVehicle();
        if (root == this.owner.player.getRootVehicle()) {
            return true;
        }
        final EntityTable table = this.table;
        final int slot = table.find(root.getId());
        return slot >= 0 && table.entity[slot] == root && this.pass - table.seen[slot] <= 1
            && (table.always[slot] || table.state[slot] == EntityTable.VISIBLE);
    }

    /**
     * Entity tracker check, see the class documentation.
     */
    public boolean canSee(final Entity entity) {
        if (this.startedPass != this.pass) {
            this.startPass();
        }
        if (!this.passActive) {
            return true;
        }
        if (this.jobPending && this.job.done) {
            this.applyJob();
        }

        final ServerPlayer viewer = this.owner.player;
        final Entity root = entity.getRootVehicle();
        final Entity viewerRoot = viewer.getRootVehicle();
        if (root == viewerRoot) {
            // the viewer's own vehicle and everything riding with the viewer
            return true;
        }

        final EntityTable table = this.table;
        final int id = root.getId();
        int slot = table.find(id);
        if (slot >= 0 && table.entity[slot] != root) {
            table.remove(slot);
            slot = -1;
        }
        if (slot < 0) {
            slot = table.add(id, root);
        }
        if (table.seen[slot] != this.pass) {
            this.refresh(slot, root, viewer, viewerRoot);
        }
        if (table.always[slot]) {
            return true;
        }
        return switch (table.state[slot]) {
            case EntityTable.VISIBLE -> true;
            case EntityTable.HIDDEN -> false;
            default -> this.firstSight(slot, entity, viewer);
        };
    }

    private void startPass() {
        this.startedPass = this.pass;
        final WorldConfig.Anticheat.AntiEsp config = this.owner.config().antiEsp;
        if (config != this.config) {
            this.config = config;
            final Set<EntityType<?>> types = new ReferenceOpenHashSet<>(ALWAYS_VISIBLE_TYPES);
            types.addAll(config.compiledExcludedEntityTypes());
            this.alwaysVisibleTypes = types;
        }
        this.passActive = config.enabled && !this.owner.bypassesAntiEsp();
        this.alwaysVisibleDistanceSq = config.alwaysVisibleDistance * config.alwaysVisibleDistance;
        this.maxTraceDistanceSq = config.maxTraceDistance * config.maxTraceDistance;
        this.rayTraceThirdPerson = config.rayTraceThirdPerson;
        this.hidePlayers = config.hidePlayers;
        this.respectVanillaNameTags = config.respectVanillaNameTags;
        this.hideProjectiles = config.hideProjectiles;
        this.visibleRecheckInterval = config.visibleRecheckIntervalTicks;
        this.syncBudget = SYNC_BUDGET;
    }

    /**
     * Updates the entry of a vehicle stack for the current pass: its box, whether it moved, and whether it is exempt
     * from tracing.
     */
    private void refresh(final int slot, final Entity root, final ServerPlayer viewer, final Entity viewerRoot) {
        final EntityTable table = this.table;
        final int pass = this.pass;
        final boolean continuous = table.seen[slot] == pass - 1;
        if (!continuous) {
            // new, or the tracker skipped it for a pass, which means the client doesn't have it anymore
            table.forget(slot);
        }
        table.seen[slot] = pass;

        final double[] box = this.scratchBox;
        stackBox(root, box);
        final double[] stored = table.box;
        final int offset = slot * EntityTable.BOX_STRIDE;
        final int motion = slot * EntityTable.MOTION_STRIDE;
        if (!continuous) {
            System.arraycopy(box, 0, stored, offset, EntityTable.BOX_STRIDE);
            table.moved[slot] = pass;
        } else if (!Arrays.equals(box, 0, EntityTable.BOX_STRIDE, stored, offset, offset + EntityTable.BOX_STRIDE)) {
            for (int axis = 0; axis < 3; ++axis) {
                table.motion[motion + axis] = (box[axis] + box[axis + 3] - stored[offset + axis] - stored[offset + axis + 3]) * 0.5;
            }
            System.arraycopy(box, 0, stored, offset, EntityTable.BOX_STRIDE);
            table.moved[slot] = pass;
            if (lengthSq(table.motion[motion], table.motion[motion + 1], table.motion[motion + 2]) > TELEPORT_DISTANCE_SQ) {
                // teleported: having been visible at the old position says nothing about the new one
                table.lastVisible[slot] = EntityTable.NEVER;
                table.nextCheck[slot] = EntityTable.NEVER;
            }
        } else {
            table.motion[motion] = 0.0;
            table.motion[motion + 1] = 0.0;
            table.motion[motion + 2] = 0.0;
        }

        final double distanceSq = distanceSq(viewer, box, 0);
        final boolean always = distanceSq <= this.alwaysVisibleDistanceSq || distanceSq > this.maxTraceDistanceSq
            || this.isAlwaysVisible(root, true, viewer, viewerRoot)
            || (root.isVehicle() && this.isAnyPassengerAlwaysVisible(root, viewer, viewerRoot));
        table.always[slot] = always;
        if (always) {
            // once this no longer applies the entity is traced again, starting with the usual grace period
            table.state[slot] = EntityTable.VISIBLE;
            table.lastVisible[slot] = pass;
            table.nextCheck[slot] = pass;
        }
    }

    /**
     * Handles an entity without a visibility result. It must not be sent before it is known to be visible, but it
     * also must not pop in late when it appears close to the player.
     */
    private boolean firstSight(final int slot, final Entity entity, final ServerPlayer viewer) {
        final ChunkMap.TrackedEntity tracked = entity.moonrise$getTrackedEntity();
        if (tracked != null && tracked.seenBy.contains(viewer.connection)) {
            // sent before anything was known (the module just became active), hiding it now would only flicker
            return true;
        }
        final EntityTable table = this.table;
        final int offset = slot * EntityTable.BOX_STRIDE;
        if (this.syncBudget <= 0 || distanceSq(viewer, table.box, offset) > SYNC_DISTANCE_SQ) {
            // the worker answers within a tick
            return false;
        }
        --this.syncBudget;
        final OcclusionCache cache = OcclusionCache.get(this.syncAccess(viewer), this.syncEyeX, this.syncEyeY, this.syncEyeZ);
        // no grid points on the region thread: large boxes would cost hundreds of rays, a box only visible through a
        // small gap is found by the worker right after
        final boolean visible = BoxOcclusion.isVisible(cache, this.syncCameras, this.syncCameraCount, table.box, offset, this.syncPoints, false);
        OcclusionCache.releaseCurrent();
        final int pass = this.pass;
        table.checked[slot] = pass;
        if (visible) {
            table.state[slot] = EntityTable.VISIBLE;
            table.lastVisible[slot] = pass;
            table.nextCheck[slot] = pass + this.visibleRecheckInterval;
        } else {
            // the worker re-checks it right away, with the predicted eyes as well
            table.state[slot] = EntityTable.HIDDEN;
            table.nextCheck[slot] = pass;
        }
        return visible;
    }

    /**
     * @return a block access and the cameras for synchronous checks, built at most once per pass and camera position
     */
    private OcclusionAccess syncAccess(final ServerPlayer viewer) {
        final double eyeX = viewer.getX();
        final double eyeY = viewer.getEyeY();
        final double eyeZ = viewer.getZ();
        OcclusionAccess access = this.syncAccess;
        if (access != null && this.syncPass == this.pass && eyeX == this.syncEyeX && eyeY == this.syncEyeY && eyeZ == this.syncEyeZ) {
            return access;
        }
        // a new access every pass, since it caches chunks which may unload in between
        access = new OcclusionAccess(viewer.level());
        final Vec3 look = viewer.getViewVector(1.0F);
        final CameraSnapshot snapshot = new CameraSnapshot(
            viewer.level(), this.owner.epoch(), viewer.tickCount,
            eyeX, eyeY, eyeZ,
            viewer.getYRot(), viewer.getXRot(),
            look.x, look.y, look.z,
            0.0, 0.0, 0.0,
            viewer.getAttributeValue(Attributes.CAMERA_DISTANCE)
        );
        this.syncCameraCount = collectCameras(snapshot, access, this.rayTraceThirdPerson, 0, this.syncCameras);
        this.syncAccess = access;
        this.syncPass = this.pass;
        this.syncEyeX = eyeX;
        this.syncEyeY = eyeY;
        this.syncEyeZ = eyeZ;
        return access;
    }

    /**
     * Whether a single entity is exempt from tracing, which exempts its whole vehicle stack.
     *
     * @param root whether the entity is the root of its stack. Display and interaction entities are usually mounted
     *             on other entities by plugins (name tags, hitboxes) and follow their vehicle, standing alone they
     *             are never hidden since they can render through walls
     */
    private boolean isAlwaysVisible(final Entity entity, final boolean root, final ServerPlayer viewer, final Entity viewerRoot) {
        if (entity == viewer.getCamera() || this.alwaysVisibleTypes.contains(entity.getType())) {
            return true;
        }
        if (root && (entity instanceof Display || entity instanceof Interaction || entity instanceof Marker)) {
            return true;
        }
        if (entity instanceof Player) {
            if (!this.hidePlayers) {
                return true;
            }
        } else if (entity instanceof Projectile projectile) {
            // includes the viewer's own fishing bobber
            if (!this.hideProjectiles || projectile.getOwner() == viewer) {
                return true;
            }
        }
        if (entity.isCurrentlyGlowing()) {
            return true;
        }
        // leashed to the viewer (or something riding with it), or the viewer's vehicle is leashed to the entity
        if (entity instanceof Leashable leashable) {
            final Leashable.LeashData leash = leashable.getLeashData();
            if (leash != null && leash.leashHolder != null && leash.leashHolder.getRootVehicle() == viewerRoot) {
                return true;
            }
        }
        if (viewerRoot instanceof Leashable leashable) {
            final Leashable.LeashData leash = leashable.getLeashData();
            if (leash != null && leash.leashHolder == entity) {
                return true;
            }
        }
        return this.respectVanillaNameTags && isNameTagRenderedThroughWalls(entity, viewer);
    }

    private boolean isAnyPassengerAlwaysVisible(final Entity vehicle, final ServerPlayer viewer, final Entity viewerRoot) {
        final List<Entity> passengers = vehicle.getPassengers();
        for (int i = 0, len = passengers.size(); i < len; ++i) {
            final Entity passenger = passengers.get(i);
            if (this.isAlwaysVisible(passenger, false, viewer, viewerRoot)
                || (passenger.isVehicle() && this.isAnyPassengerAlwaysVisible(passenger, viewer, viewerRoot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mirrors when a vanilla client renders an entity's name tag in see-through mode, which shows it through walls.
     */
    private static boolean isNameTagRenderedThroughWalls(final Entity entity, final ServerPlayer viewer) {
        if (entity.isDiscrete()) {
            // sneaking players' name tags are rendered normally, so walls hide them
            return false;
        }
        final double dx = entity.getX() - viewer.getX();
        final double dy = entity.getY() - viewer.getEyeY();
        final double dz = entity.getZ() - viewer.getZ();
        if (dx * dx + dy * dy + dz * dz > NAME_TAG_DISTANCE_SQ) {
            return false;
        }
        if (entity instanceof ArmorStand) {
            // holograms: shown even when the armor stand is invisible
            return entity.isCustomNameVisible();
        }
        if (!(entity instanceof LivingEntity)) {
            return entity.shouldShowName() && entity.hasCustomName();
        }
        if (!(entity instanceof Player) && !(entity.shouldShowName() && entity.hasCustomName())) {
            return false;
        }
        final boolean shown = !entity.isInvisibleTo(viewer);
        final PlayerTeam team = entity.getTeam();
        if (team != null) {
            final PlayerTeam viewerTeam = viewer.getTeam();
            return switch (team.getNameTagVisibility()) {
                case ALWAYS -> shown;
                case NEVER -> false;
                case HIDE_FOR_OTHER_TEAMS -> viewerTeam == null ? shown : team.isAlliedTo(viewerTeam) && (team.canSeeFriendlyInvisibles() || shown);
                case HIDE_FOR_OWN_TEAM -> viewerTeam == null ? shown : !team.isAlliedTo(viewerTeam) && shown;
            };
        }
        return shown && !entity.isVehicle();
    }

    @Override
    public boolean prepareAsync(final CameraSnapshot snapshot) {
        final WorldConfig.Anticheat.AntiEsp config = this.owner.config().antiEsp;
        final EntityTable table = this.table;
        final Job job = this.job;
        // the tracker pass that just ran, whose boxes are in the table
        final int data = this.pass - 1;

        final boolean thirdPerson = config.rayTraceThirdPerson;
        if (snapshot.eyeX != this.lastCameraX || snapshot.eyeY != this.lastCameraY || snapshot.eyeZ != this.lastCameraZ
            || (thirdPerson && (snapshot.yRot != this.lastYRot || snapshot.xRot != this.lastXRot))) {
            this.cameraMoved = data;
            this.lastCameraX = snapshot.eyeX;
            this.lastCameraY = snapshot.eyeY;
            this.lastCameraZ = snapshot.eyeZ;
            this.lastYRot = snapshot.yRot;
            this.lastXRot = snapshot.xRot;
        }
        final boolean teleported = lengthSq(snapshot.velocityX, snapshot.velocityY, snapshot.velocityZ) > TELEPORT_DISTANCE_SQ;
        // what the client shows lags behind by about a round trip and a tick, so look that far ahead
        final int predictionTicks = teleported ? 0 : Math.clamp(1 + (this.owner.player.connection.latency() + 49) / 50, 1, MAX_PREDICTION_TICKS);

        int count = 0;
        for (int slot = 0; slot < table.size; ) {
            if (table.seen[slot] != data) {
                // the tracker didn't ask about it in the last pass, so the client doesn't have it anymore
                table.remove(slot);
                continue;
            }
            if (table.always[slot]) {
                ++slot;
                continue;
            }
            final byte state = table.state[slot];
            if (teleported && state == EntityTable.VISIBLE) {
                // what was visible before the teleport says nothing about now
                table.lastVisible[slot] = EntityTable.NEVER;
            }
            final int checked = table.checked[slot];
            final boolean due = state == EntityTable.UNKNOWN || teleported || data >= table.nextCheck[slot]
                || (state == EntityTable.HIDDEN && (table.moved[slot] > checked || this.cameraMoved > checked));
            if (due) {
                job.add(count++, table, slot, predictionTicks);
                table.checked[slot] = data;
            }
            ++slot;
        }
        if (count == 0) {
            return false;
        }

        job.count = count;
        job.pass = data;
        job.epoch = snapshot.epoch;
        job.thirdPerson = thirdPerson;
        job.predictionTicks = predictionTicks;
        job.visibleRecheckInterval = config.visibleRecheckIntervalTicks;
        job.hideGraceTicks = config.hideGraceTicks;
        job.done = false;
        this.jobPending = true;
        return true;
    }

    @Override
    public void runAsync(final CameraSnapshot snapshot, final OcclusionAccess access) {
        final Job job = this.job;
        try {
            final int cameraCount = collectCameras(snapshot, access, job.thirdPerson, job.predictionTicks, job.cameras);
            final OcclusionCache cache = OcclusionCache.get(access, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ);
            for (int i = 0, count = job.count; i < count; ++i) {
                final boolean visible = BoxOcclusion.isVisible(cache, job.cameras, cameraCount, job.boxes, i * EntityTable.BOX_STRIDE, job.points);
                job.results[i] = visible ? EntityTable.VISIBLE : EntityTable.HIDDEN;
            }
        } finally {
            // lets canSee apply the results right away, instead of waiting for the next player tick
            job.done = true;
        }
    }

    @Override
    public void applyResults(final CameraSnapshot snapshot) {
        if (this.jobPending) {
            this.applyJob();
        }
    }

    private void applyJob() {
        this.jobPending = false;
        final Job job = this.job;
        if (job.epoch != this.owner.epoch()) {
            // started before a reset
            return;
        }
        final EntityTable table = this.table;
        final int data = job.pass;
        for (int i = 0, count = job.count; i < count; ++i) {
            final Entity entity = job.entities[i];
            job.entities[i] = null;
            final byte result = job.results[i];
            final int slot = table.find(job.ids[i]);
            if (result == EntityTable.UNKNOWN || slot < 0 || table.entity[slot] != entity || table.checked[slot] != data) {
                // failed, gone, or checked again since
                continue;
            }
            if (result == EntityTable.VISIBLE) {
                table.state[slot] = EntityTable.VISIBLE;
                table.lastVisible[slot] = Math.max(table.lastVisible[slot], data);
                table.nextCheck[slot] = data + job.visibleRecheckInterval;
            } else if (table.state[slot] == EntityTable.VISIBLE && data - table.lastVisible[slot] <= job.hideGraceTicks) {
                // keep it during the grace period, but find out every tick whether the line of sight came back
                table.nextCheck[slot] = data + 1;
            } else {
                table.state[slot] = EntityTable.HIDDEN;
                table.nextCheck[slot] = data + HIDDEN_RECHECK_INTERVAL;
            }
        }
    }

    /**
     * Collects the positions the player can see from: the eyes, the third person cameras and the eyes where the
     * player is about to be. Cameras inside opaque blocks are dropped, and if the eyes are inside one nothing is
     * visible at all, as the client then only renders the inside of that block.
     *
     * @return the amount of cameras written to {@code out} as {@code x, y, z} triples
     */
    private static int collectCameras(final CameraSnapshot snapshot, final OcclusionAccess access, final boolean thirdPerson,
                                      final int predictionTicks, final double[] out) {
        final double eyeX = snapshot.eyeX;
        final double eyeY = snapshot.eyeY;
        final double eyeZ = snapshot.eyeZ;
        if (access.isOpaque(Mth.floor(eyeX), Mth.floor(eyeY), Mth.floor(eyeZ))) {
            return 0;
        }
        out[0] = eyeX;
        out[1] = eyeY;
        out[2] = eyeZ;
        int count = 1;

        if (thirdPerson) {
            final double[] cameras = snapshot.cameras(access);
            for (int i = 3; i + 2 < cameras.length; i += 3) {
                count = addCamera(access, out, count, cameras[i], cameras[i + 1], cameras[i + 2]);
            }
        }

        final double speedSq = lengthSq(snapshot.velocityX, snapshot.velocityY, snapshot.velocityZ);
        if (predictionTicks > 0 && speedSq > MIN_PREDICTED_SPEED_SQ && speedSq <= TELEPORT_DISTANCE_SQ) {
            final double speed = Math.sqrt(speedSq);
            final double dirX = snapshot.velocityX / speed;
            final double dirY = snapshot.velocityY / speed;
            final double dirZ = snapshot.velocityZ / speed;
            final double distance = Math.min(speed * predictionTicks, MAX_PREDICTION_DISTANCE);
            // never predict through a wall
            final double clip = VoxelRaycaster.clipDistance(access, eyeX, eyeY, eyeZ, dirX, dirY, dirZ, distance + CAMERA_CLIP_MARGIN) - CAMERA_CLIP_MARGIN;
            final double reach = Math.min(distance, clip);
            if (reach > 0.0) {
                count = addCamera(access, out, count, eyeX + dirX * reach, eyeY + dirY * reach, eyeZ + dirZ * reach);
            }
        }
        return count;
    }

    private static int addCamera(final OcclusionAccess access, final double[] out, final int count,
                                 final double x, final double y, final double z) {
        for (int i = 0; i < count; ++i) {
            if (lengthSq(out[i * 3] - x, out[i * 3 + 1] - y, out[i * 3 + 2] - z) < MIN_CAMERA_SEPARATION_SQ) {
                return count;
            }
        }
        if (access.isOpaque(Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
            return count;
        }
        out[count * 3] = x;
        out[count * 3 + 1] = y;
        out[count * 3 + 2] = z;
        return count + 1;
    }

    /**
     * Writes the box enclosing the entity and everything riding it.
     */
    private static void stackBox(final Entity root, final double[] out) {
        final AABB box = root.getBoundingBox();
        out[0] = box.minX;
        out[1] = box.minY;
        out[2] = box.minZ;
        out[3] = box.maxX;
        out[4] = box.maxY;
        out[5] = box.maxZ;
        if (root.isVehicle()) {
            includePassengers(root, out);
        }
    }

    private static void includePassengers(final Entity vehicle, final double[] out) {
        final List<Entity> passengers = vehicle.getPassengers();
        for (int i = 0, len = passengers.size(); i < len; ++i) {
            final Entity passenger = passengers.get(i);
            final AABB box = passenger.getBoundingBox();
            out[0] = Math.min(out[0], box.minX);
            out[1] = Math.min(out[1], box.minY);
            out[2] = Math.min(out[2], box.minZ);
            out[3] = Math.max(out[3], box.maxX);
            out[4] = Math.max(out[4], box.maxY);
            out[5] = Math.max(out[5], box.maxZ);
            if (passenger.isVehicle()) {
                includePassengers(passenger, out);
            }
        }
    }

    /**
     * Squared distance between the viewer's eyes and the closest point of a box.
     */
    private static double distanceSq(final ServerPlayer viewer, final double[] boxes, final int offset) {
        final double eyeX = viewer.getX();
        final double eyeY = viewer.getEyeY();
        final double eyeZ = viewer.getZ();
        final double dx = Math.max(0.0, Math.max(boxes[offset] - eyeX, eyeX - boxes[offset + 3]));
        final double dy = Math.max(0.0, Math.max(boxes[offset + 1] - eyeY, eyeY - boxes[offset + 4]));
        final double dz = Math.max(0.0, Math.max(boxes[offset + 2] - eyeZ, eyeZ - boxes[offset + 5]));
        return dx * dx + dy * dy + dz * dz;
    }

    private static double lengthSq(final double x, final double y, final double z) {
        return x * x + y * y + z * z;
    }

    /**
     * The work handed to the worker. Written by {@link #prepareAsync} while no job runs, read by the worker, and
     * read back on the region thread once {@link #done} is set. A reset only drops the entity references, which the
     * worker never reads.
     */
    private static final class Job {

        private static final int INITIAL_CAPACITY = 64;

        int count;
        int pass;
        long epoch;
        boolean thirdPerson;
        int predictionTicks;
        int visibleRecheckInterval;
        int hideGraceTicks;
        int[] ids = new int[INITIAL_CAPACITY];
        @Nullable Entity[] entities = new Entity[INITIAL_CAPACITY];
        double[] boxes = new double[INITIAL_CAPACITY * EntityTable.BOX_STRIDE];
        byte[] results = new byte[INITIAL_CAPACITY];
        final double[] cameras = new double[MAX_CAMERAS * 3];
        final double[] points = new double[BoxOcclusion.MAX_POINTS * 3];
        volatile boolean done;

        /**
         * Queues an entry, with its box stretched along its movement over the prediction time, so an entity walking
         * out from behind a wall is sent before it emerges on the client.
         */
        void add(final int index, final EntityTable table, final int slot, final int predictionTicks) {
            if (index == this.ids.length) {
                final int capacity = index * 2;
                this.ids = Arrays.copyOf(this.ids, capacity);
                this.entities = Arrays.copyOf(this.entities, capacity);
                this.boxes = Arrays.copyOf(this.boxes, capacity * EntityTable.BOX_STRIDE);
                this.results = Arrays.copyOf(this.results, capacity);
            }
            this.ids[index] = table.id[slot];
            this.entities[index] = table.entity[slot];
            this.results[index] = EntityTable.UNKNOWN;

            final int from = slot * EntityTable.BOX_STRIDE;
            final int to = index * EntityTable.BOX_STRIDE;
            System.arraycopy(table.box, from, this.boxes, to, EntityTable.BOX_STRIDE);

            final int motion = slot * EntityTable.MOTION_STRIDE;
            final double motionX = table.motion[motion];
            final double motionY = table.motion[motion + 1];
            final double motionZ = table.motion[motion + 2];
            final double speedSq = lengthSq(motionX, motionY, motionZ);
            if (predictionTicks <= 0 || speedSq < MIN_PREDICTED_SPEED_SQ || speedSq > TELEPORT_DISTANCE_SQ) {
                return;
            }
            final double scale = Math.min(predictionTicks, MAX_PREDICTION_DISTANCE / Math.sqrt(speedSq));
            for (int axis = 0; axis < 3; ++axis) {
                final double delta = table.motion[motion + axis] * scale;
                if (delta < 0.0) {
                    this.boxes[to + axis] += delta;
                } else {
                    this.boxes[to + axis + 3] += delta;
                }
            }
        }
    }
}
