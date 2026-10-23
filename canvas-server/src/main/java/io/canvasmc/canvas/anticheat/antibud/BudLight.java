package io.canvasmc.canvas.anticheat.antibud;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.Arrays;

/**
 * Computes the block light the light engine would have computed if some light sources emitted less, starting from
 * the light it did compute. This is how disguised buds get the light of the small bud they are disguised as, so the
 * light around them doesn't give away their size.
 * <p>
 * Mirrors Starlight's propagation exactly: each lowered source is set to its new emission and the light it may have
 * provided is removed (light decrease), then everything around the removed area, and every source inside it, is
 * propagated again (light increase). The result is the unique fixpoint the light engine computes, so it doesn't depend
 * on the order of the sources. The work stays within the reach of the lowered sources' light.
 */
public final class BudLight {

    /**
     * The world the light is computed in. Directions are {@code -x, +x, -y, +y, -z, +z}, so the opposite of
     * direction {@code d} is {@code d ^ 1}.
     */
    public interface Medium {

        /**
         * @return the block light the light engine computed at the position, or {@code -1} if it isn't loaded
         */
        int light(int x, int y, int z);

        /**
         * @return how much light loses when moving from the position into its neighbor in the given direction, or
         * {@code -1} if light can't move there (unloaded, or blocked by the faces of the two blocks)
         */
        int cost(int x, int y, int z, int direction);

        /**
         * @return the light the block at the position emits, lowered sources included
         */
        int emission(int x, int y, int z);
    }

    static final int[] DX = {-1, 1, 0, 0, 0, 0};
    static final int[] DY = {0, 0, -1, 1, 0, 0};
    static final int[] DZ = {0, 0, 0, 0, -1, 1};
    private static final int ALL_DIRECTIONS = 63;

    private static final int FLAG_RECHECK_LEVEL = 1;
    private static final int FLAG_WRITE_LEVEL = 2;

    private static final int DECREASE_STRIDE = 5;
    private static final int INCREASE_STRIDE = 6;

    private final Medium medium;
    private final Long2ByteOpenHashMap changed = new Long2ByteOpenHashMap();
    private int[] decrease = new int[DECREASE_STRIDE * 64];
    private int decreaseLength;
    private int[] increase = new int[INCREASE_STRIDE * 64];
    private int increaseLength;

    public BudLight(final Medium medium) {
        this.medium = medium;
        this.changed.defaultReturnValue((byte) -1);
    }

    public static long pack(final int x, final int y, final int z) {
        // the layout of BlockPos#asLong
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) | ((long) z & 0x3FFFFFFL) << 12;
    }

    public static int unpackX(final long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(final long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int unpackZ(final long packed) {
        return (int) (packed << 26 >> 38);
    }

    private int light(final int x, final int y, final int z) {
        final byte changed = this.changed.get(pack(x, y, z));
        return changed >= 0 ? changed : this.medium.light(x, y, z);
    }

    private void setLight(final int x, final int y, final int z, final int level) {
        this.changed.put(pack(x, y, z), (byte) level);
    }

    /**
     * Lowers a light source to a new emission. Call {@link #propagate} once every source was lowered.
     *
     * @param emission    the emission the source had when the light engine computed the light
     * @param newEmission the emission it has now, which {@link Medium#emission} returns for it
     */
    public void lower(final int x, final int y, final int z, final int emission, final int newEmission) {
        final int current = this.light(x, y, z);
        if (current < 0 || current > emission) {
            // something else lights the source brighter than it does: its light is the brightest nowhere
            return;
        }
        this.setLight(x, y, z, newEmission);
        if (newEmission != 0) {
            this.addIncrease(x, y, z, newEmission, ALL_DIRECTIONS, 0);
        }
        this.addDecrease(x, y, z, current, ALL_DIRECTIONS);
    }

    public void propagate() {
        this.performDecrease();
        this.performIncrease();
    }

    /**
     * @return the positions whose light changed, packed by {@link #pack}, with their new light
     */
    public Long2ByteMap changes() {
        // positions that were zeroed and lit up to their old level again changed nothing
        final Long2ByteOpenHashMap ret = new Long2ByteOpenHashMap(this.changed.size());
        for (final Long2ByteMap.Entry entry : this.changed.long2ByteEntrySet()) {
            final long packed = entry.getLongKey();
            final int level = entry.getByteValue();
            if (this.medium.light(unpackX(packed), unpackY(packed), unpackZ(packed)) != level) {
                ret.put(packed, (byte) level);
            }
        }
        return ret;
    }

    private void addDecrease(final int x, final int y, final int z, final int level, final int directions) {
        if (this.decreaseLength + DECREASE_STRIDE > this.decrease.length) {
            this.decrease = Arrays.copyOf(this.decrease, this.decrease.length << 1);
        }
        final int[] queue = this.decrease;
        final int at = this.decreaseLength;
        queue[at] = x;
        queue[at + 1] = y;
        queue[at + 2] = z;
        queue[at + 3] = level;
        queue[at + 4] = directions;
        this.decreaseLength = at + DECREASE_STRIDE;
    }

    private void addIncrease(final int x, final int y, final int z, final int level, final int directions, final int flags) {
        if (this.increaseLength + INCREASE_STRIDE > this.increase.length) {
            this.increase = Arrays.copyOf(this.increase, this.increase.length << 1);
        }
        final int[] queue = this.increase;
        final int at = this.increaseLength;
        queue[at] = x;
        queue[at + 1] = y;
        queue[at + 2] = z;
        queue[at + 3] = level;
        queue[at + 4] = directions;
        queue[at + 5] = flags;
        this.increaseLength = at + INCREASE_STRIDE;
    }

    private void performDecrease() {
        final Medium medium = this.medium;
        for (int read = 0; read < this.decreaseLength; read += DECREASE_STRIDE) {
            final int[] queue = this.decrease;
            final int x = queue[read];
            final int y = queue[read + 1];
            final int z = queue[read + 2];
            final int level = queue[read + 3];
            final int directions = queue[read + 4];
            for (int direction = 0; direction < 6; ++direction) {
                if ((directions & 1 << direction) == 0) {
                    continue;
                }
                final int offX = x + DX[direction];
                final int offY = y + DY[direction];
                final int offZ = z + DZ[direction];
                final int current = this.light(offX, offY, offZ);
                if (current <= 0) {
                    // already dark, or not loaded
                    continue;
                }
                final int cost = medium.cost(x, y, z, direction);
                if (cost < 0) {
                    continue;
                }
                final int target = Math.max(0, level - cost);
                if (current > target) {
                    // lit by something else, which lights up what was removed again
                    this.addIncrease(offX, offY, offZ, current, ALL_DIRECTIONS, FLAG_RECHECK_LEVEL);
                    continue;
                }
                final int emitted = medium.emission(offX, offY, offZ);
                if (emitted != 0) {
                    this.addIncrease(offX, offY, offZ, emitted, ALL_DIRECTIONS, FLAG_WRITE_LEVEL);
                }
                this.setLight(offX, offY, offZ, 0);
                if (target > 0) {
                    this.addDecrease(offX, offY, offZ, target, ALL_DIRECTIONS & ~(1 << (direction ^ 1)));
                }
            }
        }
        this.decreaseLength = 0;
    }

    private void performIncrease() {
        final Medium medium = this.medium;
        for (int read = 0; read < this.increaseLength; read += INCREASE_STRIDE) {
            final int[] queue = this.increase;
            final int x = queue[read];
            final int y = queue[read + 1];
            final int z = queue[read + 2];
            final int level = queue[read + 3];
            final int directions = queue[read + 4];
            final int flags = queue[read + 5];
            if ((flags & FLAG_RECHECK_LEVEL) != 0) {
                if (this.light(x, y, z) != level) {
                    continue;
                }
            } else if ((flags & FLAG_WRITE_LEVEL) != 0) {
                // a source zeroed by the decrease. Unlike Starlight, never lower a level that was lit up again in the
                // meantime: with several lowered sources the light can reach a source before its own entry does
                final int current = this.light(x, y, z);
                if (current > level) {
                    continue;
                }
                this.setLight(x, y, z, level);
            }
            for (int direction = 0; direction < 6; ++direction) {
                if ((directions & 1 << direction) == 0) {
                    continue;
                }
                final int offX = x + DX[direction];
                final int offY = y + DY[direction];
                final int offZ = z + DZ[direction];
                final int current = this.light(offX, offY, offZ);
                if (current < 0 || current >= level - 1) {
                    continue;
                }
                final int cost = medium.cost(x, y, z, direction);
                if (cost < 0) {
                    continue;
                }
                final int target = level - cost;
                if (target <= current) {
                    continue;
                }
                this.setLight(offX, offY, offZ, target);
                if (target > 1) {
                    this.addIncrease(offX, offY, offZ, target, ALL_DIRECTIONS & ~(1 << (direction ^ 1)), 0);
                }
            }
        }
        this.increaseLength = 0;
    }
}
