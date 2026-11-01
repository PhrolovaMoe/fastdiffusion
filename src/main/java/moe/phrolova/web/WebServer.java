package moe.phrolova.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import moe.phrolova.Config;
import moe.phrolova.engine.TerrainService;
import moe.phrolova.world.TerrainBiomes;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * The web map: an embedded HTTP server on its own port serving the bundled single-page app, a JSON API and a
 * server-sent event stream. Everything but the app's static files requires an operator session.
 */
public final class WebServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile WebServer instance;

    private final MinecraftServer server;
    private final HttpServer http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Auth auth;
    private final Events events = new Events();
    private final StaticFiles files = new StaticFiles();
    private final MapTiles tiles;
    private final Players players;
    private final Waypoints waypoints;
    private final ChunkLayer chunks;
    private final ChunkyBridge chunky;
    private final BiomeSearch search;
    private int tick;

    private WebServer(MinecraftServer server) throws IOException {
        this.server = server;
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("fastdiffusion").resolve("web");
        auth = new Auth(server, dir.resolve("sessions.json"));
        tiles = new MapTiles(server, events);
        players = new Players(server, events);
        waypoints = new Waypoints(dir.resolve("waypoints.json"), events);
        chunks = new ChunkLayer(events);
        chunky = new ChunkyBridge(events);
        search = new BiomeSearch(events);
        http = HttpServer.create(new InetSocketAddress(Config.webBind(), Config.webPort()), 64);
        http.setExecutor(executor);
        http.createContext("/", this::handle);
        http.start();
    }

    // ---- Lifecycle (server thread) -----------------------------------------------------------

    /** Starts the web map if needed. Throws if the port cannot be bound. */
    public static synchronized WebServer start(MinecraftServer server) throws IOException {
        if (instance != null && instance.server == server) return instance;
        stop();
        instance = new WebServer(server);
        LOGGER.info("FastDiffusion web map listening on {}", instance.baseUrl());
        return instance;
    }

    public static synchronized WebServer get() {
        return instance;
    }

    public static synchronized void stop() {
        if (instance == null) return;
        WebServer w = instance;
        instance = null;
        w.events.close();
        w.tiles.close();
        w.search.close();
        w.chunky.detach();
        w.http.stop(0);
        w.executor.shutdownNow();
        LOGGER.info("FastDiffusion web map stopped");
    }

    /** Server thread, every tick. */
    public static void tick() {
        WebServer w = instance;
        if (w == null) return;
        w.tick++;
        if (w.tick % 2 == 0 && w.events.hasClients()) w.players.tick();
        if (w.tick % 5 == 0) w.chunks.flush();
        if (w.tick % 100 == 0) w.chunky.attach();
        if (w.tick % 20 == 0 && w.events.hasClients())
            for (MapWorld world : MapWorld.all(w.server)) w.chunky.refresh(world.id());
    }

    /** Any thread: a chunk was generated and loaded for the first time. */
    public static void chunkGenerated(String dim, int cx, int cz) {
        WebServer w = instance;
        if (w != null) w.chunks.generated(dim, cx, cz);
    }

    public String baseUrl() {
        String configured = Config.webPublicUrl();
        if (!configured.isEmpty()) return configured.replaceAll("/+$", "");
        String host = Config.webBind();
        if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("::")) host = "localhost";
        if (host.contains(":")) host = "[" + host + "]";
        return "http://" + host + ":" + http.getAddress().getPort();
    }

    /** A one-time login link (valid for ten minutes). {@code player} is null for the console. */
    public String loginLink(UUID player, String name) {
        return baseUrl() + "/login?code=" + auth.issueCode(player, name);
    }

    public int revokeSessions() {
        return auth.revokeAll();
    }

    // ---- Routing -----------------------------------------------------------------------------

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getRawPath();
            if (!path.startsWith("/api/")) {
                files.serve(ex, path);
                return;
            }
            String[] segs = Arrays.stream(path.substring(5).split("/")).filter(s -> !s.isEmpty())
                    .map(s -> java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8)).toArray(String[]::new);
            String method = ex.getRequestMethod();
            if (segs.length == 1 && segs[0].equals("login") && method.equals("POST")) {
                login(ex);
                return;
            }
            Auth.Session session = auth.check(ex.getRequestHeaders().getFirst("Authorization"));
            if (session == null) {
                Http.error(ex, 401, "sign in with /fastdiffusion web");
                return;
            }
            Http.Request req = new Http.Request(ex, method, segs, Http.parseQuery(ex.getRequestURI().getRawQuery()), session);
            route(req);
        } catch (Http.Error e) {
            quietly(ex, e.status, e.getMessage());
        } catch (CancellationException e) {
            quietly(ex, 409, "cancelled");
        } catch (Throwable e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Http.Error he) quietly(ex, he.status, he.getMessage());
            else if (cause instanceof CancellationException) quietly(ex, 409, "cancelled");
            else if (cause instanceof TimeoutException) quietly(ex, 504, "timed out");
            else {
                LOGGER.warn("Web map request {} failed", ex.getRequestURI(), cause);
                quietly(ex, 500, cause.getMessage() == null ? cause.toString() : cause.getMessage());
            }
        }
    }

    private static void quietly(HttpExchange ex, int status, String message) {
        try {
            Http.error(ex, status, message);
        } catch (IOException | RuntimeException ignored) {
            ex.close();
        }
    }

    private void login(HttpExchange ex) throws IOException {
        Http.Request req = new Http.Request(ex, "POST", new String[0], java.util.Map.of(), null);
        String token = auth.login(Http.str(req.json(), "code"));
        if (token == null) {
            Http.error(ex, 401, "this login link is invalid or has expired; run /fastdiffusion web again");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("token", token);
        Http.json(ex, 200, o);
    }

    private void route(Http.Request r) throws Exception {
        HttpExchange ex = r.exchange();
        String head = r.seg(0);
        switch (head) {
            case "session" -> {
                if (r.method().equals("DELETE")) {
                    auth.logout(r.session());
                    Http.json(ex, 200, new JsonObject());
                } else {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", r.session().name());
                    o.addProperty("expires", r.session().expires());
                    Http.json(ex, 200, o);
                }
            }
            case "info" -> Http.json(ex, 200, info());
            case "events" -> events.serve(ex, c -> {
                c.send("hello", info());
                c.send("players", snapshotPlayers());
                c.send("waypoints", waypoints.list());
                for (MapWorld w : MapWorld.all(server)) c.send("chunky", chunky.status(w.id()));
                c.send("searches", search.list());
            }, () -> auth.check(ex.getRequestHeaders().getFirst("Authorization")) != null);
            case "tiles" -> tile(r);
            case "viewport" -> {
                JsonObject in = r.json();
                tiles.viewport(Http.str(in, "client"), Http.str(in, "dim"), Http.num(in, "x0"), Http.num(in, "z0"),
                        Http.num(in, "x1"), Http.num(in, "z1"));
                Http.send(ex, 204, new byte[0]);
            }
            case "chunks" -> {
                MapWorld w = MapWorld.require(server, r.seg(1));
                Http.json(ex, 200, chunks.saved(w, r.queryDouble("x0"), r.queryDouble("z0"), r.queryDouble("x1"),
                        r.queryDouble("z1")));
            }
            case "teleport" -> {
                requirePost(r);
                JsonObject in = r.json();
                UUID player;
                try {
                    player = UUID.fromString(Http.str(in, "player"));
                } catch (IllegalArgumentException e) {
                    throw Http.badRequest("bad player id");
                }
                Integer y = in.has("y") && !in.get("y").isJsonNull() ? (int) Math.floor(Http.num(in, "y")) : null;
                String dim = Http.str(in, "dim");
                MapWorld.require(server, dim);
                JsonObject done = players.teleport(player, dim, (int) Math.floor(Http.num(in, "x")),
                        (int) Math.floor(Http.num(in, "z")), y).get(60, TimeUnit.SECONDS);
                Http.json(ex, 200, done);
            }
            case "waypoints" -> {
                switch (r.method()) {
                    case "GET" -> Http.json(ex, 200, waypoints.list());
                    case "POST" -> Http.json(ex, 200, waypoints.upsert(r.json(), r.session().name()));
                    case "DELETE" -> {
                        if (!waypoints.remove(r.seg(1))) throw Http.notFound();
                        Http.json(ex, 200, new JsonObject());
                    }
                    default -> throw new Http.Error(405, "method not allowed");
                }
            }
            case "chunky" -> {
                if (r.path().length == 1) {
                    Http.json(ex, 200, chunky.info());
                    return;
                }
                String world = MapWorld.require(server, r.seg(1)).id();
                if (r.path().length == 2) {
                    Http.json(ex, 200, chunky.status(world));
                    return;
                }
                requirePost(r);
                JsonObject body = r.json();
                // Chunky schedules its own work; calls that touch the server run on its thread.
                JsonObject out = server.submit(() -> switch (r.seg(2)) {
                    case "start" -> chunky.start(world, body);
                    case "pause" -> chunky.pause(world);
                    case "continue" -> chunky.resume(world);
                    case "cancel" -> chunky.cancel(world);
                    default -> throw Http.notFound();
                }).get(30, TimeUnit.SECONDS);
                Http.json(ex, 200, out);
            }
            case "search" -> {
                if (r.method().equals("POST") && r.path().length == 1) {
                    JsonObject in = r.json();
                    MapWorld w = MapWorld.require(server, Http.str(in, "dim"));
                    int radius = (int) Math.clamp(Http.num(in, "radius", 8192), 256, 200_000);
                    JsonObject q = in.has("query") && in.get("query").isJsonObject() ? in.getAsJsonObject("query") : new JsonObject();
                    Http.json(ex, 200, search.start(w, Http.num(in, "x"), Http.num(in, "z"), radius, BiomeSearch.Query.parse(q)));
                } else if (r.method().equals("DELETE")) {
                    if (!search.cancel(r.seg(1))) throw Http.notFound();
                    Http.json(ex, 200, new JsonObject());
                } else if (r.path().length == 1) {
                    Http.json(ex, 200, search.list());
                } else {
                    JsonObject s = search.get(r.seg(1));
                    if (s == null) throw Http.notFound();
                    Http.json(ex, 200, s);
                }
            }
            case "stats" -> Http.json(ex, 200, stats());
            default -> throw Http.notFound();
        }
    }

    private static void requirePost(Http.Request r) {
        if (!r.method().equals("POST")) throw new Http.Error(405, "method not allowed");
    }

    private JsonArray snapshotPlayers() {
        return players.snapshot();
    }

    // /api/tiles/{dim}/{fingerprint}/{z}/{x}/{y}
    private void tile(Http.Request r) throws Exception {
        MapWorld w = MapWorld.require(server, r.seg(1));
        if (!r.seg(2).equals(fingerprint(w))) throw new Http.Error(410, "the world changed; reload the map");
        int z = r.segInt(3), x = r.segInt(4), y = r.segInt(5);
        MapTiles.Encoded t = tiles.get(w, z, x, y);
        var h = r.exchange().getResponseHeaders();
        h.set("Content-Type", "application/octet-stream");
        h.set("Content-Encoding", "gzip");
        h.set("X-Tile-Quality", Integer.toString(t.quality()));
        h.set("Access-Control-Expose-Headers", "X-Tile-Quality");
        // Final tiles never change for this world (the URL carries its fingerprint); others may be refined.
        h.set("Cache-Control", t.quality() >= MapTiles.finalQuality(w, z) ? "private, max-age=2592000, immutable" : "no-cache");
        Http.send(r.exchange(), 200, t.gzip());
    }

    /** Changes whenever the seed or scale behind a dimension changes, so browser caches never mix worlds. */
    private static String fingerprint(MapWorld w) {
        long seed = w.level().getSeed();
        long h = seed * 0x9E3779B97F4A7C15L ^ w.scale() * 0xC2B2AE3D27D4EB4FL ^ w.id().hashCode() ^ MapTiles.FORMAT;
        return Long.toHexString(h ^ (h >>> 29));
    }

    private JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("motd", server.getMotd());
        o.addProperty("version", SharedConstants.getCurrentVersion().getName());
        String device = TerrainService.deviceIfRunning();
        if (device != null) o.addProperty("device", device);
        JsonArray dims = new JsonArray();
        for (MapWorld w : MapWorld.all(server)) {
            JsonObject d = new JsonObject();
            d.addProperty("id", w.id());
            d.addProperty("fingerprint", fingerprint(w));
            d.addProperty("scale", w.scale());
            d.addProperty("metersPerBlock", 30.0 / w.scale());
            d.addProperty("minY", w.minY());
            d.addProperty("maxY", w.maxY());
            d.addProperty("seaLevel", w.seaLevel());
            d.addProperty("detailMax", w.detailMax());
            d.addProperty("overviewMax", w.overviewMax());
            d.addProperty("refineMax", w.overviewMax() + MapTiles.REFINE_LEVELS);
            d.addProperty("maxZoom", w.maxZoom());
            BlockPos spawn = w.level().getSharedSpawnPos();
            d.addProperty("spawnX", spawn.getX());
            d.addProperty("spawnZ", spawn.getZ());
            dims.add(d);
        }
        o.add("dimensions", dims);
        JsonArray biomes = new JsonArray();
        for (int i = 0; i < TerrainBiomes.count(); i++) {
            JsonObject b = new JsonObject();
            b.addProperty("id", i);
            b.addProperty("key", TerrainBiomes.key(i));
            b.addProperty("generated", TerrainBiomes.generated(i));
            biomes.add(b);
        }
        o.add("biomes", biomes);
        o.add("chunky", chunky.info());
        return o;
    }

    private JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("mspt", Math.round(server.getAverageTickTimeNanos() / 1e4) / 100.0);
        String device = TerrainService.deviceIfRunning();
        if (device == null) return o;
        double[] s = TerrainService.get().stats();
        o.addProperty("device", device);
        o.addProperty("regions", s[0]);
        o.addProperty("failed", s[1]);
        o.addProperty("gpuBatches", s[2]);
        o.addProperty("gpuSeconds", s[3] / 1000);
        o.addProperty("cpuSeconds", s[4] / 1000);
        o.addProperty("queued", s[5]);
        return o;
    }
}
