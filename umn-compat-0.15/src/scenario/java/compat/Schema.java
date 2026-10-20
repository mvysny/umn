package compat;

import com.gitlab.mvysny.umn.core.DocumentSerializer;
import com.gitlab.mvysny.umn.core.IndexingUMN;
import com.gitlab.mvysny.umn.core.TypeToClassResolver;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Aedict's node shape (aedict-common's {@code UMNUtils.kt}): its document types, a binary serializer and the kinds of
 * indices it defines.
 */
public final class Schema {
    static final KClass<Category> CATEGORY = JvmClassMappingKt.getKotlinClass(Category.class);
    static final KClass<Item> ITEM = JvmClassMappingKt.getKotlinClass(Item.class);

    /**
     * Writes opaque bytes, as Aedict's {@code BoxableSerializer}; the document's type travels beside them.
     */
    public static final DocumentSerializer SERIALIZER = new DocumentSerializer() {
        @Override
        public void serialize(Object document, DataOutputStream dout) {
            try {
                if (document instanceof Category) {
                    dout.writeUTF(((Category) document).name);
                } else {
                    Item item = (Item) document;
                    dout.writeLong(item.categoryId.getMostSignificantBits());
                    dout.writeLong(item.categoryId.getLeastSignificantBits());
                    dout.writeFloat(item.ordinal);
                    dout.writeUTF(item.text);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public <T> T deserialize(KClass<T> clazz, DataInputStream din) {
            try {
                Object document = clazz.equals(CATEGORY)
                        ? new Category(din.readUTF())
                        : new Item(new UUID(din.readLong(), din.readLong()), din.readFloat(), din.readUTF());
                return JvmClassMappingKt.getJavaClass(clazz).cast(document);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // 0.15's interface has no default methods for Java, so both overloads are spelled out
        @Override
        public byte[] serialize(Object document) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            serialize(document, new DataOutputStream(bytes));
            return bytes.toByteArray();
        }

        @Override
        public <T> T deserialize(KClass<T> clazz, byte[] bytes) {
            return deserialize(clazz, new DataInputStream(new ByteArrayInputStream(bytes)));
        }
    };

    /**
     * The type is the ordinal, as Aedict's {@code DocumentTypeEnum}.
     */
    public static final TypeToClassResolver TYPES = new TypeToClassResolver() {
        @Override
        public KClass<?> toClass(short type) {
            switch (type) {
                case 0: return CATEGORY;
                case 1: return ITEM;
                default: throw new IllegalArgumentException("unknown document type " + type);
            }
        }

        @Override
        public short toType(KClass<?> clazz) {
            if (clazz.equals(CATEGORY)) return 0;
            if (clazz.equals(ITEM)) return 1;
            throw new IllegalArgumentException(clazz + " is not a known document type");
        }
    };

    static final IndexingUMN.IndexDef<String, Category, Category> CATEGORY_BY_NAME =
            IndexingUMN.IndexDef.Companion.simple("c_name", (Category c) -> Collections.singleton(c.name), CATEGORY);

    /**
     * A category's items in order, as Aedict's {@code idxCategoryItems}: a custom {@link java.io.Serializable} key.
     */
    static final IndexingUMN.IndexDef<ItemKey, Item, Item> ITEMS_OF_CATEGORY =
            IndexingUMN.IndexDef.Companion.simple("c_items", (Item i) -> Collections.singleton(new ItemKey(i.categoryId, i.ordinal)), ITEM);

    /**
     * Indexes an item but finds its category, as Aedict's {@code idxEntryRefKeyToCategory}.
     */
    static final IndexingUMN.IndexDef<String, Item, Category> CATEGORY_OF_TEXT =
            new IndexingUMN.IndexDef<>("c_text", (Item i) -> Collections.singleton(i.text), ITEM, CATEGORY, (id, i) -> i.categoryId);

    static final Set<IndexingUMN.IndexDef<?, ?, ?>> INDICES =
            new HashSet<>(Arrays.asList(CATEGORY_BY_NAME, ITEMS_OF_CATEGORY, CATEGORY_OF_TEXT));

    private Schema() {
    }
}
