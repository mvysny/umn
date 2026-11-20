package compat;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The scenario steps behind one command line, so a test runs the very same steps on HEAD in-process and on UMN 0.15
 * in a child JVM ({@code Driver015}). The node session:
 *
 * <pre>
 * session &lt;node file&gt; &lt;ws url&gt; &lt;admin url&gt; &lt;user&gt; &lt;pass&gt; cat=Work item=Work/apple sync dump
 * </pre>
 *
 * prints {@code opened queued=<n> pointer=<n>} first, then what its ops print. The ops, in order:
 * <ul>
 * <li>{@code plain} — first only: open without the sync wrapper, a node that never synced</li>
 * <li>{@code cat=<name>}, {@code item=<cat>/<text>} — create</li>
 * <li>{@code edit=<text>/<new text>}, {@code rename=<cat>/<new name>} — update</li>
 * <li>{@code del=<text>}, {@code delcat=<cat>} — delete; a category goes with its items</li>
 * <li>{@code items=<cat>/<n>} — create {@code n} items {@code <cat> 0}…, committing the store after each, as a phone's
 * edits spread over time do</li>
 * <li>{@code idle=<ms>} — leave the store open, its background writer running</li>
 * <li>{@code maps=<n>} — {@code n} more utility maps of one entry each, as an app's own indices add</li>
 * <li>{@code sync} — push the queue, pull the log; prints {@code synced log=<n>}</li>
 * <li>{@code ready} — prints {@code READY}, for a test to act while this node stays connected</li>
 * <li>{@code await=<n>} — wait until the node pulled {@code n} records</li>
 * <li>{@code dump} — prints the documents, see {@link Node#dump()}</li>
 * </ul>
 * The indices are checked at open and at close. The other commands: {@code wire} ({@link Wire#probe}),
 * {@code wire-login} ({@link Wire#login}) and {@code golden <dir>}.
 */
public final class Steps {
    public static void run(Consumer<String> out, String... args) throws Exception {
        switch (args[0]) {
            case "session":
                session(out, args[1], new Remote(args[2], args[3], args[4], args[5]), Arrays.asList(args).subList(6, args.length));
                break;
            case "wire":
                Wire.probe(out, args[1], args[2], args[3], Long.parseLong(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
                break;
            case "wire-login":
                Wire.login(out, args[1], args[2], args[3]);
                break;
            case "golden":
                Golden.write(new File(args[1]));
                break;
            default:
                throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    private static void session(Consumer<String> out, String file, Remote remote, List<String> ops) throws Exception {
        boolean plain = !ops.isEmpty() && ops.get(0).equals("plain");
        try (Node node = new Node(new File(file), plain ? null : remote)) {
            out.accept("opened queued=" + node.queued() + " pointer=" + node.pointer());
            node.checkIndices();
            for (String op : plain ? ops.subList(1, ops.size()) : ops) {
                String[] kv = op.split("=", 2);
                String[] parts = kv.length > 1 ? kv[1].split("/", 2) : new String[0];
                switch (kv[0]) {
                    case "cat": node.createCategory(parts[0]); break;
                    case "item": node.addItem(parts[0], parts[1]); break;
                    case "edit": node.editItem(parts[0], parts[1]); break;
                    case "rename": node.renameCategory(parts[0], parts[1]); break;
                    case "del": node.deleteItem(parts[0]); break;
                    case "delcat": node.deleteCategory(parts[0]); break;
                    case "items":
                        for (int i = 0; i < Integer.parseInt(parts[1]); i++) {
                            node.addItem(parts[0], parts[0] + " " + i);
                            node.commit();
                        }
                        break;
                    case "idle": Thread.sleep(Long.parseLong(parts[0])); break;
                    case "maps": node.addMaps(Integer.parseInt(parts[0])); break;
                    case "sync": out.accept("synced log=" + node.sync()); break;
                    case "ready": out.accept("READY"); break;
                    case "await": node.awaitPointer(Long.parseLong(parts[0])); break;
                    case "dump": node.dump().forEach(out); break;
                    default: throw new IllegalArgumentException("unknown op " + op);
                }
            }
            node.checkIndices();
        }
    }

    private Steps() {
    }
}
