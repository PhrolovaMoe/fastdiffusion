package moe.phrolova.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import moe.phrolova.compat.ChunkyCompat;
import net.neoforged.fml.ModList;

/** Chunky pregeneration for the web map; inert when Chunky is not installed. */
final class ChunkyBridge {
    static final boolean INSTALLED = ModList.get().isLoaded("chunky");
    private static final Set<String> SHAPES = Set.of("square", "rectangle", "circle", "ellipse", "diamond");
    private static final Set<String> PATTERNS = Set.of("concentric", "loop", "spiral", "region");
    private static final double MAX_RADIUS = 1_000_000;

    private final Events events;
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    private final Map<String, String> emitted = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private volatile boolean hooked;

    ChunkyBridge(Events events) {
        this.events = events;
    }

    /** Hooks Chunky's progress events once Chunky has started; cheap to call repeatedly. */
    void attach() {
        if (!INSTALLED || hooked) return;
        hooked = ChunkyCompat.listen(p -> {
            long now = System.currentTimeMillis();
            Long last = lastSent.get(p.world());
            if (!p.complete() && last != null && now - last < 250) return;
            lastSent.put(p.world(), now);
            refresh(p.world());
        }, finished -> {
            ChunkyCompat.Task t = finished.task();
            String world = t.world();
            if (finished.cancelled()) {
                paused.remove(world);
                snapshots.remove(world);
                refresh(world);
                return;
            }
            if (finished.complete()) paused.remove(world);
            else paused.add(world);
            JsonObject o = encode(t, finished.complete() ? "complete" : "paused");
            snapshots.put(world, o);
            send(o);

        });
    }

    void detach() {
        if (INSTALLED && hooked) ChunkyCompat.unlisten();
        hooked = false;
    }

    JsonObject info() {
        attach();
        JsonObject o = new JsonObject();
        o.addProperty("installed", INSTALLED);
        o.addProperty("ready", INSTALLED && ChunkyCompat.available());
        JsonArray shapes = new JsonArray(), patterns = new JsonArray();
        SHAPES.stream().sorted().forEach(shapes::add);
        PATTERNS.stream().sorted().forEach(patterns::add);
        o.add("shapes", shapes);
        o.add("patterns", patterns);
        return o;
    }

    JsonObject status(String world) {
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        if (!INSTALLED) {
            o.addProperty("state", "unavailable");
            return o;
        }
        ChunkyCompat.Task t = ChunkyCompat.task(world);
        if (t == null) {
            JsonObject previous = snapshots.get(world);
            if (previous != null && (paused.contains(world) || "complete".equals(Http.str(previous, "state", "")))) {
                o = previous.deepCopy();
                o.addProperty("state", paused.contains(world) ? "paused" : "complete");
            } else o.addProperty("state", paused.contains(world) ? "paused" : "idle");
            return o;
        }
        o = encode(t, paused.contains(world) ? "pausing" : "running");
        snapshots.put(world, o.deepCopy());
        return o;
    }

    private static JsonObject encode(ChunkyCompat.Task t, String state) {
        JsonObject o = new JsonObject();
        o.addProperty("world", t.world());
        o.addProperty("state", state);
        o.addProperty("shape", t.shape());
        o.addProperty("pattern", t.pattern());
        o.addProperty("x", t.centerX());
        o.addProperty("z", t.centerZ());
        o.addProperty("radiusX", t.radiusX());
        o.addProperty("radiusZ", t.radiusZ());
        o.addProperty("done", t.done());
        o.addProperty("total", t.total());
        o.addProperty("percent", t.total() == 0 ? 0 : 100.0 * t.done() / t.total());
        o.addProperty("rate", t.rate());
        o.addProperty("etaSeconds", t.etaSeconds());
        o.addProperty("chunkX", t.chunkX());
        o.addProperty("chunkZ", t.chunkZ());
        return o;
    }

    JsonObject start(String world, JsonObject in) {
        requireReady();
        String shape = Http.str(in, "shape", "square"), pattern = Http.str(in, "pattern", "concentric");
        if (!SHAPES.contains(shape)) throw Http.badRequest("unsupported shape " + shape);
        if (!PATTERNS.contains(pattern)) throw Http.badRequest("unsupported pattern " + pattern);
        double x = Http.num(in, "x"), z = Http.num(in, "z");
        double rx = Http.num(in, "radiusX"), rz = Http.num(in, "radiusZ", rx);
        if (rx < 1 || rz < 1 || rx > MAX_RADIUS || rz > MAX_RADIUS) throw Http.badRequest("radius out of range");
        if (shape.equals("square") || shape.equals("circle") || shape.equals("diamond")) rz = rx;
        if (!ChunkyCompat.start(world, shape, x, z, rx, rz, pattern))
            throw new Http.Error(409, "Chunky is already generating this dimension");
        paused.remove(world);
        return broadcast(world);
    }

    JsonObject pause(String world) {
        requireReady();
        snapshots.put(world, status(world));
        if (!ChunkyCompat.pause(world)) throw new Http.Error(409, "nothing is running");
        paused.add(world);
        return broadcast(world);
    }

    JsonObject resume(String world) {
        requireReady();
        if (ChunkyCompat.task(world) != null) throw new Http.Error(409, "Chunky is still pausing; wait for its in-flight chunks to finish");
        if (!ChunkyCompat.resume(world)) throw new Http.Error(409, "no saved task to continue");
        paused.remove(world);
        return broadcast(world);
    }

    JsonObject cancel(String world) {
        requireReady();
        ChunkyCompat.cancel(world);
        snapshots.remove(world);
        paused.remove(world);
        return broadcast(world);
    }

    void refresh(String world) {
        send(status(world));
    }

    private void send(JsonObject o) {
        String world = Http.str(o, "world"), encoded = o.toString();
        if (!encoded.equals(emitted.put(world, encoded))) events.broadcast("chunky", o);
    }

    private JsonObject broadcast(String world) {
        JsonObject o = status(world);
        send(o);
        return o;
    }

    private void requireReady() {
        if (!INSTALLED) throw new Http.Error(501, "Chunky is not installed");
        attach();
        if (!ChunkyCompat.available()) throw new Http.Error(503, "Chunky has not started yet");
    }
}
