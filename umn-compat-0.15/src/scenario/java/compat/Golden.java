package compat;

import com.gitlab.mvysny.umn.core.LogRecord;
import com.gitlab.mvysny.umn.core.MvstoreUMN;
import com.gitlab.mvysny.umn.proxyclient.AbstractMessage;
import org.h2.mvstore.MVStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every {@link LogRecord} kind and every proxy message, built from fixed ids and documents so each version writes
 * them byte for byte alike. {@code ./gradlew umn-compat-0.15:recordGolden015} writes 0.15's into
 * {@code src/test/resources/golden/}, one {@code <name>.bin} each.
 */
public final class Golden {
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID POPULATED_ID = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    /**
     * @return the log records, by name
     */
    public static Map<String, byte[]> records() {
        Map<String, byte[]> records = new LinkedHashMap<>();
        records.put("record-created-category", new LogRecord.DocumentCreated(new Category("Work"), (short) 0, CATEGORY_ID).toByteArray(Schema.SERIALIZER));
        records.put("record-created-item", new LogRecord.DocumentCreated(new Item(CATEGORY_ID, 10f, "apple"), (short) 1, ITEM_ID).toByteArray(Schema.SERIALIZER));
        records.put("record-updated-item", new LogRecord.DocumentUpdated(new Item(CATEGORY_ID, 10f, "pear"), (short) 1, ITEM_ID).toByteArray(Schema.SERIALIZER));
        records.put("record-deleted", new LogRecord.DocumentDeleted(ITEM_ID).toByteArray(Schema.SERIALIZER));
        records.put("record-created2", populated().toByteArray(Schema.SERIALIZER));
        return records;
    }

    /**
     * @return the proxy messages, by name
     */
    public static Map<String, byte[]> messages() {
        byte[] record = records().get("record-created-item");
        Map<String, byte[]> messages = new LinkedHashMap<>();
        messages.put("message-started", AbstractMessage.Started.INSTANCE.asByteArray());
        messages.put("message-add-async", new AbstractMessage.AddAsync(record).asByteArray());
        messages.put("message-on-added", AbstractMessage.OnAdded.INSTANCE.asByteArray());
        messages.put("message-on-log-record", new AbstractMessage.OnLogRecord(record).asByteArray());
        messages.put("message-on-log-record-hole", new AbstractMessage.OnLogRecord(null).asByteArray());
        return messages;
    }

    static List<byte[]> recordList() {
        return new ArrayList<>(records().values());
    }

    static void write(File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        Map<String, byte[]> all = new LinkedHashMap<>(records());
        all.putAll(messages());
        for (Map.Entry<String, byte[]> e : all.entrySet()) {
            Files.write(new File(dir, e.getKey() + ".bin").toPath(), e.getValue());
        }
    }

    /**
     * The record a node writes for a document it had before it first synced.
     */
    private static LogRecord.DocumentCreated2 populated() {
        MVStore store = new MVStore.Builder().open();
        try {
            MvstoreUMN umn = new MvstoreUMN(store, Schema.SERIALIZER, Schema.TYPES);
            umn.create(new Category("Home"), POPULATED_ID);
            return new LogRecord.DocumentCreated2(umn.findSerialized(POPULATED_ID), POPULATED_ID);
        } finally {
            store.close();
        }
    }

    private Golden() {
    }
}
