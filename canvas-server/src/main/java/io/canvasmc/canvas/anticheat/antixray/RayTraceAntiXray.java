package io.canvasmc.canvas.anticheat.antixray;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.AnticheatExecutor;
import io.canvasmc.canvas.anticheat.CameraSnapshot;
import io.canvasmc.canvas.anticheat.PlayerVisibility;
import io.canvasmc.canvas.anticheat.VisibilityModule;
import io.canvasmc.canvas.anticheat.raytrace.BlockOcclusion;
import io.canvasmc.canvas.anticheat.raytrace.FaceVisibility;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionCache;
import io.papermc.paper.antixray.ChunkPacketInfo;
import io.papermc.paper.antixray.ChunkPacketInfoAntiXray;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Per-player ray traced anti-xray: tracks the blocks hidden by {@link RayTraceObfuscation} in every chunk sent to
 * the player and reveals them once the player can see them.
 * <p>
 * Each sent chunk is tracked by position until it is unloaded or sent again. While its packet is still being
 * obfuscated the entry only holds the packet's anti-xray info; once the packet is ready the sorted positions of the
 * hidden blocks are taken from it, together with a bitset of which of them are still hidden for this player. That
 * state is read by the asynchronous visibility pass and by {@link #isHidden} from any thread, and only the bitset
 * changes afterwards (atomically).
 * <p>
 * A hidden block is revealed by sending its current state (and block entity) once a ray from one of the player's
 * cameras reaches one of its exposed faces. Passes only run when the player moved or turned, when new chunks arrived,
 * when blocks next to hidden blocks changed, or after a configured interval, so standing still costs next to
 * nothing.
 */
public final class RayTraceAntiXray implements VisibilityModule {

    private static final VarHandle BITS = MethodHandles.arrayElementVarHandle(long[].class);
    private static final Set<ResourceKey<Level>> WARNED_LEVELS = ConcurrentHashMap.newKeySet();
    private static final int[] NO_POSITIONS = new int[0];
    /**
     * Blocks revealed per tick after the module was deactivated for a player.
     */
    private static final int DEACTIVATION_REVEALS_PER_TICK = 512;
    private static final long[] NO_BITS = new long[0];

    /**
     * How far the eyes must move from the position of the last pass to trigger a new one.
     */
    private static final double MOVE_THRESHOLD_SQUARED = 0.1 * 0.1;
    /**
     * How far the view must turn (in degrees) to trigger a new pass, when the view direction matters.
     */
    private static final float ROTATION_THRESHOLD = 2.0F;
    /**
     * Blocks whose center lies up to this far behind a camera still count as in front of it. Covers the extent of
     * the block itself and the client turning a little ahead of the server.
     */
    private static final double FRUSTUM_MARGIN = 1.0;

    private final PlayerVisibility owner;
    private final ConcurrentChainedLong2ReferenceHashTable<TrackedChunk> chunks = new ConcurrentChainedLong2ReferenceHashTable<>();

    /**
     * Set when an update of a hidden block was withheld. Paper sends hidden blocks whenever a block next to them is
     * broken, so this is the moment a hidden block may have become visible without the player moving.
     */
    private volatile boolean updateWithheld;

    // region thread state
    private final ArrayList<TrackedChunk> pending = new ArrayList<>();
    /**
     * Chunks that still had hidden blocks when the module was deactivated, their blocks are revealed over the next
     * ticks.
     */
    private final Long2ReferenceLinkedOpenHashMap<TrackedChunk> deactivated = new Long2ReferenceLinkedOpenHashMap<>();
    private boolean needsPass;
    private double passX = Double.NaN;
    private double passY;
    private double passZ;
    private float passYaw;
    private float passPitch;
    private int passTick;

    // job inputs, written by prepareAsync
    private double jobDistance;
    private boolean jobRehide;
    private double jobRehideDistanceSquared;
    private boolean jobThirdPerson;
    private boolean jobFrustumCulling;
    private boolean jobLavaObscures;

    // job results, written by runAsync and read by applyResults. Indices of blocks to reveal, or ~index to rehide
    private TrackedChunk[] resultChunks = new TrackedChunk[64];
    private int[] resultIndices = new int[64];
    private int resultCount;

    public RayTraceAntiXray(final PlayerVisibility owner) {
        this.owner = owner;
    }

    @Override
    public boolean isActive(final WorldConfig.Anticheat config) {
        if (!config.rayTraceAntiXray.enabled || this.owner.bypassesAntiXray()) {
            return false;
        }
        final ServerLevel level = this.owner.player.level();
        if (RayTraceObfuscation.of(level) != null) {
            return true;
        }
        if (WARNED_LEVELS.add(level.dimension())) {
            AnticheatExecutor.LOGGER.warn(
                "Ray traced anti-xray is enabled in {}, but it requires Paper anti-xray to be enabled with engine-mode 1 there. It stays disabled",
                level.dimension().identifier()
            );
        }
        return false;
    }

    @Override
    public void reset() {
        if (this.isActive(this.owner.config())) {
            // world change or respawn: the chunk loader may have sent chunks of the new world in this tick already,
            // the client keeps those, so only the old world's chunks are dropped
            final ServerLevel level = this.owner.player.level();
            for (final Iterator<TrackedChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
                final TrackedChunk tracked = iterator.next();
                if (tracked.level != level) {
                    this.chunks.remove(tracked.key, tracked);
                }
            }
            this.pending.removeIf((final TrackedChunk tracked) -> tracked.level != level);
            this.deactivated.clear();
        } else {
            // deactivated (bypass granted, disabled by a reload): the client still shows the hidden blocks, they are
            // revealed over the next ticks. Updates of them go through from now on
            for (final Iterator<TrackedChunk> iterator = this.chunks.valueIterator(); iterator.hasNext(); ) {
                final TrackedChunk tracked = iterator.next();
                this.deactivated.put(tracked.key, tracked);
            }
            this.chunks.clear();
            this.pending.clear();
        }
        this.passX = Double.NaN;
        this.needsPass = true;
    }

    @Override
    public void tickInactive() {
        final ServerLevel level = this.owner.player.level();
        int budget = DEACTIVATION_REVEALS_PER_TICK;
        while (budget > 0 && !this.deactivated.isEmpty()) {
            final TrackedChunk tracked = this.deactivated.get(this.deactivated.firstLongKey());
            final ChunkPacketInfoAntiXray info = tracked.info;
            if (info != null && !info.getChunkPacket().isReady()) {
                // still being obfuscated, which won't take long
                return;
            }
            this.deactivated.removeFirst();
            final LevelChunk chunk = tracked.level == level ? this.ownedChunk(tracked) : null;
            if (chunk == null) {
                // the client forgot the chunk
                continue;
            }
            // takes the positions from the ready packet, and sends updates that were withheld while it was obfuscated
            this.tryExtract(tracked);
            final int[] positions = tracked.positions;
            final long[] hidden = tracked.hidden;
            for (int i = 0; i < positions.length; ++i) {
                if ((hidden[i >>> 6] & 1L << i) != 0L) {
                    this.sendRealState(chunk, tracked.blockPos(positions[i]));
                    --budget;
                }
            }
        }
    }

    @Override
    public void tick(final CameraSnapshot snapshot) {
        if (this.deactivated.isEmpty()) {
            return;
        }
        // activated again before everything was revealed, the rest is still hidden
        for (final TrackedChunk tracked : this.deactivated.values()) {
            if (tracked.level == snapshot.level && this.chunks.putIfAbsent(tracked.key, tracked) == null && tracked.info != null) {
                this.pending.add(tracked);
            }
        }
        this.deactivated.clear();
        this.needsPass = true;
    }

    // chunk tracking

    public void onChunkSent(final LevelChunk chunk, final ClientboundLevelChunkWithLightPacket packet) {
        final long key = CoordinateUtils.getChunkKey(chunk.getPos());
        this.deactivated.remove(key);
        final ChunkPacketInfo<BlockState> info = packet.chunkData().canvas$chunkPacketInfo;
        if (!(info instanceof final ChunkPacketInfoAntiXray antiXrayInfo) || antiXrayInfo.getRayTraceObfuscation() == null) {
            // sent without ray traced blocks, e.g. to a bypassing player
            this.chunks.remove(key);
            return;
        }
        final TrackedChunk tracked = new TrackedChunk(key, chunk, antiXrayInfo);
        this.chunks.put(key, tracked);
        if (!this.tryExtract(tracked)) {
            this.pending.add(tracked);
        }
    }

    public void onChunkUnload(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        this.chunks.remove(key);
        this.deactivated.remove(key);
    }

    /**
     * Takes the hidden positions from the chunk packet once it is ready, dropping the reference to the packet info
     * (which keeps the chunk and its neighbours alive). Updates of the chunk that were filtered in the meantime are
     * reconciled with the actual positions.
     *
     * @return whether the entry doesn't wait for its packet anymore
     */
    private boolean tryExtract(final TrackedChunk tracked) {
        final ChunkPacketInfoAntiXray info = tracked.info;
        if (info == null) {
            return true;
        }
        if (!info.getChunkPacket().isReady()) {
            return false;
        }
        final int[] hiddenPositions = info.getRayTraceHidden();
        final int[] positions = hiddenPositions == null ? NO_POSITIONS : hiddenPositions;
        final long[] hidden = positions.length == 0 ? NO_BITS : new long[(positions.length + 63) >>> 6];
        Arrays.fill(hidden, -1L);
        if ((positions.length & 63) != 0) {
            hidden[hidden.length - 1] = (1L << positions.length) - 1L;
        }

        IntArrayList wronglyWithheld = null;
        synchronized (tracked) {
            final LongArrayList updates = tracked.pendingUpdates;
            tracked.pendingUpdates = null;
            if (updates != null) {
                // in order, the last update of a position decides what the client has
                for (int i = 0, len = updates.size(); i < len; ++i) {
                    final long update = updates.getLong(i);
                    final int packed = (int) (update >> 1);
                    final boolean withheld = (update & 1L) != 0L;
                    final int index = Arrays.binarySearch(positions, packed);
                    if (index >= 0) {
                        // withheld updates keep the position hidden, the next reveal sends the real state. Otherwise
                        // the client got a state that isn't hidden anymore
                        final long bit = 1L << index;
                        hidden[index >>> 6] = withheld ? hidden[index >>> 6] | bit : hidden[index >>> 6] & ~bit;
                    } else if (withheld) {
                        if (wronglyWithheld == null) {
                            wronglyWithheld = new IntArrayList();
                        }
                        wronglyWithheld.add(packed);
                    }
                }
            }
            tracked.positions = positions;
            tracked.hidden = hidden;
            tracked.hiddenSections = tracked.sectionsOf(true);
            // publishes the fields above, updates that see the cleared info use them instead of being recorded
            tracked.info = null;
        }

        if (wronglyWithheld != null) {
            // turned out not to be hidden, the client must learn about the changes after all
            final LevelChunk chunk = this.ownedChunk(tracked);
            if (chunk != null) {
                for (int i = 0, len = wronglyWithheld.size(); i < len; ++i) {
                    this.sendRealState(chunk, tracked.blockPos(wronglyWithheld.getInt(i)));
                }
            }
        }

        if (positions.length == 0) {
            this.chunks.remove(tracked.key, tracked);
        } else {
            this.needsPass = true;
        }
        return true;
    }

    // filtering, any thread

    /**
     * Whether the state of the block must not be sent to the player because the block is hidden from them. Callable
     * from any thread, the answer may be slightly stale.
     * <p>
     * If the position is hidden but the block changed into something that isn't hidden, the position stops being
     * hidden and the update goes through.
     */
    public boolean isHidden(final int x, final int y, final int z, final BlockState state) {
        final TrackedChunk tracked = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        if (tracked == null) {
            return false;
        }
        final int packed = RayTraceObfuscation.pack(y, z & 15, x & 15);
        if (tracked.info != null) {
            synchronized (tracked) {
                final ChunkPacketInfoAntiXray info = tracked.info;
                if (info != null) {
                    return tracked.isHiddenWhilePending(info, packed, state);
                }
            }
        }
        if (!tracked.mayHideInSection((y >> 4) - tracked.minSectionY)) {
            return false;
        }
        final int index = Arrays.binarySearch(tracked.positions, packed);
        if (index < 0) {
            return false;
        }
        final long[] hidden = tracked.hidden;
        final long bit = 1L << index;
        if ((hidden[index >>> 6] & bit) == 0L) {
            return false;
        }
        if (tracked.obfuscation.isTraced(state)) {
            if (!this.updateWithheld) {
                this.updateWithheld = true;
            }
            return true;
        }
        BITS.getAndBitwiseAnd(hidden, index >>> 6, ~bit);
        return false;
    }

    /**
     * Cheap pre-check for {@link #isHidden}: whether the player may have hidden blocks in the given 16³ section. Same
     * thread rules as {@link #isHidden}.
     *
     * @return {@code false} if no position in the section is hidden from the player
     */
    public boolean mayHideInSection(final int chunkX, final int sectionY, final int chunkZ) {
        final TrackedChunk tracked = this.chunks.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        return tracked != null && (tracked.info != null || tracked.mayHideInSection(sectionY - tracked.minSectionY));
    }

    @FunctionalInterface
    public interface PositionFilter {
        boolean test(int x, int y, int z);
    }

    /**
     * Whether a block in the box is currently hidden from the player and matches the filter. Same thread rules as
     * {@link #isHidden}. Chunks whose packet is still being obfuscated count as matching, their positions aren't
     * known yet.
     */
    public boolean anyHidden(final ServerLevel level, final int minX, final int minY, final int minZ,
                             final int maxX, final int maxY, final int maxZ, final PositionFilter filter) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; ++chunkX) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; ++chunkZ) {
                final TrackedChunk tracked = this.chunks.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
                if (tracked == null || tracked.level != level) {
                    continue;
                }
                if (tracked.info != null) {
                    return true;
                }
                if (!tracked.mayHideInSections((minY >> 4) - tracked.minSectionY, (maxY >> 4) - tracked.minSectionY)) {
                    continue;
                }
                final int[] positions = tracked.positions;
                final long[] hidden = tracked.hidden;
                for (int i = 0; i < positions.length; ++i) {
                    if ((hidden[i >>> 6] & 1L << i) == 0L) {
                        continue;
                    }
                    final int packed = positions[i];
                    final int y = RayTraceObfuscation.unpackY(packed);
                    final int x = tracked.blockX(packed);
                    final int z = tracked.blockZ(packed);
                    if (y >= minY && y <= maxY && x >= minX && x <= maxX && z >= minZ && z <= maxZ && filter.test(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // visibility passes

    @Override
    public boolean prepareAsync(final CameraSnapshot snapshot) {
        for (int i = 0; i < this.pending.size(); ) {
            final TrackedChunk tracked = this.pending.get(i);
            if (this.chunks.get(tracked.key) != tracked || this.tryExtract(tracked)) {
                final int last = this.pending.size() - 1;
                this.pending.set(i, this.pending.get(last));
                this.pending.remove(last);
            } else {
                ++i;
            }
        }

        if (this.updateWithheld) {
            this.updateWithheld = false;
            this.needsPass = true;
        }
        final WorldConfig.Anticheat.RayTraceAntiXray config = this.owner.config().rayTraceAntiXray;
        if (!this.needsPass && !this.viewChanged(snapshot, config)
            && snapshot.tick - this.passTick < config.updateIntervalTicks && snapshot.tick >= this.passTick) {
            return false;
        }
        this.needsPass = false;
        if (this.chunks.isEmpty()) {
            return false;
        }

        this.passX = snapshot.eyeX;
        this.passY = snapshot.eyeY;
        this.passZ = snapshot.eyeZ;
        this.passYaw = snapshot.yRot;
        this.passPitch = snapshot.xRot;
        this.passTick = snapshot.tick;

        this.jobDistance = config.rayTraceDistance;
        this.jobRehide = config.rehideBlocks;
        this.jobRehideDistanceSquared = config.rehideDistance * config.rehideDistance;
        this.jobThirdPerson = config.rayTraceThirdPerson;
        this.jobFrustumCulling = config.frustumCulling;
        this.jobLavaObscures = snapshot.level.paperConfig().anticheat.antiXray.lavaObscures;
        return true;
    }

    private boolean viewChanged(final CameraSnapshot snapshot, final WorldConfig.Anticheat.RayTraceAntiXray config) {
        if (Double.isNaN(this.passX) || snapshot.distanceSquared(this.passX, this.passY, this.passZ) > MOVE_THRESHOLD_SQUARED) {
            return true;
        }
        // the view direction only matters for the frustum and the third person cameras
        return (config.frustumCulling || config.rayTraceThirdPerson)
            && (Math.abs(Mth.wrapDegrees(snapshot.yRot - this.passYaw)) > ROTATION_THRESHOLD || Math.abs(snapshot.xRot - this.passPitch) > ROTATION_THRESHOLD);
    }

    @Override
    public boolean isLatencySensitive() {
        // passes can take a while, blocks being revealed a little later is better than entities popping in late
        return false;
    }

    @Override
    public void runAsync(final CameraSnapshot snapshot, final OcclusionAccess access) {
        // a discarded job may have left references behind
        Arrays.fill(this.resultChunks, 0, this.resultCount, null);
        this.resultCount = 0;

        // the client's camera isn't stopped by lava, the cameras are shared with the other modules
        final double @Nullable [] cameras = this.jobThirdPerson ? snapshot.cameras(access) : null;
        access.setLavaObscures(this.jobLavaObscures);
        this.trace(snapshot, access, cameras);
    }

    private void trace(final CameraSnapshot snapshot, final OcclusionAccess access, final double @Nullable [] cameras) {
        // rays end at the cameras, so the cells around the eyes are read over and over
        final OcclusionCache cache = OcclusionCache.get(access, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ);
        final double distance = this.jobDistance;
        final double distanceSquared = distance * distance;
        final int minChunkX = Mth.floor(snapshot.eyeX - distance) >> 4;
        final int maxChunkX = Mth.floor(snapshot.eyeX + distance) >> 4;
        final int minChunkZ = Mth.floor(snapshot.eyeZ - distance) >> 4;
        final int maxChunkZ = Mth.floor(snapshot.eyeZ + distance) >> 4;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
            final double dz = Math.max(0.0, Math.max((chunkZ << 4) - snapshot.eyeZ, snapshot.eyeZ - ((chunkZ << 4) + 16)));
            for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
                final double dx = Math.max(0.0, Math.max((chunkX << 4) - snapshot.eyeX, snapshot.eyeX - ((chunkX << 4) + 16)));
                if (dx * dx + dz * dz > distanceSquared) {
                    continue;
                }
                final TrackedChunk tracked = this.chunks.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
                if (tracked != null && tracked.info == null) {
                    this.traceChunk(tracked, snapshot, cache, cameras, distanceSquared);
                }
            }
        }
    }

    private void traceChunk(final TrackedChunk tracked, final CameraSnapshot snapshot, final BlockOcclusion access,
                            final double @Nullable [] cameras, final double distanceSquared) {
        final boolean rehide = this.jobRehide;
        final int[] positions = tracked.positions;
        final long[] hidden = tracked.hidden;
        final int baseX = tracked.chunkX << 4;
        final int baseZ = tracked.chunkZ << 4;
        for (int i = 0; i < positions.length; ++i) {
            final boolean isHidden = (hidden[i >>> 6] & 1L << i) != 0L;
            if (!isHidden && !rehide) {
                continue;
            }
            final int packed = positions[i];
            final int x = baseX + RayTraceObfuscation.unpackRelX(packed);
            final int y = RayTraceObfuscation.unpackY(packed);
            final int z = baseZ + RayTraceObfuscation.unpackRelZ(packed);
            final double blockDistanceSquared = snapshot.distanceSquared(x + 0.5, y + 0.5, z + 0.5);
            if (blockDistanceSquared > distanceSquared) {
                // out of range blocks keep their state
                continue;
            }
            final boolean visible = (!rehide || blockDistanceSquared < this.jobRehideDistanceSquared)
                && this.isVisible(access, snapshot, cameras, x, y, z);
            if (visible == isHidden) {
                this.addResult(tracked, visible ? i : ~i);
            }
        }
    }

    private boolean isVisible(final BlockOcclusion access, final CameraSnapshot snapshot, final double @Nullable [] cameras,
                              final int x, final int y, final int z) {
        if (cameras == null) {
            return this.isVisibleFrom(access, x, y, z, snapshot.eyeX, snapshot.eyeY, snapshot.eyeZ, snapshot.lookX, snapshot.lookY, snapshot.lookZ);
        }
        for (int i = 0; i < cameras.length; i += 3) {
            final double camX = cameras[i];
            final double camY = cameras[i + 1];
            final double camZ = cameras[i + 2];
            if (i != 0 && camX == snapshot.eyeX && camY == snapshot.eyeY && camZ == snapshot.eyeZ) {
                // a third person camera pulled all the way into the head
                continue;
            }
            // the cameras are the eyes, the back camera looking ahead and the front camera looking back
            final double sign = i == 6 ? -1.0 : 1.0;
            if (this.isVisibleFrom(access, x, y, z, camX, camY, camZ, snapshot.lookX * sign, snapshot.lookY * sign, snapshot.lookZ * sign)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the block is in front of the camera, if the view direction matters, and can be seen from it, see
     * {@link FaceVisibility}.
     */
    private boolean isVisibleFrom(final BlockOcclusion access, final int x, final int y, final int z,
                                  final double camX, final double camY, final double camZ,
                                  final double dirX, final double dirY, final double dirZ) {
        if (this.jobFrustumCulling && (x + 0.5 - camX) * dirX + (y + 0.5 - camY) * dirY + (z + 0.5 - camZ) * dirZ < -FRUSTUM_MARGIN) {
            return false;
        }

        return FaceVisibility.isVisibleFrom(access, x, y, z, camX, camY, camZ);
    }

    private void addResult(final TrackedChunk tracked, final int index) {
        final int count = this.resultCount;
        if (count == this.resultIndices.length) {
            this.resultChunks = Arrays.copyOf(this.resultChunks, count << 1);
            this.resultIndices = Arrays.copyOf(this.resultIndices, count << 1);
        }
        this.resultChunks[count] = tracked;
        this.resultIndices[count] = index;
        this.resultCount = count + 1;
    }

    @Override
    public void applyResults(final CameraSnapshot snapshot) {
        final int count = this.resultCount;
        this.resultCount = 0;
        final boolean rehide = this.owner.config().rayTraceAntiXray.rehideBlocks;
        TrackedChunk revealed = null;
        for (int i = 0; i < count; ++i) {
            final TrackedChunk tracked = this.resultChunks[i];
            this.resultChunks[i] = null;
            if (tracked != revealed && revealed != null) {
                this.onRevealed(revealed, rehide);
                revealed = null;
            }
            if (this.chunks.get(tracked.key) != tracked || tracked.level != this.owner.player.level()) {
                // unloaded or sent again since
                continue;
            }
            final int result = this.resultIndices[i];
            if (result < 0) {
                this.rehide(tracked, ~result);
            } else if (this.reveal(tracked, result)) {
                revealed = tracked;
            }
        }
        if (revealed != null) {
            this.onRevealed(revealed, rehide);
        }
    }

    /**
     * Sends the current state of a hidden block.
     *
     * @return whether the block was revealed
     */
    private boolean reveal(final TrackedChunk tracked, final int index) {
        final int packed = tracked.positions[index];
        final LevelChunk chunk = this.ownedChunk(tracked);
        if (chunk == null || this.owner.antiFreecam.isMasked(tracked.blockX(packed), RayTraceObfuscation.unpackY(packed), tracked.blockZ(packed))) {
            // retried by the next pass
            return false;
        }
        final long bit = 1L << index;
        if (((long) BITS.getAndBitwiseAnd(tracked.hidden, index >>> 6, ~bit) & bit) == 0L) {
            // the client got the real state already
            return false;
        }
        this.sendRealState(chunk, tracked.blockPos(packed));
        return true;
    }

    /**
     * Hides a revealed block again, unless it changed into something that isn't hidden.
     */
    private void rehide(final TrackedChunk tracked, final int index) {
        final int packed = tracked.positions[index];
        final LevelChunk chunk = this.ownedChunk(tracked);
        if (chunk == null) {
            return;
        }
        final BlockPos pos = tracked.blockPos(packed);
        if (!tracked.obfuscation.isTraced(chunk.getBlockState(pos))) {
            return;
        }
        // the same block paper hides with: stone, deepslate, netherrack or end stone
        final BlockState[] hideStates = tracked.level.chunkPacketBlockController.getPresetBlockStates(tracked.level, chunk.getPos(), pos.getY() >> 4);
        if (hideStates == null) {
            return;
        }
        // mark the section first, so filtering never skips a section with hidden blocks
        tracked.hiddenSections |= TrackedChunk.sectionBit((pos.getY() >> 4) - tracked.minSectionY);
        final long bit = 1L << index;
        if (((long) BITS.getAndBitwiseOr(tracked.hidden, index >>> 6, bit) & bit) == 0L) {
            this.owner.player.connection.send(new ClientboundBlockUpdatePacket(pos, hideStates[0]));
        }
    }

    /**
     * Shrinks the sections with hidden blocks after reveals, and stops tracking chunks without hidden blocks when
     * nothing will be hidden again.
     */
    private void onRevealed(final TrackedChunk tracked, final boolean rehide) {
        final long sections = tracked.sectionsOf(false);
        tracked.hiddenSections = sections;
        if (sections == 0L && !rehide && !tracked.anyHidden()) {
            this.chunks.remove(tracked.key, tracked);
        }
    }

    private @Nullable LevelChunk ownedChunk(final TrackedChunk tracked) {
        // the chunks a player can see belong to the player's region, but never touch another region's chunks
        if (!TickThread.isTickThreadFor(tracked.level, tracked.chunkX, tracked.chunkZ)) {
            return null;
        }
        return tracked.level.getChunkSource().getChunkAtIfLoadedImmediately(tracked.chunkX, tracked.chunkZ);
    }

    private void sendRealState(final LevelChunk chunk, final BlockPos pos) {
        if (this.owner.storage.isHiddenAt(pos.getX(), pos.getY(), pos.getZ())) {
            // storage hiding reveals it once it is in sight, anti-xray no longer hides it then
            return;
        }
        final BlockState state = chunk.getBlockState(pos);
        this.owner.player.connection.send(new ClientboundBlockUpdatePacket(pos, this.owner.disguiseBlock(pos.getX(), pos.getY(), pos.getZ(), state)));
        if (state.hasBlockEntity()) {
            final BlockEntity blockEntity = chunk.getBlockEntity(pos);
            final Packet<ClientGamePacketListener> packet = blockEntity == null ? null : blockEntity.getUpdatePacket();
            if (packet != null) {
                this.owner.player.connection.send(packet);
            }
        }
    }

    /**
     * A chunk sent to the player with hidden blocks.
     */
    private static final class TrackedChunk {

        final long key;
        final int chunkX;
        final int chunkZ;
        final int minSectionY;
        final ServerLevel level;
        final RayTraceObfuscation obfuscation;

        /**
         * The info of the packet while it is being obfuscated. Once cleared, {@link #positions} and {@link #hidden}
         * are valid.
         */
        volatile @Nullable ChunkPacketInfoAntiXray info;
        int[] positions = NO_POSITIONS;
        /**
         * Bit {@code i} is set while {@code positions[i]} is hidden from the player. Only changed atomically.
         */
        long[] hidden = NO_BITS;
        /**
         * Bit {@code i} is set if section index {@code i} may contain hidden positions. Only written on the owning
         * region thread, only ever cleared after the positions were revealed.
         */
        volatile long hiddenSections;
        /**
         * Positions of updates filtered while the packet was being obfuscated, shifted left by one with the lowest
         * bit set if the update was withheld. Guarded by this.
         */
        @Nullable LongArrayList pendingUpdates;

        TrackedChunk(final long key, final LevelChunk chunk, final ChunkPacketInfoAntiXray info) {
            this.key = key;
            this.chunkX = chunk.getPos().x();
            this.chunkZ = chunk.getPos().z();
            this.minSectionY = chunk.getMinSectionY();
            this.level = (ServerLevel) chunk.getLevel();
            this.obfuscation = info.getRayTraceObfuscation();
            this.info = info;
        }

        static long sectionBit(final int sectionIndex) {
            return sectionIndex >= 0 && sectionIndex < Long.SIZE ? 1L << sectionIndex : 0L;
        }

        boolean mayHideInSections(final int fromSectionIndex, final int toSectionIndex) {
            for (int sectionIndex = fromSectionIndex; sectionIndex <= toSectionIndex; ++sectionIndex) {
                if (this.mayHideInSection(sectionIndex)) {
                    return true;
                }
            }
            return false;
        }

        boolean mayHideInSection(final int sectionIndex) {
            // sections beyond what the mask covers are never ruled out
            return sectionIndex >= Long.SIZE || sectionIndex >= 0 && (this.hiddenSections >>> sectionIndex & 1L) != 0L;
        }

        long sectionsOf(final boolean all) {
            final int[] positions = this.positions;
            final long[] hidden = this.hidden;
            long ret = 0L;
            for (int i = 0; i < positions.length; ++i) {
                if (all || (hidden[i >>> 6] & 1L << i) != 0L) {
                    ret |= sectionBit((RayTraceObfuscation.unpackY(positions[i]) >> 4) - this.minSectionY);
                }
            }
            return ret;
        }

        boolean anyHidden() {
            for (final long word : this.hidden) {
                if (word != 0L) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Decides about an update while the packet is still being obfuscated, recording it so it can be reconciled
         * with the hidden positions later. Must hold the lock.
         */
        boolean isHiddenWhilePending(final ChunkPacketInfoAntiXray info, final int packed, final BlockState state) {
            final boolean traced = this.obfuscation.isTraced(state);
            if (info.getChunkPacket().isReady()) {
                // every recorded position starts hidden, updates of other positions are never hidden
                final int[] positions = info.getRayTraceHidden();
                if (positions == null || Arrays.binarySearch(positions, packed) < 0) {
                    return false;
                }
            }
            LongArrayList updates = this.pendingUpdates;
            if (updates == null) {
                this.pendingUpdates = updates = new LongArrayList();
            }
            // until the positions are known, every traced block may be hidden
            updates.add((long) packed << 1 | (traced ? 1L : 0L));
            return traced;
        }

        int blockX(final int packed) {
            return (this.chunkX << 4) + RayTraceObfuscation.unpackRelX(packed);
        }

        int blockZ(final int packed) {
            return (this.chunkZ << 4) + RayTraceObfuscation.unpackRelZ(packed);
        }

        BlockPos blockPos(final int packed) {
            return new BlockPos(this.blockX(packed), RayTraceObfuscation.unpackY(packed), this.blockZ(packed));
        }
    }
}
