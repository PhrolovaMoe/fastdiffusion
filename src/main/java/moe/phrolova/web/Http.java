package moe.phrolova.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small request/response helpers over the JDK HTTP server. */
final class Http {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_BODY = 1 << 20;

    private Http() {}

    /** Thrown by handlers; becomes a JSON error response. */
    static final class Error extends RuntimeException {
        final int status;

        Error(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    static Error badRequest(String message) {
        return new Error(400, message);
    }

    static Error notFound() {
        return new Error(404, "not found");
    }

    /** A routed API request. {@code path} holds the segments after /api/. */
    record Request(HttpExchange exchange, String method, String[] path, Map<String, String> query, Auth.Session session) {
        String seg(int i) {
            if (i >= path.length) throw notFound();
            return path[i];
        }

        int segInt(int i) {
            try {
                return Integer.parseInt(seg(i));
            } catch (NumberFormatException e) {
                throw notFound();
            }
        }

        JsonObject json() {
            try (InputStream in = exchange.getRequestBody()) {
                byte[] body = in.readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) throw new Error(413, "request body too large");
                if (body.length == 0) return new JsonObject();
                JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                if (!e.isJsonObject()) throw badRequest("expected a JSON object");
                return e.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                if (e instanceof Error err) throw err;
                throw badRequest("malformed JSON");
            }
        }

        double queryDouble(String name) {
            String v = query.get(name);
            if (v == null) throw badRequest("missing query parameter " + name);
            try {
                double d = Double.parseDouble(v);
                if (!Double.isFinite(d)) throw new NumberFormatException();
                return d;
            } catch (NumberFormatException e) {
                throw badRequest("bad query parameter " + name);
            }
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq), v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, bytes);
    }

    static void error(HttpExchange ex, int status, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        json(ex, status, o);
    }

    static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) throw badRequest("missing " + key);
        return e.getAsString();
    }

    static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? fallback : e.getAsString();
    }

    static double num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            double d = e.getAsDouble();
            if (!Double.isFinite(d)) throw new NumberFormatException();
            return d;
        } catch (RuntimeException ex) {
            throw badRequest("missing or bad number " + key);
        }
    }

    static double num(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : num(o, key);
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        try {
            return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
        } catch (RuntimeException ex) {
            throw badRequest("bad boolean " + key);
        }
    }
}
