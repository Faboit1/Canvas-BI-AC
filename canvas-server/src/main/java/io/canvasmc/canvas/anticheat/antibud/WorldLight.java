package io.canvasmc.canvas.anticheat.antibud;

import ca.spottedleaf.moonrise.patches.starlight.blockstate.StarlightAbstractBlockState;
import io.canvasmc.canvas.anticheat.raytrace.OcclusionAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The block light and blocks of a level as {@link BudLight} sees them, with the disguised buds replaced by the small
 * buds they are sent as. Light moves between blocks by Starlight's rules: through faces that the face occlusion
 * shapes of the two blocks don't cover together, losing the light dampening of the block it enters, at least 1.
 * <p>
 * Reads the loaded chunks directly, like {@link OcclusionAccess}, and the light engine's published light, so it can
 * be used from any thread. One instance per computation.
 */
final class WorldLight implements BudLight.Medium {

    private static final Direction[] DIRECTIONS = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
    private static final byte[] NOT_LOADED = new byte[0];
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final OcclusionAccess access;
    private final LayerLightEventListener blockLight;
    private final LongSet disguised;
    private final int minY;
    private final int maxY;
    private final int minSectionY;
    private final int minLightSection;
    private final int maxLightSection;
    private final Long2ObjectOpenHashMap<byte[]> nibbles = new Long2ObjectOpenHashMap<>();

    /**
     * @param disguised the positions of the buds that are disguised, packed by {@link BudLight#pack}
     */
    WorldLight(final ServerLevel level, final LongSet disguised) {
        this.access = new OcclusionAccess(level);
        this.blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        this.disguised = disguised;
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
        this.minSectionY = level.getMinSectionY();
        this.minLightSection = level.getLightEngine().getMinLightSection();
        this.maxLightSection = level.getLightEngine().getMaxLightSection() - 1;
    }

    private byte[] nibble(final int x, final int y, final int z) {
        final int sectionY = y >> 4;
        if (sectionY < this.minLightSection || sectionY > this.maxLightSection) {
            return NOT_LOADED;
        }
        final long key = SectionPos.asLong(x >> 4, sectionY, z >> 4);
        byte[] ret = this.nibbles.get(key);
        if (ret == null) {
            final DataLayer layer = this.blockLight.getDataLayerData(SectionPos.of(x >> 4, sectionY, z >> 4));
            ret = layer == null ? NOT_LOADED : layer.getData();
            this.nibbles.put(key, ret);
        }
        return ret;
    }

    @Override
    public int light(final int x, final int y, final int z) {
        final byte[] nibble = this.nibble(x, y, z);
        if (nibble == NOT_LOADED) {
            return -1;
        }
        final int index = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        return nibble[index >> 1] >> ((index & 1) << 2) & 15;
    }

    private @Nullable BlockState state(final int x, final int y, final int z) {
        final LevelChunkSection[] sections = this.access.sections(x >> 4, z >> 4);
        if (sections == null) {
            return null;
        }
        if (y < this.minY || y > this.maxY) {
            // the light sections above and below the world
            return AIR;
        }
        final BlockState state;
        try {
            state = sections[(y >> 4) - this.minSectionY].getBlockState(x & 15, y & 15, z & 15);
        } catch (final MissingPaletteEntryException ignored) {
            // racing a palette resize of a chunk owned by another region
            return null;
        }
        return this.disguised.contains(BudLight.pack(x, y, z)) ? BudDisguise.disguise(state) : state;
    }

    private static boolean isConditionallyFullOpaque(final BlockState state) {
        return ((StarlightAbstractBlockState) state).starlight$isConditionallyFullOpaque();
    }

    @Override
    public int cost(final int x, final int y, final int z, final int direction) {
        final BlockState from = this.state(x, y, z);
        final BlockState to = this.state(x + BudLight.DX[direction], y + BudLight.DY[direction], z + BudLight.DZ[direction]);
        if (from == null || to == null) {
            return -1;
        }
        final Direction towards = DIRECTIONS[direction];
        final VoxelShape fromShape = isConditionallyFullOpaque(from) ? from.getFaceOcclusionShape(towards) : Shapes.empty();
        if (fromShape != Shapes.empty() && Shapes.faceShapeOccludes(Shapes.empty(), fromShape)) {
            return -1;
        }
        if (isConditionallyFullOpaque(to) && Shapes.faceShapeOccludes(fromShape, to.getFaceOcclusionShape(towards.getOpposite()))) {
            return -1;
        }
        return Math.max(1, to.getLightDampening());
    }

    @Override
    public int emission(final int x, final int y, final int z) {
        final BlockState state = this.state(x, y, z);
        return state == null ? 0 : state.getLightEmission();
    }
}
