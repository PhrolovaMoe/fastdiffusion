package moe.phrolova.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import moe.phrolova.Config;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Operator-only access. {@code /fastdiffusion web} issues a short-lived one-time login code; the browser trades it
 * for a bearer token. Only token hashes are stored. Sessions issued by a player lapse as soon as that player is no
 * longer an operator.
 */
final class Auth {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CODE_TTL_MS = 10 * 60 * 1000L;
    private static final long PERMISSION_RECHECK_MS = 10_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** {@code player} is null for sessions issued from the server console. */
    record Session(String hash, UUID player, String name, long created, long expires) {}

    private record Code(UUID player, String name, long expires) {}

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Code> codes = new HashMap<>();
    private final Map<UUID, Long> verifiedAt = new HashMap<>();

    Auth(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
        load();
    }

    synchronized String issueCode(UUID player, String name) {
        long now = System.currentTimeMillis();
        codes.values().removeIf(c -> c.expires < now);
        String code = token(15);
        codes.put(code, new Code(player, name, now + CODE_TTL_MS));
        return code;
    }

    /** Trades a login code for a new session; returns the bearer token, or null if the code is invalid. */
    synchronized String login(String code) {
        Code c = codes.remove(code);
        long now = System.currentTimeMillis();
        if (c == null || c.expires < now) return null;
        String token = token(32);
        Session s = new Session(hash(token), c.player, c.name, now, now + Config.webSessionDays() * 86_400_000L);
        sessions.put(s.hash, s);
        save();
        return token;
    }

    /** The session for an Authorization header value, or null. */
    Session check(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        Session s;
        synchronized (this) {
            s = sessions.get(hash(header.substring(7).trim()));
            if (s == null) return null;
            if (s.expires < System.currentTimeMillis()) {
                sessions.remove(s.hash);
                save();
                return null;
            }
        }
        return s.player == null || isOperator(s.player) ? s : null;
    }

    synchronized void logout(Session s) {
        if (sessions.remove(s.hash) != null) save();
    }

    synchronized int revokeAll() {
        int n = sessions.size();
        sessions.clear();
        codes.clear();
        save();
        return n;
    }

    private boolean isOperator(UUID player) {
        long now = System.currentTimeMillis();
        synchronized (verifiedAt) {
            Long at = verifiedAt.get(player);
            if (at != null && now - at < PERMISSION_RECHECK_MS) return true;
        }
        GameProfile profile = server.getProfileCache() == null ? null
                : server.getProfileCache().get(player).orElse(null);
        boolean ok = profile != null && server.getProfilePermissions(profile) >= 2;
        synchronized (verifiedAt) {
            if (ok) verifiedAt.put(player, now);
            else verifiedAt.remove(player);
        }
        return ok;
    }

    private static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            long now = System.currentTimeMillis();
            for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                String player = Http.str(o, "player", null);
                Session s = new Session(o.get("hash").getAsString(), player == null ? null : UUID.fromString(player),
                        o.get("name").getAsString(), o.get("created").getAsLong(), o.get("expires").getAsLong());
                if (s.expires > now) sessions.put(s.hash, s);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring unreadable web sessions file {}: {}", file, e.toString());
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        for (Session s : sessions.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("hash", s.hash);
            if (s.player != null) o.addProperty("player", s.player.toString());
            o.addProperty("name", s.name);
            o.addProperty("created", s.created);
            o.addProperty("expires", s.expires);
            arr.add(o);
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Http.GSON.toJson(arr));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not save web sessions to {}: {}", file, e.toString());
        }
    }
}
