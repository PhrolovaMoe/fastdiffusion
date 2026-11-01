package moe.phrolova.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * The single-page app, bundled in the jar under /web. Unknown paths fall back to the SPA shell so client-side
 * routes survive a reload. Hashed assets are cached forever; text is gzipped once and kept in memory.
 */
final class StaticFiles {
    private static final String ROOT = "/web";
    private static final String SHELL = "/_shell.html";
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"), Map.entry("webp", "image/webp"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("webmanifest", "application/manifest+json"));

    private record Asset(byte[] raw, byte[] gzip, String type) {}

    private static final Asset MISSING = new Asset(null, null, null);
    private final Map<String, Asset> cache = new ConcurrentHashMap<>();

    void serve(HttpExchange ex, String path) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            Http.error(ex, 405, "method not allowed");
            return;
        }
        if (path.contains("..") || path.contains("\\")) {
            Http.error(ex, 400, "bad path");
            return;
        }
        if (path.equals("/") || path.isEmpty()) path = SHELL;
        Asset asset = load(path);
        boolean hashed = path.startsWith("/assets/");
        if (asset == MISSING) {
            // Missing files under /assets are real 404s; anything else is a client-side route.
            if (hashed || path.lastIndexOf('.') > path.lastIndexOf('/')) {
                Http.error(ex, 404, "not found");
                return;
            }
            path = SHELL;
            asset = load(path);
            hashed = false;
            if (asset == MISSING) {
                Http.error(ex, 503, "the web map was not bundled into this build");
                return;
            }
        }
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", asset.type);
        headers.set("Cache-Control", hashed ? "public, max-age=31536000, immutable" : "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.add("Vary", "Accept-Encoding");
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (asset.gzip != null && accept != null && accept.contains("gzip")) {
            headers.set("Content-Encoding", "gzip");
            Http.send(ex, 200, asset.gzip);
        } else {
            Http.send(ex, 200, asset.raw);
        }
    }

    private Asset load(String path) {
        return cache.computeIfAbsent(path, p -> {
            try (InputStream in = StaticFiles.class.getResourceAsStream(ROOT + p)) {
                if (in == null) return MISSING;
                byte[] raw = in.readAllBytes();
                String ext = p.substring(p.lastIndexOf('.') + 1);
                String type = TYPES.getOrDefault(ext, "application/octet-stream");
                boolean text = type.startsWith("text/") || type.contains("json") || type.contains("svg");
                return new Asset(raw, text && raw.length > 512 ? gzip(raw) : null, type);
            } catch (IOException e) {
                return MISSING;
            }
        });
    }

    static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        return bos.toByteArray();
    }
}
