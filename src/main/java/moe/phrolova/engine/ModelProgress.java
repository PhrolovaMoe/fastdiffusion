package moe.phrolova.engine;

/**
 * Model preparation state, written by the thread that starts the service and read by the
 * client's loading screen (the integrated server shares this JVM).
 */
public final class ModelProgress {
    public enum Stage { IDLE, DOWNLOADING, CONVERTING, LOADING }

    private static volatile Stage stage = Stage.IDLE;
    private static volatile long done, total;

    private ModelProgress() {}

    public static Stage stage() { return stage; }

    /** Bytes downloaded so far, including files verified from an earlier attempt. */
    public static long done() { return done; }

    public static long total() { return total; }

    static void set(Stage s) { stage = s; }

    static void download(long bytesDone, long bytesTotal) {
        total = bytesTotal;
        done = bytesDone;
        stage = Stage.DOWNLOADING;
    }

    static void advance(long bytes) { done += bytes; }  // single writer
}
