package moe.phrolova.world;

import com.mojang.logging.LogUtils;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import moe.phrolova.Config;
import moe.phrolova.engine.RegionData;
import moe.phrolova.engine.TerrainWorld;
import moe.phrolova.engine.TerrainService;
import moe.phrolova.web.WebHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Server lifecycle glue: spawn placement, prefetching around players, native cleanup. */
public final class WorldRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<TerrainChunkGenerator> ACTIVE = ConcurrentHashMap.newKeySet();
    private static int tick;
    private static volatile boolean serverStarted;

    private WorldRegistry() {}

    public static boolean serverStarted() { return serverStarted; }

    @SubscribeEvent
    public static void onServerStarting(ServerAboutToStartEvent event) { serverStarted = false; }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) { serverStarted = true; }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fastdiffusion").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(context -> {
                    if (ACTIVE.isEmpty()) {
                        context.getSource().sendSuccess(() -> Component.literal("No FastDiffusion world is active."), false);
                        return 0;
                    }
                    TerrainService service = TerrainService.get();
                    double[] stats = service.stats();
                    context.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                            "%s: %.0f regions, %.0f failed, %.0f queued; GPU %.1f s / %.0f batches, CPU %.1f s",
                            service.device(), stats[0], stats[1], stats[5], stats[3] / 1000, stats[2], stats[4] / 1000)), false);
                    return 1;
                }))
                .then(WebHooks.command()));
    }

    static void register(TerrainChunkGenerator generator) {
        ACTIVE.add(generator);
    }

    @SubscribeEvent
    public static void onCreateSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getChunkSource().getGenerator() instanceof TerrainChunkGenerator gen) || gen.world() == null) return;
        TerrainWorld world = gen.world();
        long[] land = world.findLand();
        BlockPos spawn = nearestLand(world, (int) land[0], (int) land[1]);
        event.getSettings().setSpawn(spawn, 0.0f);
        event.setCanceled(true);
        LOGGER.info("FastDiffusion spawn at {}", spawn.toShortString());
    }

    /** Land column closest to (x, z) within its region, falling back to (x, z) itself. */
    private static BlockPos nearestLand(TerrainWorld world, int x, int z) {
        RegionData r = world.regionNow(x, z);
        int bx = r.rx << RegionData.SHIFT, bz = r.rz << RegionData.SHIFT;
        int best = -1, bestX = x, bestZ = z;
        for (int lz = 0; lz < RegionData.SIZE; lz++)
            for (int lx = 0; lx < RegionData.SIZE; lx++) {
                if (r.height(lx, lz) < TerrainChunkGenerator.SEA_LEVEL || r.slope(lx, lz) > 0.8f) continue;
                int dx = bx + lx - x, dz = bz + lz - z, d = dx * dx + dz * dz;
                if (best < 0 || d < best) {
                    best = d;
                    bestX = bx + lx;
                    bestZ = bz + lz;
                }
            }
        return new BlockPos(bestX, world.surfaceY(bestX, bestZ) + 1, bestZ);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tick % 20 != 0 || ACTIVE.isEmpty()) return;
        int radius = Config.prefetchRadius();
        if (radius <= 0) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof TerrainChunkGenerator gen) || gen.world() == null) continue;
            for (ServerPlayer player : level.players()) gen.world().prefetch(player.getBlockX(), player.getBlockZ(), radius);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        serverStarted = false;
        for (TerrainChunkGenerator gen : ACTIVE) gen.release();
        ACTIVE.clear();
        TerrainService.shutdown();
    }
}
