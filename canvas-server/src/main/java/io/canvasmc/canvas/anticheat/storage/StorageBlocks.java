package io.canvasmc.canvas.anticheat.storage;

import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.anticheat.AnticheatExecutor;
import io.canvasmc.canvas.anticheat.VisibilityFilters;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The blocks storage hiding hides, compiled from the configuration of a world, and what they are sent as.
 * <p>
 * By default these are all blocks that store items: chests (copper chests included), trapped chests, ender chests,
 * barrels, shulker boxes, hoppers, dispensers, droppers, crafters, furnaces, smokers, blast furnaces, brewing stands,
 * chiseled bookshelves, shelves and decorated pots. Instances are immutable and shared by all threads.
 */
public final class StorageBlocks {

    private static final BlockEntityType<?>[] STORAGE_TYPES = {
        BlockEntityTypes.CHEST, BlockEntityTypes.TRAPPED_CHEST, BlockEntityTypes.ENDER_CHEST, BlockEntityTypes.BARREL,
        BlockEntityTypes.SHULKER_BOX, BlockEntityTypes.HOPPER, BlockEntityTypes.DISPENSER, BlockEntityTypes.DROPPER,
        BlockEntityTypes.CRAFTER, BlockEntityTypes.FURNACE, BlockEntityTypes.SMOKER, BlockEntityTypes.BLAST_FURNACE,
        BlockEntityTypes.BREWING_STAND, BlockEntityTypes.CHISELED_BOOKSHELF, BlockEntityTypes.SHELF, BlockEntityTypes.DECORATED_POT
    };
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};

    private static final Map<WorldConfig.Anticheat.AntiStorageEsp, StorageBlocks> COMPILED = new IdentityHashMap<>();
    private static volatile @Nullable StorageBlocks last;
    /**
     * The states any world hides, for packets that don't know their world.
     */
    private static volatile boolean[] any = new boolean[0];

    private final WorldConfig.Anticheat.AntiStorageEsp config;
    private final boolean[] storage;
    private final boolean[] emitting;
    private final boolean[] covering;

    private StorageBlocks(final WorldConfig.Anticheat.AntiStorageEsp config) {
        this.config = config;
        final int states = Block.BLOCK_STATE_REGISTRY.size();
        this.storage = new boolean[states];
        this.emitting = new boolean[states];
        this.covering = new boolean[states];
        if (config.blocks.isEmpty()) {
            for (final Block block : BuiltInRegistries.BLOCK) {
                for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
                    for (final BlockEntityType<?> type : STORAGE_TYPES) {
                        if (type.isValid(state)) {
                            this.add(state);
                            break;
                        }
                    }
                }
            }
        } else {
            for (final String entry : config.blocks) {
                this.add(entry);
            }
        }
    }

    private void add(final String entry) {
        final boolean tag = entry.startsWith("#");
        final Identifier identifier = Identifier.tryParse(tag ? entry.substring(1) : entry);
        if (identifier == null) {
            AnticheatExecutor.LOGGER.warn("Ignoring invalid block \"{}\" in the storage hiding block list", entry);
            return;
        }
        if (tag) {
            final TagKey<Block> key = TagKey.create(Registries.BLOCK, identifier);
            boolean found = false;
            for (final Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
                this.add(holder.value());
                found = true;
            }
            if (!found) {
                AnticheatExecutor.LOGGER.warn("Ignoring unknown or empty block tag \"{}\" in the storage hiding block list", entry);
            }
            return;
        }
        final Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
        if (block == null) {
            AnticheatExecutor.LOGGER.warn("Ignoring unknown block \"{}\" in the storage hiding block list", entry);
            return;
        }
        this.add(block);
    }

    private void add(final Block block) {
        for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
            this.add(state);
        }
    }

    private void add(final BlockState state) {
        // never hide air, it would cause needless updates
        if (state.isAir()) {
            return;
        }
        final int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        this.storage[id] = true;
        this.emitting[id] = state.getLightEmission() > 0;
        this.covering[id] = state.isSolidRender() || isBoxLike(state);
    }

    /**
     * @return whether the block is a single box that fills at least 14/16 of its cell on every axis, like a chest or a
     * shulker box: what is behind it can't be seen
     */
    private static boolean isBoxLike(final BlockState state) {
        final List<AABB> boxes;
        try {
            boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
        } catch (final RuntimeException ignored) {
            // a shape that needs a world, so not a simple box
            return false;
        }
        if (boxes.size() != 1) {
            return false;
        }
        final AABB box = boxes.getFirst();
        final double min = 14.0 / 16.0 - 1.0E-6;
        return box.getXsize() >= min && box.getYsize() >= min && box.getZsize() >= min;
    }

    /**
     * @return the storage blocks of the world, or {@code null} if storage hiding is disabled there
     */
    public static @Nullable StorageBlocks of(final ServerLevel level) {
        final WorldConfig.Anticheat.AntiStorageEsp config = level.canvasConfig().anticheat.effective().antiStorageEsp;
        if (!config.enabled) {
            return null;
        }
        final StorageBlocks last = StorageBlocks.last;
        if (last != null && last.config == config) {
            return last;
        }
        final StorageBlocks ret;
        synchronized (COMPILED) {
            StorageBlocks compiled = COMPILED.get(config);
            if (compiled == null) {
                if (COMPILED.size() > 64) {
                    // configurations of earlier reloads
                    COMPILED.clear();
                }
                compiled = new StorageBlocks(config);
                COMPILED.put(config, compiled);
                final boolean[] union = any.length == compiled.storage.length ? any.clone() : new boolean[compiled.storage.length];
                for (int i = 0; i < union.length; ++i) {
                    union[i] |= compiled.storage[i];
                }
                any = union;
            }
            ret = compiled;
        }
        StorageBlocks.last = ret;
        return ret;
    }

    /**
     * @return whether some world may hide the state, a cheap check for packets that don't know their world
     */
    public static boolean mayBeStorage(final BlockState state) {
        final boolean[] any = StorageBlocks.any;
        final int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        return id >= 0 && id < any.length && any[id];
    }

    public boolean isStorage(final BlockState state) {
        final int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        return id >= 0 && id < this.storage.length && this.storage[id];
    }

    /**
     * @return whether the state is storage that emits light, which its disguise doesn't
     */
    public boolean isEmitting(final BlockState state) {
        final int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        return id >= 0 && id < this.emitting.length && this.emitting[id];
    }

    /**
     * @return whether the state is storage that hides what is behind it, opaque or box like (chests, shulker boxes,
     * barrels, furnaces, ...). Hoppers, brewing stands and other storage with gaps don't
     */
    public boolean isCovering(final BlockState state) {
        final int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        return id >= 0 && id < this.covering.length && this.covering[id];
    }

    /**
     * @return an access to the level in which storage that hides what is behind it occludes, see {@link #isCovering}.
     * Storage is traced with it: a chest in front of another one hides it
     */
    public OcclusionAccess occlusion(final ServerLevel level) {
        final OcclusionAccess ret = new OcclusionAccess(level);
        ret.setAlsoOccluding(this::isCovering);
        return ret;
    }

    /**
     * Cheap check of the section's palette.
     *
     * @return {@code false} if the section contains no storage
     */
    public boolean mayContain(final LevelChunkSection section) {
        return !section.hasOnlyAir() && section.maybeHas(this::isStorage);
    }

    /**
     * Cheap check of the section's palette.
     *
     * @return {@code false} if the section contains no storage that emits light
     */
    public boolean mayContainEmitting(final LevelChunkSection section) {
        return !section.hasOnlyAir() && section.maybeHas(this::isEmitting);
    }

    /**
     * What a hidden storage block is sent as. Opaque storage (barrels, furnaces, ...) becomes the natural stone of the
     * world like hidden ores. Other storage (chests, hoppers, shulker boxes, ...) becomes what most of the blocks
     * around it are, other storage aside: the open space, air or the fluid it is waterlogged with, it stands in, or
     * stone if it is set into a wall. Neither changes the light dampening the client computes with: opaque stays
     * opaque, and the rare see-through block sent as stone is ignored, see
     * {@link io.canvasmc.canvas.anticheat.disguise.WorldLight}.
     */
    public BlockState filler(final OcclusionAccess access, final ServerLevel level, final int x, final int y, final int z, final BlockState state) {
        final BlockState stone = VisibilityFilters.hideBlock(level, y);
        if (state.isSolidRender()) {
            return stone;
        }
        int solid = 0;
        int open = 0;
        for (int direction = 0; direction < 6; ++direction) {
            final BlockState neighbour = access.getBlockState(x + DX[direction], y + DY[direction], z + DZ[direction]);
            if (neighbour == null) {
                // outside the world or not loaded
                ++solid;
            } else if (!this.isStorage(neighbour)) {
                if (neighbour.isSolidRender()) {
                    ++solid;
                } else {
                    ++open;
                }
            }
        }
        if (solid > open) {
            return stone;
        }
        final FluidState fluid = state.getFluidState();
        return fluid.isEmpty() ? AIR : fluid.createLegacyBlock();
    }
}
