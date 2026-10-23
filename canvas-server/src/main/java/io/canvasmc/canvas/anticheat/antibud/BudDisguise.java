package io.canvasmc.canvas.anticheat.antibud;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The states anti-bud disguises: medium and large amethyst buds and amethyst clusters are sent as a small amethyst
 * bud facing the same way, waterlogged the same way. Only the size, and with it the emitted light, differs.
 */
public final class BudDisguise {

    private static final Reference2ReferenceOpenHashMap<BlockState, BlockState> DISGUISES = new Reference2ReferenceOpenHashMap<>();

    static {
        for (final Block block : new Block[] {Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER}) {
            for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
                DISGUISES.put(state, Blocks.SMALL_AMETHYST_BUD.defaultBlockState()
                    .setValue(AmethystClusterBlock.FACING, state.getValue(AmethystClusterBlock.FACING))
                    .setValue(AmethystClusterBlock.WATERLOGGED, state.getValue(AmethystClusterBlock.WATERLOGGED)));
            }
        }
    }

    private BudDisguise() {
    }

    /**
     * @return whether anti-bud sends the state disguised to players that are far away
     */
    public static boolean isDisguised(final BlockState state) {
        final Block block = state.getBlock();
        return block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD || block == Blocks.AMETHYST_CLUSTER;
    }

    /**
     * @return whether the state is a bud or a cluster: disguised, or a small bud, which is its own disguise. A small
     * bud growing must not show either
     */
    public static boolean isBud(final BlockState state) {
        return state.getBlock() == Blocks.SMALL_AMETHYST_BUD || isDisguised(state);
    }

    /**
     * @return the small bud the state is disguised as, or the state itself if it isn't disguised
     */
    public static BlockState disguise(final BlockState state) {
        final BlockState ret = DISGUISES.get(state);
        return ret == null ? state : ret;
    }

    /**
     * Cheap check of the section's palette.
     *
     * @return {@code false} if the section contains no disguised state
     */
    public static boolean mayContain(final LevelChunkSection section) {
        return !section.hasOnlyAir() && section.maybeHas(BudDisguise::isDisguised);
    }

    /**
     * Cheap check of the section's palette.
     *
     * @return {@code false} if the section contains no bud at all
     */
    public static boolean mayContainBuds(final LevelChunkSection section) {
        return !section.hasOnlyAir() && section.maybeHas(BudDisguise::isBud);
    }
}
