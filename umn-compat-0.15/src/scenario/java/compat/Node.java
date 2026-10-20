package compat;

import com.gitlab.mvysny.umn.core.For;
import com.gitlab.mvysny.umn.core.IndexingUMN;
import com.gitlab.mvysny.umn.core.MvstoreUMN;
import com.gitlab.mvysny.umn.core.UMN;
import com.gitlab.mvysny.umn.proxyclient.ProxyClient;
import com.gitlab.mvysny.umn.sync.SyncingUMN;
import com.gitlab.mvysny.umn.sync.SyncingUMNKt;
import kotlin.Pair;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.ranges.RangesKt;
import kotlin.reflect.KClass;
import kotlin.sequences.SequencesKt;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One node file, opened the way Aedict opens its user data (aedict-common's {@code UserDataUMN}), with Aedict's
 * notepad as the data: categories holding items.
 *
 * <pre>{@code
 * try (Node node = new Node(file, remote)) {
 *     node.createCategory("Work");
 *     node.addItem("Work", "apple");
 *     node.sync();   // pushes both, pulls everyone else's
 * }
 * }</pre>
 *
 * Every mutation goes through the outermost wrapper; items and categories are looked up through the indices, so a
 * stale index fails the step.
 */
final class Node implements Closeable {
    private static final KClass<Object> ANY = JvmClassMappingKt.getKotlinClass(Object.class);
    private static final long TIMEOUT_MILLIS = 30_000;
    // the utility maps the sync engine keeps in the node file, named alike in 0.15 and HEAD
    private static final String OUTGOING_BUFFER = "__OUTGOING_BUFFER";
    private static final String LOG_POINTER = "__CURRENT_LOG_PTR";

    private final MVStore store;
    private final IndexingUMN indexing;
    private final SyncingUMN syncing;
    private final UMN umn;
    private final Remote remote;
    private final OkHttpClient http = new OkHttpClient.Builder().pingInterval(1, TimeUnit.MINUTES).build();

    /**
     * @param remote {@code null} opens the node without the sync wrapper, so its changes queue nowhere: a node that
     *               never synced
     */
    Node(File file, Remote remote) {
        this.remote = remote;
        store = new MVStore.Builder().fileName(file.getAbsolutePath()).compress().autoCommitBufferSize(256).open();
        MvstoreUMN storage = new MvstoreUMN(store, Schema.SERIALIZER, Schema.TYPES);
        indexing = new IndexingUMN(storage, Schema.INDICES);
        storage.migrateV1V2(id -> {
            throw new IllegalStateException("a v1 document " + id);
        });
        if (remote == null) {
            syncing = null;
            umn = indexing;
        } else {
            syncing = SyncingUMNKt.syncing(indexing,
                    () -> new ProxyClient(http, new Request.Builder().url(remote.wsUrl), remote.user, remote.pass),
                    Schema.SERIALIZER, false, false);
            umn = syncing;
        }
    }

    /**
     * @return how many records wait to be pushed
     */
    long queued() {
        return store.hasMap(OUTGOING_BUFFER) ? store.openMap(OUTGOING_BUFFER).sizeAsLong() : 0;
    }

    /**
     * @return how many global log records this node has pulled
     */
    long pointer() {
        if (!store.hasMap(LOG_POINTER)) {
            return 0;
        }
        MVMap<Boolean, Long> map = store.openMap(LOG_POINTER);
        Long pointer = map.get(false);
        return pointer == null ? 0 : pointer;
    }

    void createCategory(String name) {
        umn.create(new Category(name));
    }

    /**
     * Appends an item after the category's last one, as Aedict's notepad does.
     */
    void addItem(String categoryName, String text) {
        UUID category = category(categoryName);
        Pair<UUID, Item> last = indexing.getIndex(Schema.ITEMS_OF_CATEGORY).findLastDoc(itemsOf(category));
        umn.create(new Item(category, last == null ? 10f : last.getSecond().ordinal + 10f, text));
    }

    void editItem(String text, String newText) {
        UUID id = item(text);
        Item item = (Item) umn.find(id, ANY);
        umn.update(id, new Item(item.categoryId, item.ordinal, newText));
    }

    void renameCategory(String name, String newName) {
        umn.update(category(name), new Category(newName));
    }

    void deleteItem(String text) {
        umn.delete(item(text));
    }

    /**
     * Deletes the category and its items, as Aedict's {@code NotepadCategoryDB.delete()}.
     */
    void deleteCategory(String name) {
        UUID category = category(name);
        for (UUID id : indexing.getIndex(Schema.ITEMS_OF_CATEGORY).findAllIDs(itemsOf(category))) {
            umn.delete(id);
        }
        umn.delete(category);
    }

    /**
     * Starts the sync and waits until this node's queue is pushed and the whole global log is pulled.
     *
     * @return the global log's size
     */
    long sync() throws IOException, InterruptedException {
        if (syncing == null) {
            throw new IllegalStateException("opened without sync");
        }
        syncing.getSyncer().start();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (true) {
            // in this order: an empty queue means the server appended it all, so the log size read next includes it
            long queued = queued();
            long log = Server.logSize(remote.adminUrl, remote.user);
            long pointer = pointer();
            if (queued == 0 && pointer == log) {
                return log;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("not synced in time: queued=" + queued + " pointer=" + pointer + " log=" + log);
            }
            Thread.sleep(50);
        }
    }

    /**
     * Waits until this node, syncing already, has pulled {@code count} records.
     */
    void awaitPointer(long count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (pointer() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("pulled " + pointer() + " records, not " + count);
            }
            Thread.sleep(50);
        }
    }

    /**
     * Lists the documents, sorted, one per line:
     *
     * <pre>
     * doc cat 6f1c…  Work
     * doc item 0a9e… Work/apple
     * </pre>
     */
    List<String> dump() {
        List<String> lines = new ArrayList<>();
        for (UUID id : ids()) {
            Object doc = umn.find(id, ANY);
            if (doc instanceof Category) {
                lines.add("doc cat " + id + " " + ((Category) doc).name);
            } else {
                Item item = (Item) doc;
                Object category = umn.find(item.categoryId, ANY);
                String categoryName = category instanceof Category ? ((Category) category).name : "?";
                lines.add("doc item " + id + " " + categoryName + "/" + item.text);
            }
        }
        Collections.sort(lines);
        return lines;
    }

    /**
     * Fails unless every index finds exactly the documents it should.
     */
    void checkIndices() {
        Set<UUID> categories = new HashSet<>();
        Set<UUID> items = new HashSet<>();
        Set<UUID> categoriesOfItems = new HashSet<>();
        for (UUID id : ids()) {
            Object doc = umn.find(id, ANY);
            if (doc instanceof Category) {
                Category category = (Category) doc;
                categories.add(id);
                check(indexing.getIndex(Schema.CATEGORY_BY_NAME).findAllIDs(new For.Key<>(category.name)).contains(id),
                        "category " + category.name + " by name");
            } else {
                Item item = (Item) doc;
                items.add(id);
                categoriesOfItems.add(item.categoryId);
                check(indexing.getIndex(Schema.ITEMS_OF_CATEGORY).findAllIDs(new For.Key<>(new ItemKey(item.categoryId, item.ordinal))).contains(id),
                        "item " + item.text + " in its category");
                check(indexing.getIndex(Schema.CATEGORY_OF_TEXT).findAllIDs(new For.Key<>(item.text)).contains(item.categoryId),
                        "the category of item " + item.text);
            }
        }
        check(indexing.getIndex(Schema.CATEGORY_BY_NAME).findAllIDs(For.Companion.all()).equals(categories), "no stale category names");
        check(indexing.getIndex(Schema.ITEMS_OF_CATEGORY).findAllIDs(For.Companion.all()).equals(items), "no stale items");
        check(indexing.getIndex(Schema.CATEGORY_OF_TEXT).findAllIDs(For.Companion.all()).equals(categoriesOfItems), "no stale item texts");
    }

    @Override
    public void close() throws IOException {
        umn.close();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    private List<UUID> ids() {
        return SequencesKt.toList(umn.getSequenceOfIDs());
    }

    private UUID category(String name) {
        UUID id = indexing.getIndex(Schema.CATEGORY_BY_NAME).findFirstID(new For.Key<>(name));
        if (id == null) {
            throw new IllegalArgumentException("no category " + name);
        }
        return id;
    }

    private UUID item(String text) {
        UUID category = indexing.getIndex(Schema.CATEGORY_OF_TEXT).findFirstID(new For.Key<>(text));
        if (category != null) {
            for (Pair<UUID, Item> item : SequencesKt.asIterable(indexing.getIndex(Schema.ITEMS_OF_CATEGORY).getSequenceOfDocs(itemsOf(category)))) {
                if (item.getSecond().text.equals(text)) {
                    return item.getFirst();
                }
            }
        }
        throw new IllegalArgumentException("no item " + text);
    }

    private static For<ItemKey> itemsOf(UUID category) {
        return new For.KeyRange<>(RangesKt.rangeTo(new ItemKey(category, Float.NEGATIVE_INFINITY), new ItemKey(category, Float.POSITIVE_INFINITY)));
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("index check failed: " + what);
        }
    }
}
