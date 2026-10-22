package io.canvasmc.canvas.anticheat.antiesp;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Per-player visibility state of the entities the tracker asked about, keyed by entity id.
 * <p>
 * The tracker queries every (entity, player) pair every tick, so this is a struct of arrays with an open addressing
 * index instead of a map of objects: lookups neither allocate nor chase pointers, and iterating all entries is a
 * linear scan. Entries live in the dense arrays at {@code [0, size)}, removal moves the last entry into the hole.
 * Only used on the region thread owning the player.
 */
final class EntityTable {

    static final byte UNKNOWN = 0;
    static final byte VISIBLE = 1;
    static final byte HIDDEN = 2;

    /**
     * Tick value meaning "never". Far enough from {@link Integer#MIN_VALUE} that subtracting it from a tick can't
     * overflow.
     */
    static final int NEVER = Integer.MIN_VALUE / 2;

    static final int BOX_STRIDE = 6;
    static final int MOTION_STRIDE = 3;

    private static final int INITIAL_CAPACITY = 32;

    int size;
    /**
     * The entity, compared by reference on lookup so a reused id never inherits state.
     */
    @Nullable Entity[] entity;
    int[] id;
    byte[] state;
    /**
     * Whether the entity skipped tracing during the tracker pass in {@link #seen}.
     */
    boolean[] always;
    /**
     * The last tracker pass that asked about the entity.
     */
    int[] seen;
    /**
     * The last pass the entity was known to be visible in, hysteresis is counted from here.
     */
    int[] lastVisible;
    /**
     * The pass whose data the last (queued or synchronous) visibility check used.
     */
    int[] checked;
    /**
     * The pass from which on a periodic re-check is due.
     */
    int[] nextCheck;
    /**
     * The last pass the bounding box changed in.
     */
    int[] moved;
    /**
     * Bounding box of the whole vehicle stack: minX, minY, minZ, maxX, maxY, maxZ.
     */
    double[] box;
    /**
     * Movement of the box centre during the last pass.
     */
    double[] motion;

    private int[] index;
    private int mask;

    EntityTable() {
        this.allocate(INITIAL_CAPACITY);
    }

    private void allocate(final int capacity) {
        this.size = 0;
        this.entity = new Entity[capacity];
        this.id = new int[capacity];
        this.state = new byte[capacity];
        this.always = new boolean[capacity];
        this.seen = new int[capacity];
        this.lastVisible = new int[capacity];
        this.checked = new int[capacity];
        this.nextCheck = new int[capacity];
        this.moved = new int[capacity];
        this.box = new double[capacity * BOX_STRIDE];
        this.motion = new double[capacity * MOTION_STRIDE];
        this.index = new int[capacity * 2];
        this.mask = this.index.length - 1;
    }

    /**
     * Drops all entries, giving back the memory of large tables.
     */
    void clear() {
        if (this.entity.length > INITIAL_CAPACITY * 4) {
            this.allocate(INITIAL_CAPACITY);
            return;
        }
        Arrays.fill(this.entity, 0, this.size, null);
        Arrays.fill(this.index, 0);
        this.size = 0;
    }

    /**
     * @return the slot of the entity with the given id, or {@code -1}
     */
    int find(final int id) {
        final int[] index = this.index;
        final int mask = this.mask;
        for (int pos = HashCommon.mix(id) & mask; ; pos = (pos + 1) & mask) {
            final int value = index[pos];
            if (value == 0) {
                return -1;
            }
            if (this.id[value - 1] == id) {
                return value - 1;
            }
        }
    }

    /**
     * Adds an entry for an id that is not present yet.
     *
     * @return its slot, initialized to an unknown state that was never seen
     */
    int add(final int id, final @Nullable Entity entity) {
        if (this.size == this.id.length) {
            this.grow();
        }
        final int slot = this.size++;
        this.entity[slot] = entity;
        this.id[slot] = id;
        this.seen[slot] = NEVER;
        this.forget(slot);
        this.link(slot);
        return slot;
    }

    /**
     * Resets everything known about the visibility of an entry.
     */
    void forget(final int slot) {
        this.state[slot] = UNKNOWN;
        this.always[slot] = false;
        this.lastVisible[slot] = NEVER;
        this.checked[slot] = NEVER;
        this.nextCheck[slot] = NEVER;
        this.moved[slot] = NEVER;
        final int motion = slot * MOTION_STRIDE;
        this.motion[motion] = 0.0;
        this.motion[motion + 1] = 0.0;
        this.motion[motion + 2] = 0.0;
    }

    void remove(final int slot) {
        this.unlink(slot);
        final int last = --this.size;
        if (slot != last) {
            this.entity[slot] = this.entity[last];
            this.id[slot] = this.id[last];
            this.state[slot] = this.state[last];
            this.always[slot] = this.always[last];
            this.seen[slot] = this.seen[last];
            this.lastVisible[slot] = this.lastVisible[last];
            this.checked[slot] = this.checked[last];
            this.nextCheck[slot] = this.nextCheck[last];
            this.moved[slot] = this.moved[last];
            System.arraycopy(this.box, last * BOX_STRIDE, this.box, slot * BOX_STRIDE, BOX_STRIDE);
            System.arraycopy(this.motion, last * MOTION_STRIDE, this.motion, slot * MOTION_STRIDE, MOTION_STRIDE);
            // point the index entry of the moved entry to its new slot
            final int[] index = this.index;
            for (int pos = HashCommon.mix(this.id[slot]) & this.mask; ; pos = (pos + 1) & this.mask) {
                if (index[pos] == last + 1) {
                    index[pos] = slot + 1;
                    break;
                }
            }
        }
        this.entity[last] = null;
    }

    private void link(final int slot) {
        final int[] index = this.index;
        int pos = HashCommon.mix(this.id[slot]) & this.mask;
        while (index[pos] != 0) {
            pos = (pos + 1) & this.mask;
        }
        index[pos] = slot + 1;
    }

    private void unlink(final int slot) {
        final int[] index = this.index;
        final int mask = this.mask;
        int pos = HashCommon.mix(this.id[slot]) & mask;
        while (index[pos] != slot + 1) {
            pos = (pos + 1) & mask;
        }
        // backward shift deletion, keeps every probe sequence intact without tombstones
        for (;;) {
            final int last = pos;
            pos = (pos + 1) & mask;
            int value;
            for (;;) {
                value = index[pos];
                if (value == 0) {
                    index[last] = 0;
                    return;
                }
                final int home = HashCommon.mix(this.id[value - 1]) & mask;
                if (last <= pos ? last >= home || home > pos : last >= home && home > pos) {
                    break;
                }
                pos = (pos + 1) & mask;
            }
            index[last] = value;
        }
    }

    private void grow() {
        final int capacity = this.id.length * 2;
        this.entity = Arrays.copyOf(this.entity, capacity);
        this.id = Arrays.copyOf(this.id, capacity);
        this.state = Arrays.copyOf(this.state, capacity);
        this.always = Arrays.copyOf(this.always, capacity);
        this.seen = Arrays.copyOf(this.seen, capacity);
        this.lastVisible = Arrays.copyOf(this.lastVisible, capacity);
        this.checked = Arrays.copyOf(this.checked, capacity);
        this.nextCheck = Arrays.copyOf(this.nextCheck, capacity);
        this.moved = Arrays.copyOf(this.moved, capacity);
        this.box = Arrays.copyOf(this.box, capacity * BOX_STRIDE);
        this.motion = Arrays.copyOf(this.motion, capacity * MOTION_STRIDE);
        this.index = new int[capacity * 2];
        this.mask = this.index.length - 1;
        for (int slot = 0; slot < this.size; ++slot) {
            this.link(slot);
        }
    }
}
