package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.anticheat.freecam.FreecamGuard;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Per-player filtering of block related packets that are broadcast to everyone who has a chunk loaded.
 * <p>
 * Broadcast packets are shared between all receivers, so they are never modified in place: players that must not
 * receive (part of) a packet get a filtered copy, or nothing. When nobody filters anything the original list or
 * packet is returned without allocating.
 */
public final class VisibilityFilters {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();

    private VisibilityFilters() {
    }

    /**
     * @return the players that may receive the given state at the given position, which is the given list itself if
     * none of them hides the position
     */
    public static List<ServerPlayer> receiversOf(final List<ServerPlayer> players, final BlockPos pos, final BlockState state) {
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();
        List<ServerPlayer> ret = null;
        for (int i = 0, len = players.size(); i < len; ++i) {
            final ServerPlayer player = players.get(i);
            if (player.canvas$visibility.isBlockHidden(x, y, z, state)) {
                if (ret == null) {
                    ret = new ArrayList<>(players.subList(0, i));
                }
            } else if (ret != null) {
                ret.add(player);
            }
        }
        return ret == null ? players : ret;
    }

    /**
     * Sends a multi block update to every player, leaving out the positions each player must not see.
     */
    public static void broadcastSectionUpdate(final List<ServerPlayer> players, final ClientboundSectionBlocksUpdatePacket packet) {
        for (final ServerPlayer player : players) {
            final ClientboundSectionBlocksUpdatePacket filtered = packet.canvas$filter(player.canvas$visibility);
            if (filtered != null) {
                player.connection.send(filtered);
            }
        }
    }

    /**
     * Sends a light update to every player, leaving out the sections each player must not see.
     */
    public static void broadcastLightUpdate(final List<ServerPlayer> players, final ClientboundLightUpdatePacket packet) {
        for (final ServerPlayer player : players) {
            final ClientboundLightUpdatePacket filtered = player.canvas$visibility.filterLightUpdate(packet);
            if (filtered != null) {
                player.connection.send(filtered);
            }
        }
    }

    /**
     * Mirrors {@link net.minecraft.server.players.PlayerList#broadcast} for block events (chest lids, pistons,
     * note blocks, spawner resets...), which would otherwise give away hidden blocks to everyone within 64 blocks.
     */
    public static void broadcastBlockEvent(final ServerLevel level, final BlockPos pos, final ClientboundBlockEventPacket packet) {
        final double range = 64.0;
        final BlockState state = level.getBlockState(pos);
        for (final ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;
            }
            final double dx = pos.getX() - player.getX();
            final double dy = pos.getY() - player.getY();
            final double dz = pos.getZ() - player.getZ();
            if (dx * dx + dy * dy + dz * dz < range * range && !player.canvas$visibility.isBlockHidden(pos.getX(), pos.getY(), pos.getZ(), state)) {
                player.connection.send(packet);
            }
        }
    }

    /**
     * Block breaking progress (the crack overlay) is broadcast to everyone within 32 blocks. It must not reach players
     * the breaker is hidden from by anti-ESP, nor players the block itself is hidden from.
     */
    public static boolean mayReceiveBlockDestruction(final ServerPlayer viewer, final @Nullable Entity breaker, final BlockPos pos) {
        if (breaker != null && breaker != viewer && viewer.canvas$visibility.isEntityHidden(breaker)) {
            return false;
        }
        final BlockState state = viewer.level().getBlockStateIfLoaded(pos);
        return state == null || !viewer.canvas$visibility.isBlockHidden(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    /**
     * Mirrors {@link net.minecraft.server.players.PlayerList#broadcast} for the block break level events, which carry
     * the id of the broken state. Players that the block was hidden from receive the event for the hide block
     * instead, so they still hear and see a block break without learning what it was.
     */
    public static void broadcastBlockBreakEvent(final ServerLevel level, final @Nullable Player except, final int type, final BlockPos pos, final int data) {
        final double range = 64.0;
        final BlockState state = Block.stateById(data);
        final ClientboundLevelEventPacket packet = new ClientboundLevelEventPacket(type, pos, data, false);
        ClientboundLevelEventPacket disguised = null;
        for (final ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player == except || player.level() != level) {
                continue;
            }
            final double dx = pos.getX() - player.getX();
            final double dy = pos.getY() - player.getY();
            final double dz = pos.getZ() - player.getZ();
            if (dx * dx + dy * dy + dz * dz >= range * range) {
                continue;
            }
            if (except != null && !player.getBukkitEntity().canSee(except.getBukkitEntity())) {
                continue;
            }
            if (player.canvas$visibility.isBlockHidden(pos.getX(), pos.getY(), pos.getZ(), state)) {
                if (disguised == null) {
                    disguised = new ClientboundLevelEventPacket(type, pos, Block.getId(hideBlock(level, pos.getY())), false);
                }
                player.connection.send(disguised);
            } else {
                player.connection.send(packet);
            }
        }
    }

    /**
     * Creates the block update that corrects a single player's view of a block, like after an interaction the server
     * rejected. Hidden blocks are sent disguised, so modified clients can't probe them through walls with interaction
     * packets.
     */
    public static ClientboundBlockUpdatePacket blockUpdateFor(final ServerPlayer player, final Level level, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (player.canvas$visibility.isBlockHidden(pos.getX(), pos.getY(), pos.getZ(), state)) {
            return new ClientboundBlockUpdatePacket(pos, hideBlock(level, pos.getY()));
        }
        return new ClientboundBlockUpdatePacket(pos, state);
    }

    /**
     * Called when a block changed between occluding and not occluding, which may change what players can potentially
     * see. Players the chunk was sent to get their anti-freecam visibility checked soon, instead of at the next
     * periodic update. Changes inside sections masked for a player are skipped, no view leads into those.
     */
    public static void onOpacityChange(final ServerLevel level, final BlockPos pos) {
        if (!level.canvasConfig().anticheat.antiFreecam.enabled) {
            return;
        }
        final int chunkX = pos.getX() >> 4;
        final int sectionY = pos.getY() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        final List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunkX, chunkZ), false);
        for (int i = 0, len = players.size(); i < len; ++i) {
            final FreecamGuard antiFreecam = players.get(i).canvas$visibility.antiFreecam;
            if (!antiFreecam.isSectionMasked(chunkX, sectionY, chunkZ)) {
                antiFreecam.onOpacityChange();
            }
        }
    }

    /**
     * The block hidden blocks are disguised as, matching paper's anti-xray engine mode 1.
     */
    public static BlockState hideBlock(final Level level, final int y) {
        return switch (level.getWorld().getEnvironment()) {
            case NETHER -> NETHERRACK;
            case THE_END -> END_STONE;
            default -> y < 0 ? DEEPSLATE : STONE;
        };
    }
}
