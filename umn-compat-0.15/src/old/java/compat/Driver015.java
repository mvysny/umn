package compat;

/**
 * Runs {@link Steps} on UMN 0.15, one output line per fact on stdout; exits 1 on failure, with the stack trace on
 * stderr.
 */
public final class Driver015 {
    public static void main(String[] args) {
        try {
            Steps.run(line -> {
                System.out.println(line);
                System.out.flush();
            }, args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        // okhttp's threads would keep the JVM alive
        System.exit(0);
    }
}
