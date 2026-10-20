package compat;

import java.util.UUID;

/**
 * A notepad item in a {@link Category}, as Aedict's {@code NotepadCategoryItemUMN}; {@link #text} stands for its
 * {@code EntryRef}.
 */
final class Item {
    final UUID categoryId;
    final float ordinal;
    final String text;

    Item(UUID categoryId, float ordinal, String text) {
        this.categoryId = categoryId;
        this.ordinal = ordinal;
        this.text = text;
    }
}
