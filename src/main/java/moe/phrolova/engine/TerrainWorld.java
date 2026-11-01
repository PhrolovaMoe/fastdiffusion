package moe.phrolova.engine;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import moe.phrolova.Config;

/** A world's view of the native pipeline: region futures with an LRU of finished regions. */
public final class TerrainWorld implements AutoCloseable {
    /** Request priorities; lower runs first. */
    public static final int PRIORITY_BLOCKING = 0;
    public static final int PRIORITY_CHUNK = 1;
    public static final int PRIORITY_PREFETCH = 5;
    public static final int PRIORITY_LOD = 8;
    public static final int PRIORITY_MAP = 9;
    public static final int PRIORITY_SEARCH = 11;
    public static final int PRIORITY_MAP_REFINE = 13;

    private final TerrainService service;
    private final int handle;
    public final int scale, minY, height, seaLevel;
    private final int capacity = Config.regionCache();
    private volatile boolean closed;

    private final LinkedHashMap<Long, CompletableFuture<RegionData>> regions = new LinkedHashMap<>(256, 0.75f, true);

    private void trimRegions() {
        var entries = regions.entrySet().iterator();
        while (regions.size() > capacity && entries.hasNext()) {
            if (entries.next().getValue().isDone()) entries.remove();
        }
    }

    // Last region touched by this thread: most lookups hit the same region repeatedly.
    private final ThreadLocal<RegionData> last = new ThreadLocal<>();

    TerrainWorld(TerrainService service, int handle, int scale, int minY, int height, int seaLevel) {
        this.service = service;
        this.handle = handle;
        this.scale = scale;
        this.minY = minY;
        this.height = height;
        this.seaLevel = seaLevel;
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xffffffffL);
    }

    public CompletableFuture<RegionData> region(int rx, int rz, int priority) {
        long k = key(rx, rz);
        synchronized (regions) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("world closed"));
            CompletableFuture<RegionData> f = regions.get(k);
            if (f != null && !f.isCompletedExceptionally()) return f;
            f = service.request(handle, rx, rz, priority);
            regions.put(k, f);
            f.whenComplete((region, error) -> {
                synchronized (regions) { trimRegions(); }
            });
            trimRegions();
            return f;
        }
    }

    /**
     * A region for map views: shares a region already cached for gameplay, but does not insert into that cache,
     * so browsing far away never evicts what players need. Cancelling the future drops a queued request.
     */
    public CompletableFuture<RegionData> regionForMap(int rx, int rz, int priority) {
        synchronized (regions) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("world closed"));
            CompletableFuture<RegionData> f = regions.get(key(rx, rz));
            if (f != null && !f.isCompletedExceptionally()) return f.copy();
        }
        return service.request(handle, rx, rz, priority);
    }

    /**
     * Samples a {@code w x h} grid at block-space pixel centers {@code x0 + (j + 0.5) * step}. {@code latent} samples
     * the latent low-frequency band (~8 native pixels); otherwise only the much cheaper coarse map (~256 native px).
     */
    public CompletableFuture<GridData> grid(double x0, double z0, double step, int w, int h, boolean latent, int priority) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("world closed"));
        return service.requestGrid(handle, x0, z0, step, w, h, latent, priority);
    }

    /** Meters per block at this world's scale. */
    public float blockMeters() {
        return 30.0f / scale;
    }

    /** Coarsest log2 blocks per sample still worth taking from full regions; regions are cheap at large scales. */
    public int regionLevelMax() {
        return scale >= 3 ? 3 : scale;
    }

    /** Coarsest log2 blocks per sample for 256-sample latent grids; latent tiles per side grow as 2^z / scale, kept to about six. */
    public int latentLevelMax() {
        return Math.max(regionLevelMax(), 31 - Integer.numberOfLeadingZeros(6 * scale));
    }

    public CompletableFuture<RegionData> regionAtBlock(int x, int z, int priority) {
        return region(x >> RegionData.SHIFT, z >> RegionData.SHIFT, priority);
    }

    /** Region containing the block, blocking until it is generated. */
    public RegionData regionNow(int x, int z) {
        if (closed) throw new IllegalStateException("world closed");
        int rx = x >> RegionData.SHIFT, rz = z >> RegionData.SHIFT;
        RegionData r = last.get();
        if (r != null && r.rx == rx && r.rz == rz) return r;
        r = region(rx, rz, PRIORITY_BLOCKING).join();
        last.set(r);
        return r;
    }

    /** Y of the highest solid block at a column. */
    public int surfaceY(int x, int z) {
        return regionNow(x, z).height(x & 255, z & 255);
    }

    public int biomeId(int x, int z) {
        return regionNow(x, z).biome(x & 255, z & 255);
    }

    /** Requests regions within {@code radius} regions of a block position without waiting. */
    public void prefetch(int x, int z, int radius) {
        int cx = x >> RegionData.SHIFT, cz = z >> RegionData.SHIFT;
        for (int r = 0; r <= radius; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++)
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) region(cx + dx, cz + dz, PRIORITY_PREFETCH + r);
    }

    /** Nearest land to the origin on the coarse map, {x, z} in blocks. */
    public long[] findLand() {
        return service.findLand(handle);
    }

    @Override
    public void close() {
        synchronized (regions) {
            if (closed) return;
            closed = true;
            regions.clear();
        }
        service.closeWorld(handle);
    }
}
