package io.canvasmc.canvas.world;

import io.canvasmc.canvas.GlobalConfiguration;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import org.jspecify.annotations.Nullable;

/**
 * Entity movement LOD, see {@link GlobalConfiguration.EntityTracking.MovementLod}: movement and head rotation of an
 * entity are sent less often to players far from it.
 * <p>
 * The relative moves a player doesn't get right away are added up exactly, in the units of the packets, and sent as
 * one move with the next update that goes out to them, or as a position sync if the sum doesn't fit a relative move.
 * Rotation, head rotation and the on ground flag are sent as last known. Nothing pending is ever left behind: it goes
 * out once its interval is up even if the entity stopped moving ({@link #tick}), before any other packet about the
 * entity so packets stay in order, and is dropped when an absolute position makes it obsolete.
 * <p>
 * One instance per tracked entity, only used on the thread that ticks the entity.
 */
public final class MovementLod {

    private static final int OTHER = 0;
    private static final int MOVE = 1;
    private static final int HEAD = 2;
    /**
     * Packets that give the absolute position, making pending relative moves obsolete.
     */
    private static final int ABSOLUTE = 3;

    private final Entity entity;
    private final ServerEntity serverEntity;
    private final Reference2ObjectOpenHashMap<ServerPlayerConnection, Viewer> viewers = new Reference2ObjectOpenHashMap<>();
    /**
     * Viewers with something pending.
     */
    private int pending;
    private int tick;

    public MovementLod(final Entity entity, final ServerEntity serverEntity) {
        this.entity = entity;
        this.serverEntity = serverEntity;
    }

    private static GlobalConfiguration.EntityTracking.MovementLod config() {
        return GlobalConfiguration.getInstance().entityTracking.movementLod;
    }

    private boolean isActive(final GlobalConfiguration.EntityTracking.MovementLod config) {
        if (!config.enabled) {
            return false;
        }
        final var worldData = ((net.minecraft.server.level.ServerLevel) this.entity.level()).getCurrentWorldData();
        return worldData != null && worldData.getLocalPlayers().size() >= config.startAtPlayers;
    }

    private static int kind(final Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket) {
            return MOVE;
        }
        if (packet instanceof ClientboundRotateHeadPacket) {
            return HEAD;
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket || packet instanceof ClientboundMoveMinecartPacket
            || packet instanceof ClientboundTeleportEntityPacket teleport && teleport.relatives().isEmpty()) {
            return ABSOLUTE;
        }
        return OTHER;
    }

    private double distanceSquared(final ServerPlayer player) {
        final double dx = player.getX() - this.entity.getX();
        final double dz = player.getZ() - this.entity.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Sends a packet about the entity to the players tracking it that match the filter.
     *
     * @return whether the packet was handled, otherwise the caller sends it to every one of them as usual
     */
    public boolean send(final Set<ServerPlayerConnection> seenBy, final Packet<? super ClientGamePacketListener> packet,
                        final @Nullable Predicate<ServerPlayer> filter) {
        final int kind = kind(packet);
        final GlobalConfiguration.EntityTracking.MovementLod config = config();
        final boolean active = (kind == MOVE || kind == HEAD) && this.isActive(config);
        if (!active) {
            if (this.pending != 0) {
                // what is pending goes out first, unless the packet makes it obsolete
                this.settle(seenBy, filter, kind == ABSOLUTE);
            }
            return false;
        }
        for (final ServerPlayerConnection connection : seenBy) {
            if (filter != null && !filter.test(connection.getPlayer())) {
                continue;
            }
            final int interval = config.intervalFor(this.distanceSquared(connection.getPlayer()));
            Viewer viewer = this.viewers.get(connection);
            if (interval <= 1 && (viewer == null || !viewer.hasPending())) {
                connection.send(packet);
                if (viewer != null) {
                    viewer.markSent(kind, this.tick);
                }
                continue;
            }
            if (viewer == null) {
                this.viewers.put(connection, viewer = new Viewer());
            }
            final boolean wasPending = viewer.hasPending();
            viewer.add(packet, kind);
            if (interval <= 1 || this.tick - viewer.lastSent(kind) >= interval) {
                this.flush(connection, viewer, kind);
            }
            this.updatePending(viewer, wasPending);
        }
        return true;
    }

    /**
     * Sends what is pending for players whose interval is up. Called once per tick after the entity's changes were
     * sent.
     */
    public void tick(final Set<ServerPlayerConnection> seenBy) {
        ++this.tick;
        if (this.pending == 0) {
            return;
        }
        final GlobalConfiguration.EntityTracking.MovementLod config = config();
        final boolean active = this.isActive(config);
        for (final var iterator = this.viewers.reference2ObjectEntrySet().fastIterator(); iterator.hasNext(); ) {
            final Reference2ObjectMap.Entry<ServerPlayerConnection, Viewer> entry = iterator.next();
            final Viewer viewer = entry.getValue();
            if (!viewer.hasPending()) {
                continue;
            }
            final ServerPlayerConnection connection = entry.getKey();
            if (!seenBy.contains(connection)) {
                --this.pending;
                iterator.remove();
                continue;
            }
            final int interval = active ? config.intervalFor(this.distanceSquared(connection.getPlayer())) : 1;
            if (viewer.hasMove && this.tick - viewer.lastMove >= interval) {
                this.flush(connection, viewer, MOVE);
            }
            if (viewer.head != null && this.tick - viewer.lastHead >= interval) {
                this.flush(connection, viewer, HEAD);
            }
            if (!viewer.hasPending()) {
                --this.pending;
            }
        }
    }

    /**
     * Sends, or drops if {@code obsolete}, the pending moves of the players matching the filter.
     */
    private void settle(final Set<ServerPlayerConnection> seenBy, final @Nullable Predicate<ServerPlayer> filter, final boolean obsolete) {
        for (final var iterator = this.viewers.reference2ObjectEntrySet().fastIterator(); iterator.hasNext(); ) {
            final Reference2ObjectMap.Entry<ServerPlayerConnection, Viewer> entry = iterator.next();
            final Viewer viewer = entry.getValue();
            final ServerPlayerConnection connection = entry.getKey();
            if (!viewer.hasPending() || filter != null && !filter.test(connection.getPlayer())) {
                continue;
            }
            if (!seenBy.contains(connection)) {
                --this.pending;
                iterator.remove();
                continue;
            }
            if (obsolete) {
                viewer.clearMove();
            } else if (viewer.hasMove) {
                this.flush(connection, viewer, MOVE);
            }
            if (viewer.head != null) {
                this.flush(connection, viewer, HEAD);
            }
            --this.pending;
        }
    }

    private void updatePending(final Viewer viewer, final boolean wasPending) {
        final boolean pending = viewer.hasPending();
        if (pending != wasPending) {
            this.pending += pending ? 1 : -1;
        }
    }

    private void flush(final ServerPlayerConnection connection, final Viewer viewer, final int kind) {
        if (kind == HEAD) {
            if (viewer.head != null) {
                connection.send(viewer.head);
                viewer.head = null;
            }
            viewer.lastHead = this.tick;
            return;
        }
        if (viewer.hasMove) {
            final int id = this.entity.getId();
            final Packet<? super ClientGamePacketListener> packet;
            if (!viewer.hasPos) {
                packet = new ClientboundMoveEntityPacket.Rot(id, viewer.yRot, viewer.xRot, viewer.onGround);
            } else if (fitsShort(viewer.xa) && fitsShort(viewer.ya) && fitsShort(viewer.za)) {
                packet = viewer.hasRot
                    ? new ClientboundMoveEntityPacket.PosRot(id, (short) viewer.xa, (short) viewer.ya, (short) viewer.za, viewer.yRot, viewer.xRot, viewer.onGround)
                    : new ClientboundMoveEntityPacket.Pos(id, (short) viewer.xa, (short) viewer.ya, (short) viewer.za, viewer.onGround);
            } else {
                // too far for a relative move: where every relative move sent so far puts the entity
                packet = new ClientboundEntityPositionSyncPacket(id, new PositionMoveRotation(this.serverEntity.canvas$positionBase(),
                    this.entity.getDeltaMovement(), this.entity.getYRot(), this.entity.getXRot()), viewer.onGround);
            }
            connection.send(packet);
            viewer.clearMove();
        }
        viewer.lastMove = this.tick;
    }

    private static boolean fitsShort(final long value) {
        return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
    }

    /**
     * Forgets a player that stopped tracking the entity.
     */
    public void remove(final ServerPlayerConnection connection) {
        final Viewer viewer = this.viewers.remove(connection);
        if (viewer != null && viewer.hasPending()) {
            --this.pending;
        }
    }

    public void clear() {
        this.viewers.clear();
        this.pending = 0;
    }

    /**
     * What one player didn't get yet.
     */
    private static final class Viewer {
        int lastMove = Integer.MIN_VALUE / 2;
        int lastHead = Integer.MIN_VALUE / 2;
        boolean hasMove;
        boolean hasPos;
        boolean hasRot;
        long xa;
        long ya;
        long za;
        byte yRot;
        byte xRot;
        boolean onGround;
        @Nullable Packet<? super ClientGamePacketListener> head;

        boolean hasPending() {
            return this.hasMove || this.head != null;
        }

        int lastSent(final int kind) {
            return kind == HEAD ? this.lastHead : this.lastMove;
        }

        void markSent(final int kind, final int tick) {
            if (kind == HEAD) {
                this.lastHead = tick;
            } else {
                this.lastMove = tick;
            }
        }

        void add(final Packet<? super ClientGamePacketListener> packet, final int kind) {
            if (kind == HEAD) {
                this.head = packet;
                return;
            }
            final ClientboundMoveEntityPacket move = (ClientboundMoveEntityPacket) packet;
            this.hasMove = true;
            this.onGround = move.isOnGround();
            if (move.hasRotation()) {
                this.hasRot = true;
                this.yRot = move.canvas$yRot();
                this.xRot = move.canvas$xRot();
            }
            if (move.hasPosition()) {
                this.hasPos = true;
                this.xa += move.getXa();
                this.ya += move.getYa();
                this.za += move.getZa();
            }
        }

        void clearMove() {
            this.hasMove = false;
            this.hasPos = false;
            this.hasRot = false;
            this.xa = 0L;
            this.ya = 0L;
            this.za = 0L;
        }
    }
}
