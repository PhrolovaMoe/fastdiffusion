package moe.phrolova.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Live player state for the map. The server thread samples every player each other tick and broadcasts only what
 * changed, so markers move at 10 Hz without flooding idle connections.
 */
final class Players {
    private static final double MOVE_EPS = 0.05;
    private static final float TURN_EPS = 2.0f;

    private record State(String uuid, String name, String dim, double x, double y, double z, float yaw, String mode,
                         float health, int food, int level, boolean sleeping) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid);
            o.addProperty("name", name);
            o.addProperty("dim", dim);
            o.addProperty("x", Math.round(x * 100) / 100.0);
            o.addProperty("y", Math.round(y * 100) / 100.0);
            o.addProperty("z", Math.round(z * 100) / 100.0);
            o.addProperty("yaw", Math.round(yaw * 10) / 10.0);
            o.addProperty("mode", mode);
            o.addProperty("health", health);
            o.addProperty("food", food);
            o.addProperty("level", level);
            o.addProperty("sleeping", sleeping);
            return o;
        }

        boolean differs(State o) {
            return !dim.equals(o.dim) || !mode.equals(o.mode) || Math.abs(x - o.x) > MOVE_EPS || Math.abs(y - o.y) > MOVE_EPS
                    || Math.abs(z - o.z) > MOVE_EPS || Math.abs(yaw - o.yaw) > TURN_EPS || health != o.health || food != o.food
                    || level != o.level || sleeping != o.sleeping;
        }
    }

    private final MinecraftServer server;
    private final Events events;
    private final Map<UUID, State> last = new HashMap<>();
    private volatile JsonArray snapshot = new JsonArray();

    Players(MinecraftServer server, Events events) {
        this.server = server;
        this.events = events;
    }

    /** Server thread. */
    void tick() {
        Map<UUID, State> now = new HashMap<>();
        JsonArray changed = new JsonArray(), all = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            State s = new State(p.getStringUUID(), p.getGameProfile().getName(), p.level().dimension().location().toString(),
                    p.getX(), p.getY(), p.getZ(), p.getYRot(), p.gameMode.getGameModeForPlayer().getName(), p.getHealth(),
                    p.getFoodData().getFoodLevel(), p.experienceLevel, p.isSleeping());
            now.put(p.getUUID(), s);
            State prev = last.get(p.getUUID());
            if (prev == null || s.differs(prev)) {
                changed.add(s.json());
                last.put(p.getUUID(), s);
            }
            all.add((prev == null || s.differs(prev) ? s : prev).json());
        }
        JsonArray removed = new JsonArray();
        last.keySet().removeIf(id -> {
            if (now.containsKey(id)) return false;
            removed.add(id.toString());
            return true;
        });
        snapshot = all;
        if (changed.isEmpty() && removed.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.add("changed", changed);
        o.add("removed", removed);
        events.broadcast("players", o);
    }

    JsonArray snapshot() {
        return snapshot;
    }

    /**
     * Teleports a player onto the surface (or to {@code y} when given), generating the target chunk first so the
     * player never lands inside unfinished terrain. Completes on the server thread.
     */
    CompletableFuture<JsonObject> teleport(UUID uuid, String dim, int x, int z, Integer y) {
        if (Math.abs((long) x) > 29_999_984 || Math.abs((long) z) > 29_999_984)
            throw Http.badRequest("destination is outside the world border");
        return server.submit(() -> beginTeleport(uuid, dim, x, z, y)).thenCompose(f -> f);
    }

    /** Called on the server thread; all player/dimension lookups and chunk tickets stay on it. */
    private CompletableFuture<JsonObject> beginTeleport(UUID uuid, String dim, int x, int z, Integer y) {
        ResourceLocation loc = ResourceLocation.tryParse(dim);
        if (loc == null) throw Http.badRequest("bad dimension");
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
        if (level == null) throw new Http.Error(404, "no dimension " + dim);
        if (server.getPlayerList().getPlayer(uuid) == null) throw new Http.Error(404, "player is not online");
        // Off the server thread getChunkFuture hops to it and completes once the chunk is fully generated.
        return level.getChunkSource().getChunkFuture(x >> 4, z >> 4, ChunkStatus.FULL, true).thenApplyAsync(result -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null) throw new Http.Error(404, "player went offline");
            if (!result.isSuccess()) throw new Http.Error(500, "the target chunk could not be generated");
            int ty = y != null ? y : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            ty = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() + 64, ty));
            p.teleportTo(level, x + 0.5, ty, z + 0.5, p.getYRot(), p.getXRot());
            p.resetFallDistance();
            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid.toString());
            o.addProperty("dim", dim);
            o.addProperty("x", x + 0.5);
            o.addProperty("y", ty);
            o.addProperty("z", z + 0.5);
            return o;
        }, server);
    }
}
