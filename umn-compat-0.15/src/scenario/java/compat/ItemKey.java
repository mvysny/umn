package compat;

import java.io.Serializable;
import java.util.UUID;

/**
 * The key of {@link Schema#ITEMS_OF_CATEGORY}, as Aedict's {@code IdxCategoryOrdinal}: MVStore stores it
 * Java-serialized in the node file's index map, so it must not change.
 */
final class ItemKey implements Comparable<ItemKey>, Serializable {
    private static final long serialVersionUID = 1L;

    final UUID categoryId;
    final float ordinal;

    ItemKey(UUID categoryId, float ordinal) {
        this.categoryId = categoryId;
        this.ordinal = ordinal;
    }

    @Override
    public int compareTo(ItemKey other) {
        int c = categoryId.compareTo(other.categoryId);
        return c != 0 ? c : Float.compare(ordinal, other.ordinal);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemKey && compareTo((ItemKey) o) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * categoryId.hashCode() + Float.hashCode(ordinal);
    }
}
