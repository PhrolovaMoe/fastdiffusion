package moe.phrolova.engine;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import org.slf4j.Logger;

/** JNI surface of libfastdiffusion (native/src/jni_bridge.cpp). */
public final class NativeBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private NativeBridge() {}

    /** Loads the model and starts the native worker threads. Throws on failure. */
    static native long create(String modelPath, int device, int cpuWorkers, long cacheBytes);

    static native void destroy(long service);

    static native String deviceName(long service);

    static native int openWorld(long service, long seed, int scale, int minY, int height, int seaLevel);

    static native void closeWorld(long service, int world);

    static native int regionBytes();

    /** Queues a region; {@code out} must be a direct buffer of {@link #regionBytes()} kept alive until completion. */
    static native long requestRegion(long service, int world, int rx, int rz, int priority, ByteBuffer out);

    /**
     * Queues a map grid sampled at block-space pixel centers {@code x0 + (j + 0.5) * step}; source 0 is the coarse
     * map, 1 the latent low-frequency band. {@code out} must hold {@code w * h * 5} bytes until completion.
     */
    static native long requestGrid(long service, int world, double x0, double z0, double step, int w, int h, int source,
                                   int priority, ByteBuffer out);

    /** Drops a request that has not started; it then completes with status 2. */
    static native void cancel(long service, long id);

    /** Waits for completed requests; returns how many entries of {@code ids}/{@code status} were filled.
     * Status: 0 ok, 1 failed, 2 cancelled. */
    static native int poll(long service, long[] ids, int[] status, int timeoutMs);

    static native String takeError(long service, long id);

    /** Blocks until the nearest land to the origin is known; returns {x, z} in blocks. */
    static native long[] findLand(long service, int world);

    /** {regionsDone, regionsFailed, gpuBatches, gpuMs, cpuMs, queued}. */
    static native double[] stats(long service);

    /** Converts upstream PyTorch checkpoints into the engine's GGUF (see native/src/convert.cpp). */
    static native void convertModel(String weightsDir, String pipelineData, String outPath);

    /** Called from native code (any thread). */
    @SuppressWarnings("unused")
    private static void log(int level, String message) {
        switch (level) {
            case 0 -> LOGGER.debug(message);
            case 1 -> LOGGER.info(message);
            case 2 -> LOGGER.warn(message);
            default -> LOGGER.error(message);
        }
    }
}
