package moe.phrolova.engine;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import moe.phrolova.Config;
import org.slf4j.Logger;

/**
 * Process-wide owner of the native inference service. Models stay resident between worlds;
 * a daemon thread drains native completions and completes the matching futures.
 */
public final class TerrainService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static TerrainService instance;

    private final long handle;
    private final String device;
    private final Thread poller;
    private volatile boolean running = true;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    // `buffer` stays referenced until native completion, even when the future was cancelled early.
    private record Pending(CompletableFuture<Object> future, Supplier<Object> result, String what, ByteBuffer buffer) {}

    private TerrainService(long handle) {
        this.handle = handle;
        this.device = NativeBridge.deviceName(handle);
        poller = new Thread(this::pollLoop, "FastDiffusion completions");
        poller.setDaemon(true);
        poller.start();
    }

    /** Starts the service on first use (downloads/converts the model if needed). */
    public static synchronized TerrainService get() {
        if (instance == null) {
            try {
                Path dataDir = Config.dataDirectory();
                NativeLibrary.load(dataDir);
                if (NativeBridge.regionBytes() != RegionData.BYTES) {
                    throw new IllegalStateException("native library does not match the mod (region layout mismatch)");
                }
                Path model = ModelFiles.ensureModel(dataDir, Config.modelFile());
                ModelProgress.set(ModelProgress.Stage.LOADING);
                long start = System.nanoTime();
                long h = NativeBridge.create(model.toAbsolutePath().toString(), Config.gpuDevice(), Config.cpuWorkers(),
                        Config.tileCacheBytes());
                instance = new TerrainService(h);
                LOGGER.info("FastDiffusion ready on {} in {} ms", instance.device, (System.nanoTime() - start) / 1_000_000);
            } finally {
                ModelProgress.set(ModelProgress.Stage.IDLE);
            }
        }
        return instance;
    }

    public String device() {
        return device;
    }

    /** Device name if the service has started, without starting it. */
    public static synchronized String deviceIfRunning() {
        return instance == null ? null : instance.device;
    }

    public static synchronized void shutdown() {
        if (instance == null) return;
        TerrainService service = instance;
        instance = null;
        synchronized (service.pending) { service.running = false; }
        boolean interrupted = false;
        while (service.poller.isAlive()) {
            try { service.poller.join(); }
            catch (InterruptedException e) { interrupted = true; }
        }
        // Keep the direct buffers alive until all native writes have stopped.
        NativeBridge.destroy(service.handle);
        service.pending.values().forEach(p -> p.future.completeExceptionally(new IllegalStateException("server stopped")));
        service.pending.clear();
        if (interrupted) Thread.currentThread().interrupt();
    }

    public TerrainWorld openWorld(long seed, int scale, int minY, int height, int seaLevel) {
        int world = NativeBridge.openWorld(handle, seed, scale, minY, height, seaLevel);
        return new TerrainWorld(this, world, scale, minY, height, seaLevel);
    }

    void closeWorld(int world) {
        NativeBridge.closeWorld(handle, world);
    }

    CompletableFuture<RegionData> request(int world, int rx, int rz, int priority) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(RegionData.BYTES);
        return submit(() -> NativeBridge.requestRegion(handle, world, rx, rz, priority, buffer),
                () -> new RegionData(rx, rz, buffer), "region " + rx + "," + rz, buffer);
    }

    CompletableFuture<GridData> requestGrid(int world, double x0, double z0, double step, int w, int h, boolean latent, int priority) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(GridData.bytes(w, h));
        return submit(() -> NativeBridge.requestGrid(handle, world, x0, z0, step, w, h, latent ? 1 : 0, priority, buffer),
                () -> new GridData(w, h, buffer), "map grid at " + x0 + "," + z0, buffer);
    }

    private interface Submit {
        long run();
    }

    /** Cancelling the returned future drops the native request if it has not started yet. */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> submit(Submit submit, Supplier<T> result, String what, ByteBuffer buffer) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        long id;
        // Register before submitting so a fast completion cannot race the map insertion.
        synchronized (pending) {
            if (!running) return CompletableFuture.failedFuture(new IllegalStateException("server stopped"));
            id = submit.run();
            pending.put(id, new Pending(future, (Supplier<Object>) result, what, buffer));
        }
        future.whenComplete((v, e) -> {
            if (future.isCancelled()) {
                synchronized (pending) {
                    if (running && pending.containsKey(id)) NativeBridge.cancel(handle, id);
                }
            }
        });
        return (CompletableFuture<T>) future;
    }

    long[] findLand(int world) {
        return NativeBridge.findLand(handle, world);
    }

    /** {regionsDone, regionsFailed, gpuBatches, gpuMs, cpuMs, queued}. */
    public double[] stats() {
        return NativeBridge.stats(handle);
    }

    private void pollLoop() {
        long[] ids = new long[256];
        int[] status = new int[256];
        while (running) {
            int n = NativeBridge.poll(handle, ids, status, 1000);
            if (n == 0) continue;
            synchronized (pending) {
                // Only waits for in-flight request() calls to register their entry.
            }
            for (int i = 0; i < n; i++) {
                Pending p = pending.remove(ids[i]);
                if (p == null) continue;
                if (status[i] == 0) {
                    p.future.complete(p.result.get());
                } else {
                    String error = NativeBridge.takeError(handle, ids[i]);
                    p.future.completeExceptionally(status[i] == 2 ? new CancellationException(p.what + " cancelled")
                            : new IllegalStateException("terrain generation failed for " + p.what + ": " + error));
                }
            }
        }
    }

}
