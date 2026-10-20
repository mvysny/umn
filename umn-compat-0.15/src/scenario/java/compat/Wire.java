package compat;

import com.gitlab.mvysny.umn.proxyclient.ProxyClient;
import kotlin.Unit;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * The proxy protocol through a bare {@link ProxyClient}, no node: what a version's client sees of a server.
 */
final class Wire {
    private static final long TIMEOUT_MILLIS = 30_000;

    /**
     * Connects with {@code rewind}, pushes the first {@code push} {@link Golden} records, and waits for their ACKs and
     * for {@code expect} pulled records. Prints {@code acks=<n>}, then one {@code record <crc32>} per pulled record.
     */
    static void probe(Consumer<String> out, String wsUrl, String user, String pass, long rewind, int push, int expect) throws Exception {
        List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger acks = new AtomicInteger();
        OkHttpClient http = new OkHttpClient();
        ProxyClient client = new ProxyClient(http, new Request.Builder().url(wsUrl), user, pass);
        try {
            client.setReceiverCallback(rewind, record -> {
                received.add(record);
                return Unit.INSTANCE;
            });
            client.setAddCallback(() -> {
                acks.incrementAndGet();
                return Unit.INSTANCE;
            });
            client.start();
            List<byte[]> records = Golden.recordList();
            for (int i = 0; i < push; i++) {
                client.addAsync(records.get(i % records.size()));
            }
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (acks.get() < push || received.size() < expect) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("acks=" + acks.get() + "/" + push + " received=" + received.size() + "/" + expect);
                }
                Thread.sleep(50);
            }
            out.accept("acks=" + acks.get());
            synchronized (received) {
                for (byte[] record : received) {
                    out.accept("record " + crc(record));
                }
            }
        } finally {
            client.close();
            http.dispatcher().executorService().shutdown();
        }
    }

    /**
     * Connects and prints {@code started}, or {@code threw <exception>: <message>}.
     */
    static void login(Consumer<String> out, String wsUrl, String user, String pass) {
        OkHttpClient http = new OkHttpClient.Builder().readTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build();
        ProxyClient client = new ProxyClient(http, new Request.Builder().url(wsUrl), user, pass);
        try {
            client.start();
            out.accept("started");
        } catch (Exception e) {
            out.accept("threw " + e.getClass().getName() + ": " + e.getMessage());
        } finally {
            client.close();
            http.dispatcher().executorService().shutdown();
        }
    }

    private static String crc(byte[] record) {
        if (record == null) {
            return "hole";
        }
        CRC32 crc = new CRC32();
        crc.update(record);
        return Long.toHexString(crc.getValue());
    }

    private Wire() {
    }
}
