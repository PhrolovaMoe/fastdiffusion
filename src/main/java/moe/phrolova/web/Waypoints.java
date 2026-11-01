package moe.phrolova.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

/** Shared, named map locations, stored with the world and pushed to every browser when they change. */
final class Waypoints {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX = 2000;

    private final Path file;
    private final Events events;
    private final Map<String, JsonObject> points = new LinkedHashMap<>();

    Waypoints(Path file, Events events) {
        this.file = file;
        this.events = events;
        try {
            if (Files.isRegularFile(file))
                for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray())
                    points.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring unreadable waypoints file {}: {}", file, e.toString());
        }
    }

    synchronized JsonArray list() {
        JsonArray a = new JsonArray();
        points.values().forEach(p -> a.add(p.deepCopy()));
        return a;
    }

    synchronized JsonObject upsert(JsonObject in, String author) {
        String id = Http.str(in, "id", null);
        JsonObject old = id == null ? null : points.get(id);
        if (old == null && points.size() >= MAX) throw new Http.Error(409, "too many waypoints");
        String name = Http.str(in, "name").trim();
        if (name.isEmpty() || name.length() > 64) throw Http.badRequest("name must be 1-64 characters");
        String color = Http.str(in, "color", "#f2c14e");
        if (!color.matches("#[0-9a-fA-F]{6}")) throw Http.badRequest("bad color");
        JsonObject p = new JsonObject();
        p.addProperty("id", old != null ? id : UUID.randomUUID().toString());
        p.addProperty("name", name);
        p.addProperty("dim", Http.str(in, "dim"));
        p.addProperty("x", (int) Math.floor(Http.num(in, "x")));
        p.addProperty("z", (int) Math.floor(Http.num(in, "z")));
        if (in.has("y") && !in.get("y").isJsonNull()) p.addProperty("y", (int) Math.floor(Http.num(in, "y")));
        p.addProperty("color", color);
        p.addProperty("icon", Http.str(in, "icon", "pin").replaceAll("[^a-z-]", ""));
        String note = Http.str(in, "note", "");
        p.addProperty("note", note.length() > 500 ? note.substring(0, 500) : note);
        p.addProperty("author", old != null ? old.get("author").getAsString() : author);
        p.addProperty("created", old != null ? old.get("created").getAsLong() : System.currentTimeMillis());
        points.put(p.get("id").getAsString(), p);
        changed();
        return p;
    }

    synchronized boolean remove(String id) {
        if (points.remove(id) == null) return false;
        changed();
        return true;
    }

    private void changed() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Http.GSON.toJson(list()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not save waypoints to {}: {}", file, e.toString());
        }
        events.broadcast("waypoints", list());
    }
}
