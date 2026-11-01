package moe.phrolova.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import moe.phrolova.engine.GridData;
import moe.phrolova.engine.RegionData;
import moe.phrolova.engine.TerrainWorld;
import moe.phrolova.world.TerrainBiomes;
import org.slf4j.Logger;

/**
 * Scenic location search. Finds spots whose surroundings match a query: a share of target biomes around the
 * spot, mountains rising above it, height, temperature and precipitation ranges.
 * <ol>
 * <li>Screen: one cheap coarse-map grid over the whole area rules out cells that cannot match (open ocean,
 *     impossible climate, no high ground).</li>
 * <li>Scan: the remaining cells, nearest first, are sampled from the latent low-frequency band; window statistics
 *     come from summed-area tables and separable sliding maxima. Results stream out as they are found.</li>
 * <li>Verify: the best results are re-measured on full regions.</li>
 * </ol>
 */
final class BiomeSearch {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CELL = 128;          // scan pixels per cell side
    private static final int MAX_CELLS = 2500;
    private static final int MAX_ACTIVE = 3;
    private static final int IN_FLIGHT = 2;

    /** Biome set as a bitmask over TerrainBiomes ids; null bounds are unconstrained. */
    record Query(long biomes, double minFraction, int windowRadius, int reliefRadius, int minRelief, Integer minPeakY,
                 Integer floorMinY, Integer floorMaxY, Float tempMin, Float tempMax, Float precipMin, Float precipMax,
                 int maxResults) {

        static Query parse(JsonObject o) {
            long mask = 0;
            if (o.has("biomes") && o.get("biomes").isJsonArray())
                for (JsonElement e : o.getAsJsonArray("biomes")) {
                    int id = e.getAsInt();
                    if (id < 0 || id >= 64) throw Http.badRequest("bad biome id " + id);
                    mask |= 1L << id;
                }
            double frac = Math.clamp(Http.num(o, "minFraction", mask == 0 ? 0 : 0.3), 0, 1);
            int window = (int) Math.clamp(Http.num(o, "windowRadius", 384), 32, 4096);
            int relief = (int) Math.clamp(Http.num(o, "reliefRadius", 1536), 64, 8192);
            return new Query(mask, frac, window, relief, (int) Math.max(0, Http.num(o, "minRelief", 0)),
                    optInt(o, "minPeakY"), optInt(o, "floorMinY"), optInt(o, "floorMaxY"), optFloat(o, "tempMin"),
                    optFloat(o, "tempMax"), optFloat(o, "precipMin"), optFloat(o, "precipMax"),
                    (int) Math.clamp(Http.num(o, "maxResults", 25), 1, 100));
        }

        private static Integer optInt(JsonObject o, String k) {
            return o.has(k) && !o.get(k).isJsonNull() ? (int) Math.round(Http.num(o, k)) : null;
        }

        private static Float optFloat(JsonObject o, String k) {
            return o.has(k) && !o.get(k).isJsonNull() ? (float) Http.num(o, k) : null;
        }

        boolean wants(int biome) {
            return (biomes >>> biome & 1) != 0;
        }

        boolean anyOcean() {
            for (int b = 0; b < 64; b++)
                if (wants(b) && TerrainBiomes.isOcean(b)) return true;
            return false;
        }
    }

    static final class Result {
        int x, z, y, peakX, peakZ, peakY, biome;
        double score, fraction;
        float temp, precip;
        boolean verified;

        JsonObject json(double cx, double cz) {
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("z", z);
            o.addProperty("y", y);
            o.addProperty("score", Math.round(score * 1000) / 1000.0);
            o.addProperty("fraction", Math.round(fraction * 1000) / 1000.0);
            o.addProperty("relief", peakY - y);
            o.addProperty("peakX", peakX);
            o.addProperty("peakZ", peakZ);
            o.addProperty("peakY", peakY);
            o.addProperty("biome", biome);
            o.addProperty("temp", temp);
            o.addProperty("precip", precip);
            o.addProperty("verified", verified);
            o.addProperty("distance", Math.round(Math.hypot(x - cx, z - cz)));
            return o;
        }
    }

    private final class Search {
        final String id = UUID.randomUUID().toString();
        final MapWorld w;
        final double cx, cz;
        final int radius;
        final Query q;
        final long started = System.currentTimeMillis();
        final List<Result> results = new ArrayList<>();
        final List<CompletableFuture<?>> inflight = new ArrayList<>();
        volatile String phase = "screening", error;
        volatile int cellsDone, cellsTotal, cellsSkipped, step;
        volatile boolean cancelled;
        long lastSent;

        Search(MapWorld w, double cx, double cz, int radius, Query q) {
            this.w = w;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.q = q;
        }

        <T> T await(CompletableFuture<T> f) throws Exception {
            synchronized (inflight) {
                if (cancelled) f.cancel(false);
                if (!inflight.contains(f)) inflight.add(f);
            }
            try {
                return f.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception ex) throw ex;
                throw e;
            } finally {
                synchronized (inflight) {
                    inflight.remove(f);
                }
            }
        }

        <T> CompletableFuture<T> track(CompletableFuture<T> f) {
            synchronized (inflight) {
                if (cancelled) f.cancel(false);
                inflight.add(f);
            }
            f.whenComplete((v, e) -> { synchronized (inflight) { inflight.remove(f); } });
            return f;
        }

        void cancel() {
            cancelled = true;
            synchronized (inflight) {
                inflight.forEach(f -> f.cancel(false));
            }
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("dim", w.id());
            o.addProperty("x", cx);
            o.addProperty("z", cz);
            o.addProperty("radius", radius);
            o.addProperty("phase", phase);
            o.addProperty("cellsDone", cellsDone);
            o.addProperty("cellsTotal", cellsTotal);
            o.addProperty("cellsSkipped", cellsSkipped);
            o.addProperty("step", step);
            o.addProperty("elapsedMs", System.currentTimeMillis() - started);
            if (error != null) o.addProperty("error", error);
            JsonArray arr = new JsonArray();
            synchronized (results) {
                for (Result r : results) arr.add(r.json(cx, cz));
            }
            o.add("results", arr);
            return o;
        }
    }

    private final Events events;
    private final Map<String, Search> searches = new LinkedHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    BiomeSearch(Events events) {
        this.events = events;
    }

    void close() {
        synchronized (searches) {
            searches.values().forEach(Search::cancel);
        }
        workers.shutdownNow();
    }

    JsonObject start(MapWorld w, double cx, double cz, int radius, Query q) {
        Search s = new Search(w, cx, cz, radius, q);
        synchronized (searches) {
            List<Search> active = searches.values().stream().filter(x -> x.phase.equals("screening")
                    || x.phase.equals("scanning") || x.phase.equals("verifying")).toList();
            for (int i = 0; i <= active.size() - MAX_ACTIVE; i++) active.get(i).cancel();
            searches.put(s.id, s);
            while (searches.size() > 16) searches.remove(searches.keySet().iterator().next());
        }
        workers.submit(() -> run(s));
        return s.json();
    }

    boolean cancel(String id) {
        Search s;
        synchronized (searches) {
            s = searches.get(id);
        }
        if (s == null) return false;
        s.cancel();
        return true;
    }

    JsonObject get(String id) {
        synchronized (searches) {
            Search s = searches.get(id);
            return s == null ? null : s.json();
        }
    }

    JsonArray list() {
        JsonArray a = new JsonArray();
        synchronized (searches) {
            for (Search s : searches.values()) {
                JsonObject o = s.json();
                a.add(o);
            }
        }
        return a;
    }

    private void publish(Search s, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - s.lastSent < 250) return;
        s.lastSent = now;
        events.broadcast("search", s.json());
    }

    // ---- The search --------------------------------------------------------------------------

    private void run(Search s) {
        try {
            scan(s);
            if (!s.cancelled) {
                s.phase = "verifying";
                publish(s, true);
                verify(s);
            }
            s.phase = s.cancelled ? "cancelled" : "done";
        } catch (CancellationException e) {
            s.phase = "cancelled";
        } catch (Exception e) {
            if (s.cancelled) {
                s.phase = "cancelled";
            } else {
                s.phase = "failed";
                s.error = e.getMessage() == null ? e.toString() : e.getMessage();
                LOGGER.warn("Biome search failed", e);
            }
        }
        publish(s, true);
    }

    private static int scanStep(int scale) {
        int want = Math.max(8, 4 * scale);
        return Integer.highestOneBit(want - 1) << 1;
    }

    private void scan(Search s) throws Exception {
        Query q = s.q;
        int step = scanStep(s.w.scale());
        s.step = step;
        int span = CELL * step;
        int margin = (Math.max(q.windowRadius(), q.reliefRadius()) + step - 1) / step + 1;

        // Cells overlapping the search circle, nearest first.
        record Cell(int i, int j, double d) {}
        List<Cell> cells = new ArrayList<>();
        int ci0 = (int) Math.floor((s.cx - s.radius) / span), ci1 = (int) Math.floor((s.cx + s.radius) / span);
        int cj0 = (int) Math.floor((s.cz - s.radius) / span), cj1 = (int) Math.floor((s.cz + s.radius) / span);
        for (int j = cj0; j <= cj1; j++)
            for (int i = ci0; i <= ci1; i++) {
                double nx = Math.clamp(s.cx, (double) i * span, (double) (i + 1) * span);
                double nz = Math.clamp(s.cz, (double) j * span, (double) (j + 1) * span);
                if (Math.hypot(nx - s.cx, nz - s.cz) > s.radius) continue;
                cells.add(new Cell(i, j, Math.hypot((i + 0.5) * span - s.cx, (j + 0.5) * span - s.cz)));
            }
        if (cells.size() > MAX_CELLS) throw Http.badRequest("search area too large; use a smaller radius");
        cells.sort(Comparator.comparingDouble(Cell::d));
        s.cellsTotal = cells.size();
        publish(s, true);

        // Screening on the coarse map: 16 x 16 samples per cell.
        int cs = span / 16;
        double sx0 = (double) ci0 * span - margin * step, sz0 = (double) cj0 * span - margin * step;
        int sw = (int) Math.ceil(((double) (ci1 - ci0 + 1) * span + 2.0 * margin * step) / cs);
        int sh = (int) Math.ceil(((double) (cj1 - cj0 + 1) * span + 2.0 * margin * step) / cs);
        if (sw > 4096 || sh > 4096) throw Http.badRequest("search area too large; use a smaller radius");
        GridData coarse = s.await(s.w.terrain().grid(sx0, sz0, cs, sw, sh, false, TerrainWorld.PRIORITY_SEARCH));
        boolean needLand = !q.anyOcean();
        int marginCs = (margin * step + cs - 1) / cs;
        List<Cell> keep = new ArrayList<>();
        for (Cell c : cells) {
            int px0 = (c.i - ci0) * 16, pz0 = (c.j - cj0) * 16;
            int maxY = Integer.MIN_VALUE;
            float tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
            for (int z = pz0; z < pz0 + 16 + 2 * marginCs; z++)
                for (int x = px0; x < px0 + 16 + 2 * marginCs; x++) {
                    if (x >= sw || z >= sh) continue;
                    maxY = Math.max(maxY, coarse.y(x, z));
                    float t = GridData.temperature(coarse.tempCode(x, z));
                    tMin = Math.min(tMin, t);
                    tMax = Math.max(tMax, t);
                }
            boolean skip = needLand && maxY < s.w.seaLevel() - 2;
            if (q.tempMin() != null && tMax < q.tempMin() - 6) skip = true;
            if (q.tempMax() != null && tMin > q.tempMax() + 6) skip = true;
            // The coarse map flattens peaks a lot; only rule out clearly low land.
            if (q.minPeakY() != null && maxY < s.w.seaLevel() + (q.minPeakY() - s.w.seaLevel()) * 0.35) skip = true;
            if (skip) s.cellsSkipped++;
            else keep.add(c);
        }
        s.cellsDone = s.cellsSkipped;
        s.phase = "scanning";
        publish(s, true);

        // Scanning: a few grids in flight so the GPU queue never runs dry.
        int n = CELL + 2 * margin;
        ArrayDeque<CompletableFuture<GridData>> window = new ArrayDeque<>();
        ArrayDeque<Cell> windowCells = new ArrayDeque<>();
        int next = 0;
        while (next < keep.size() || !window.isEmpty()) {
            while (!s.cancelled && next < keep.size() && window.size() < IN_FLIGHT) {
                Cell c = keep.get(next++);
                CompletableFuture<GridData> f = s.w.terrain().grid((double) c.i * span - margin * step,
                        (double) c.j * span - margin * step, step, n, n, true, TerrainWorld.PRIORITY_SEARCH);
                // Tracked from submission, so cancelling the search also drops grids still queued.
                synchronized (s.inflight) {
                    s.inflight.add(f);
                }
                f.whenComplete((v, e) -> {
                    synchronized (s.inflight) {
                        s.inflight.remove(f);
                    }
                });
                window.add(f);
                windowCells.add(c);
            }
            if (s.cancelled) {
                window.forEach(f -> f.cancel(false));
                throw new CancellationException();
            }
            GridData g = s.await(window.poll());
            Cell c = windowCells.poll();
            evaluate(s, g, (double) c.i * span - margin * step, (double) c.j * span - margin * step, step, margin);
            s.cellsDone++;
            publish(s, false);
        }
    }

    /** Scores every few pixels of a cell's interior and merges the winners into the result list. */
    private void evaluate(Search s, GridData g, double x0, double z0, int step, int margin) {
        Query q = s.q;
        int n = g.width;
        int rw = Math.max(1, q.windowRadius() / step), rr = Math.max(1, q.reliefRadius() / step);
        int[] y = new int[n * n];
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) y[z * n + x] = g.y(x, z);

        // Summed-area table of target-biome pixels.
        int[] sat = new int[(n + 1) * (n + 1)];
        boolean targets = q.biomes() != 0;
        if (targets)
            for (int z = 0; z < n; z++)
                for (int x = 0; x < n; x++)
                    sat[(z + 1) * (n + 1) + x + 1] = (q.wants(g.biome(x, z)) ? 1 : 0) + sat[z * (n + 1) + x + 1]
                            + sat[(z + 1) * (n + 1) + x] - sat[z * (n + 1) + x];
        int[] peak = slidingMaxIndices(y, n, rr);

        int stride = Math.max(1, rw / 2);
        List<Result> found = new ArrayList<>();
        for (int z = margin; z < n - margin; z += stride)
            for (int x = margin; x < n - margin; x += stride) {
                int o = z * n + x, cy = y[o];
                if (cy < s.w.seaLevel() - 1 && !q.anyOcean()) continue;
                if (q.floorMinY() != null && cy < q.floorMinY()) continue;
                if (q.floorMaxY() != null && cy > q.floorMaxY()) continue;
                double frac = 0;
                if (targets) {
                    if (!q.wants(g.biome(x, z))) continue;
                    int a = Math.max(0, z - rw), b = Math.min(n, z + rw + 1), l = Math.max(0, x - rw), r = Math.min(n, x + rw + 1);
                    int count = sat[b * (n + 1) + r] - sat[a * (n + 1) + r] - sat[b * (n + 1) + l] + sat[a * (n + 1) + l];
                    frac = count / (double) ((b - a) * (r - l));
                    if (frac < q.minFraction()) continue;
                }
                int peakIndex = peak[o];
                int py = y[peakIndex], relief = py - cy;
                if (q.minPeakY() != null && py < q.minPeakY()) continue;
                if (relief < q.minRelief()) continue;
                float t = GridData.temperature(g.tempCode(x, z)), p = GridData.precipitation(g.precipCode(x, z));
                if (q.tempMin() != null && t < q.tempMin() || q.tempMax() != null && t > q.tempMax()) continue;
                if (q.precipMin() != null && p < q.precipMin() || q.precipMax() != null && p > q.precipMax()) continue;

                // Score: biome share, mountains above the spot and a level floor, averaged over what the query asks.
                double score = 0, weight = 0;
                if (targets) {
                    score += frac;
                    weight += 1;
                }
                if (q.minRelief() > 0 || q.minPeakY() != null) {
                    score += Math.min(1, relief / (2.0 * Math.max(q.minRelief(), 100)));
                    weight += 1;
                }
                int dx = Math.abs(y[o + 1] - y[o - 1]), dz = Math.abs(y[o + n] - y[o - n]);
                double grade = (dx + dz) / (4.0 * step);
                score += 0.25 * Math.clamp(1 - grade / 0.3, 0, 1);
                weight += 0.25;

                Result res = new Result();
                res.x = (int) Math.floor(x0 + (x + 0.5) * step);
                res.z = (int) Math.floor(z0 + (z + 0.5) * step);
                if (Math.hypot(res.x - s.cx, res.z - s.cz) > s.radius) continue;
                res.y = cy;
                res.peakY = py;
                res.biome = g.biome(x, z);
                res.fraction = frac;
                res.temp = t;
                res.precip = p;
                res.score = score / weight;
                // Where the highest point is, so the map can show the view.
                int bx = peakIndex % n, bz = peakIndex / n;
                res.peakX = (int) Math.floor(x0 + (bx + 0.5) * step);
                res.peakZ = (int) Math.floor(z0 + (bz + 0.5) * step);
                found.add(res);
            }
        found.sort(Comparator.comparingDouble((Result r) -> -r.score));
        int separation = Math.max(2 * q.windowRadius(), 8 * step);
        synchronized (s.results) {
            for (Result r : found) merge(s.results, r, separation, q.maxResults());
        }
    }

    /** Inserts with non-maximum suppression: within {@code sep} blocks only the best result survives. */
    private static void merge(List<Result> results, Result r, int sep, int max) {
        for (Result o : results)
            if (Math.hypot(o.x - r.x, o.z - r.z) < sep && o.score >= r.score) return;
        results.removeIf(o -> Math.hypot(o.x - r.x, o.z - r.z) < sep);
        int at = 0;
        while (at < results.size() && results.get(at).score >= r.score) at++;
        if (at >= max) return;
        results.add(at, r);
        while (results.size() > max) results.removeLast();
    }

    /** Square-window maximum (radius r) via two separable passes with monotonic deques. */
    private static int[] slidingMaxIndices(int[] v, int n, int r) {
        int[] tmp = new int[n * n], out = new int[n * n];
        int[] dq = new int[n], indices = new int[n * n];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        for (int row = 0; row < n; row++) pass(indices, tmp, v, row * n, 1, n, r, dq);
        for (int col = 0; col < n; col++) pass(tmp, out, v, col, n, n, r, dq);
        return out;
    }

    private static void pass(int[] in, int[] out, int[] heights, int base, int stride, int n, int r, int[] dq) {
        int head = 0, tail = 0, next = 0;
        for (int i = 0; i < n; i++) {
            int hi = Math.min(n - 1, i + r);
            while (next <= hi) {
                int val = heights[in[base + next * stride]];
                while (tail > head && heights[in[base + dq[tail - 1] * stride]] <= val) tail--;
                dq[tail++] = next++;
            }
            while (dq[head] < i - r) head++;
            out[base + i * stride] = in[base + dq[head] * stride];
        }
    }

    /** Re-measures the best results on full regions: biome share and floor height become exact. */
    private void verify(Search s) throws Exception {
        List<Result> todo;
        synchronized (s.results) {
            todo = new ArrayList<>(s.results);
        }
        int rw = s.q.windowRadius();
        for (Result r : todo) {
            if (s.cancelled) throw new CancellationException();
            int rx0 = Math.floorDiv(r.x - rw, 256), rx1 = Math.floorDiv(r.x + rw, 256);
            int rz0 = Math.floorDiv(r.z - rw, 256), rz1 = Math.floorDiv(r.z + rw, 256);
            if ((rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 400) continue;  // huge windows stay estimated
            Map<Long, RegionData> regions = new LinkedHashMap<>();
            List<CompletableFuture<RegionData>> futures = new ArrayList<>();
            for (int rz = rz0; rz <= rz1; rz++)
                for (int rx = rx0; rx <= rx1; rx++) futures.add(s.track(s.w.terrain().regionForMap(rx, rz, TerrainWorld.PRIORITY_SEARCH)));
            for (CompletableFuture<RegionData> f : futures) {
                RegionData d = s.await(f);
                regions.put(((long) d.rx << 32) | (d.rz & 0xffffffffL), d);
            }
            long hit = 0, all = 0;
            for (int z = r.z - rw; z <= r.z + rw; z += 4)
                for (int x = r.x - rw; x <= r.x + rw; x += 4) {
                    RegionData d = regions.get(((long) (x >> 8) << 32) | ((z >> 8) & 0xffffffffL));
                    all++;
                    if (s.q.biomes() == 0 || s.q.wants(d.biome(x & 255, z & 255))) hit++;
                }
            RegionData peakRegion = s.await(s.w.terrain().regionForMap(r.peakX >> 8, r.peakZ >> 8, TerrainWorld.PRIORITY_SEARCH));
            RegionData c = regions.get(((long) (r.x >> 8) << 32) | ((r.z >> 8) & 0xffffffffL));
            synchronized (s.results) {
                r.y = c.height(r.x & 255, r.z & 255);
                r.biome = c.biome(r.x & 255, r.z & 255);
                r.temp = GridData.temperature(c.tempCode(r.x & 255, r.z & 255));
                r.precip = GridData.precipitation(c.precipCode(r.x & 255, r.z & 255));
                if (s.q.biomes() != 0) r.fraction = hit / (double) all;
                r.peakY = Math.max(peakRegion.height(r.peakX & 255, r.peakZ & 255), r.y);
                r.verified = true;
                // The overview blurs small patches; drop spots the real terrain does not back up.
                Query q = s.q;
                if (q.biomes() != 0 && (!q.wants(r.biome) || r.fraction < q.minFraction())
                        || q.floorMinY() != null && r.y < q.floorMinY() || q.floorMaxY() != null && r.y > q.floorMaxY()
                        || q.minPeakY() != null && r.peakY < q.minPeakY() || r.peakY - r.y < q.minRelief()
                        || q.tempMin() != null && r.temp < q.tempMin() || q.tempMax() != null && r.temp > q.tempMax()
                        || q.precipMin() != null && r.precip < q.precipMin() || q.precipMax() != null && r.precip > q.precipMax())
                    s.results.remove(r);
            }
            publish(s, false);
        }
    }
}
