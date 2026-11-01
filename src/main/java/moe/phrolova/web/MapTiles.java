package moe.phrolova.web;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import moe.phrolova.engine.RegionData;
import moe.phrolova.engine.TerrainWorld;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * The map tile pyramid. A tile is 256x256 pixels at 2^z blocks per pixel, holding top block Y, biome,
 * temperature and precipitation per pixel. Tiles are generated on demand, cached on disk per world, and
 * cancelled when no browser is looking at them any more. Coarse tiles just above the latent levels are
 * refined in the background from their children, and browsers are told when a sharper tile is ready.
 */
final class MapTiles {
    static final int FORMAT = 2; // Meadow classification; invalidate pre-Meadow map caches.
    static final int SIZE = 256;
    static final int Q_COARSE = 0, Q_OVERVIEW = 1, Q_DETAIL = 2;
    /** Levels above the latent levels that are refined from children in the background. */
    static final int REFINE_LEVELS = 2;
    private static final int MAX_REFINE_QUEUE = 48;
    private static final long VIEWPORT_TTL_MS = 60_000;
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Decoded tile contents. */
    static final class Tile {
        int quality;
        final short[] y = new short[SIZE * SIZE];
        final byte[] biome = new byte[SIZE * SIZE], temp = new byte[SIZE * SIZE], precip = new byte[SIZE * SIZE];
    }

    /** An encoded tile: the quality and the gzip-compressed body as served. */
    record Encoded(int quality, byte[] gzip) {}

    record Key(String dim, int z, int x, int y) {}

    private record Viewport(String dim, double x0, double z0, double x1, double z1, long at) {}

    private final class Job {
        final Key key;
        final List<CompletableFuture<?>> parts = new ArrayList<>();
        final CompletableFuture<Encoded> result = new CompletableFuture<>();
        final long started = System.currentTimeMillis();

        Job(Key key) {
            this.key = key;
        }

        void cancel() {
            synchronized (parts) {
                for (CompletableFuture<?> f : parts) f.cancel(false);
            }
            result.completeExceptionally(new CancellationException("tile no longer visible"));
        }
    }

    private final MinecraftServer server;
    private final Events events;
    private final Map<Key, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Viewport> viewports = new ConcurrentHashMap<>();
    private final LinkedBlockingDeque<Key> refineQueue = new LinkedBlockingDeque<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    MapTiles(MinecraftServer server, Events events) {
        this.server = server;
        this.events = events;
        workers.submit(this::refineLoop);
        workers.submit(this::sweepLoop);
    }

    void close() {
        closed = true;
        jobs.values().forEach(Job::cancel);
        workers.shutdownNow();
    }

    // ---- Viewports ---------------------------------------------------------------------------

    /** Records what a browser tab is looking at, in block coordinates. */
    void viewport(String client, String dim, double x0, double z0, double x1, double z1) {
        if (viewports.size() > 256) viewports.clear();
        viewports.put(client, new Viewport(dim, Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1),
                System.currentTimeMillis()));
    }

    private boolean wanted(Key k) {
        long now = System.currentTimeMillis();
        double span = (double) SIZE * (1L << k.z);
        double tx0 = k.x * span, tz0 = k.y * span, tx1 = tx0 + span, tz1 = tz0 + span;
        boolean any = false;
        for (Viewport v : viewports.values()) {
            if (!v.dim.equals(k.dim) || now - v.at > VIEWPORT_TTL_MS) continue;
            any = true;
            double mx = (v.x1 - v.x0) * 0.25, mz = (v.z1 - v.z0) * 0.25;
            if (tx1 >= v.x0 - mx && tx0 <= v.x1 + mx && tz1 >= v.z0 - mz && tz0 <= v.z1 + mz) return true;
        }
        return !any;  // nobody reported a viewport: keep everything
    }

    private void sweepLoop() {
        while (!closed) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Job j : jobs.values())
                if (now - j.started > 500 && !j.result.isDone() && !wanted(j.key)) j.cancel();
        }
    }

    // ---- Serving -----------------------------------------------------------------------------

    /** The tile, generating it if needed. Blocks the calling (virtual) thread. */
    Encoded get(MapWorld w, int z, int x, int y) throws Exception {
        if (z < 0 || z > w.maxZoom()) throw Http.notFound();
        long limit = 30_000_000L / ((long) SIZE << z) + 1;
        if (Math.abs((long) x) > limit || Math.abs((long) y) > limit) throw Http.notFound();
        Key key = new Key(w.id(), z, x, y);
        Encoded cached = read(w, key);
        if (cached == null) cached = job(w, key, TerrainWorld.PRIORITY_MAP).get(180, TimeUnit.SECONDS);
        if (cached.quality == Q_COARSE && z <= w.overviewMax() + REFINE_LEVELS) queueRefine(key);
        return cached;
    }

    /** Best quality a level reaches. */
    static int finalQuality(MapWorld w, int z) {
        return z <= w.detailMax() ? Q_DETAIL : z <= w.overviewMax() + REFINE_LEVELS ? Q_OVERVIEW : Q_COARSE;
    }

    private CompletableFuture<Encoded> job(MapWorld w, Key key, int priority) {
        Job[] created = {null};
        Job job = jobs.computeIfAbsent(key, k -> created[0] = new Job(k));
        if (created[0] != null) start(w, job, priority);
        return job.result;
    }

    private void start(MapWorld w, Job job, int priority) {
        Key k = job.key;
        CompletableFuture<Tile> tile;
        try {
            if (k.z <= w.detailMax()) tile = detail(w, job, priority);
            else tile = grid(w, job, k.z <= w.overviewMax(), priority);
        } catch (RuntimeException e) {
            tile = CompletableFuture.failedFuture(e);
        }
        tile.thenApplyAsync(t -> {
            try {
                Encoded enc = encode(t);
                write(w, k, enc);
                return enc;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, workers).whenComplete((enc, err) -> {
            jobs.remove(k, job);
            if (err != null) job.result.completeExceptionally(err);
            else job.result.complete(enc);
        });
    }

    private <T> CompletableFuture<T> part(Job job, CompletableFuture<T> f) {
        synchronized (job.parts) {
            job.parts.add(f);
        }
        if (job.result.isDone()) f.cancel(false);
        return f;
    }

    /** Levels up to detailMax: full regions, averaged down. */
    private CompletableFuture<Tile> detail(MapWorld w, Job job, int priority) {
        Key k = job.key;
        int n = 1 << k.z, per = SIZE / n;
        List<CompletableFuture<RegionData>> regions = new ArrayList<>();
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) regions.add(part(job, w.terrain().regionForMap(k.x * n + i, k.y * n + j, priority)));
        return CompletableFuture.allOf(regions.toArray(CompletableFuture[]::new)).thenApplyAsync(v -> {
            Tile t = new Tile();
            t.quality = Q_DETAIL;
            for (int j = 0; j < n; j++)
                for (int i = 0; i < n; i++) {
                    RegionData r = regions.get(j * n + i).join();
                    for (int pz = 0; pz < per; pz++)
                        for (int px = 0; px < per; px++) {
                            int sum = 0;
                            for (int dz = 0; dz < n; dz++)
                                for (int dx = 0; dx < n; dx++) sum += r.height(px * n + dx, pz * n + dz);
                            int lx = px * n + n / 2, lz = pz * n + n / 2;
                            int o = (j * per + pz) * SIZE + i * per + px;
                            t.y[o] = (short) Math.floorDiv(sum + n * n / 2, n * n);
                            t.biome[o] = (byte) r.biome(lx, lz);
                            t.temp[o] = (byte) r.tempCode(lx, lz);
                            t.precip[o] = (byte) r.precipCode(lx, lz);
                        }
                }
            return t;
        }, workers);
    }

    private CompletableFuture<Tile> grid(MapWorld w, Job job, boolean latent, int priority) {
        Key k = job.key;
        double step = 1L << k.z, span = SIZE * step;
        return part(job, w.terrain().grid(k.x * span, k.y * span, step, SIZE, SIZE, latent, priority)).thenApply(g -> {
            Tile t = new Tile();
            t.quality = latent ? Q_OVERVIEW : Q_COARSE;
            for (int z = 0; z < SIZE; z++)
                for (int x = 0; x < SIZE; x++) {
                    int o = z * SIZE + x;
                    t.y[o] = (short) g.y(x, z);
                    t.biome[o] = (byte) g.biome(x, z);
                    t.temp[o] = (byte) g.tempCode(x, z);
                    t.precip[o] = (byte) g.precipCode(x, z);
                }
            return t;
        });
    }

    // ---- Background refinement ---------------------------------------------------------------

    private void queueRefine(Key key) {
        if (refineQueue.contains(key)) return;
        refineQueue.offerFirst(key);  // newest first: what the user looks at now
        while (refineQueue.size() > MAX_REFINE_QUEUE) refineQueue.pollLast();
    }

    private void refineLoop() {
        while (!closed) {
            Key key;
            try {
                key = refineQueue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            if (!wanted(key)) continue;
            MapWorld w = MapWorld.byId(server, key.dim);
            if (w == null) continue;
            try {
                refine(w, key);
            } catch (CancellationException e) {
                // Panned away.
            } catch (Exception e) {
                if (!closed) LOGGER.debug("Map refinement of {} failed: {}", key, e.toString());
            }
        }
    }

    /** Ensures a tile of at least overview quality exists; returns it decoded. */
    private Tile refine(MapWorld w, Key key) throws Exception {
        if (closed || !wanted(key)) throw new CancellationException();
        Encoded have = read(w, key);
        if (have != null && have.quality >= Q_OVERVIEW) return decode(have);
        if (key.z <= w.overviewMax()) return decode(job(w, key, TerrainWorld.PRIORITY_MAP_REFINE).get(10, TimeUnit.MINUTES));
        Tile[] children = new Tile[4];
        for (int c = 0; c < 4; c++)
            children[c] = refine(w, new Key(key.dim, key.z - 1, key.x * 2 + (c & 1), key.y * 2 + (c >> 1)));
        Tile t = new Tile();
        t.quality = Q_OVERVIEW;
        int half = SIZE / 2;
        for (int c = 0; c < 4; c++) {
            Tile ch = children[c];
            int ox = (c & 1) * half, oz = (c >> 1) * half;
            for (int pz = 0; pz < half; pz++)
                for (int px = 0; px < half; px++) {
                    int s = pz * 2 * SIZE + px * 2, o = (oz + pz) * SIZE + ox + px;
                    t.y[o] = (short) Math.floorDiv(ch.y[s] + ch.y[s + 1] + ch.y[s + SIZE] + ch.y[s + SIZE + 1] + 2, 4);
                    t.biome[o] = ch.biome[s];
                    t.temp[o] = ch.temp[s];
                    t.precip[o] = ch.precip[s];
                }
        }
        Encoded enc = encode(t);
        write(w, key, enc);
        JsonObject o = new JsonObject();
        o.addProperty("dim", key.dim);
        o.addProperty("z", key.z);
        o.addProperty("x", key.x);
        o.addProperty("y", key.y);
        o.addProperty("quality", enc.quality);
        events.broadcast("tile", o);
        return t;
    }

    // ---- Encoding and disk cache -------------------------------------------------------------

    // Body: "FDMT", u8 format, u8 quality, u16 size, 8 reserved bytes, then int16 Y per pixel (each row delta-coded
    // from its first pixel), uint8 biome, temperature and precipitation planes. Gzip-compressed as a whole.
    static Encoded encode(Tile t) throws IOException {
        int n = SIZE * SIZE;
        ByteBuffer b = ByteBuffer.allocate(16 + n * 5).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'F').put((byte) 'D').put((byte) 'M').put((byte) 'T').put((byte) FORMAT).put((byte) t.quality)
                .putShort((short) SIZE).putLong(0);
        for (int r = 0; r < SIZE; r++) {
            short prev = 0;
            for (int c = 0; c < SIZE; c++) {
                short v = t.y[r * SIZE + c];
                b.putShort((short) (v - prev));
                prev = v;
            }
        }
        b.put(t.biome).put(t.temp).put(t.precip);
        return new Encoded(t.quality, StaticFiles.gzip(b.array()));
    }

    static Tile decode(Encoded e) throws IOException {
        byte[] raw;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(e.gzip))) {
            raw = in.readAllBytes();
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (raw.length != 16 + SIZE * SIZE * 5 || b.getInt(0) != 0x544d4446 || raw[4] != FORMAT) throw new IOException("bad tile");
        Tile t = new Tile();
        t.quality = raw[5];
        b.position(16);
        for (int r = 0; r < SIZE; r++) {
            short prev = 0;
            for (int c = 0; c < SIZE; c++) {
                prev = (short) (prev + b.getShort());
                t.y[r * SIZE + c] = prev;
            }
        }
        b.get(t.biome).get(t.temp).get(t.precip);
        return t;
    }

    private static Path file(MapWorld w, Key k) {
        return w.cacheDir().resolve(Integer.toString(k.z)).resolve(k.x + "_" + k.y + ".fdmt");
    }

    // File: one quality byte, then the gzip body exactly as served.
    private static Encoded read(MapWorld w, Key k) {
        Path p = file(w, k);
        try {
            byte[] all = Files.readAllBytes(p);
            if (all.length < 2) return null;
            byte[] gz = new byte[all.length - 1];
            System.arraycopy(all, 1, gz, 0, gz.length);
            return new Encoded(all[0], gz);
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(MapWorld w, Key k, Encoded e) throws IOException {
        Path p = file(w, k);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        ByteArrayOutputStream bos = new ByteArrayOutputStream(e.gzip.length + 1);
        bos.write(e.quality);
        bos.write(e.gzip);
        Files.write(tmp, bos.toByteArray());
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
