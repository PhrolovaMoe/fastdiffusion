package moe.phrolova.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import moe.phrolova.engine.TerrainWorld;
import moe.phrolova.world.TerrainChunkGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A diffusion dimension as the web map sees it, with its zoom levels. Level z draws 2^z blocks per pixel:
 * up to {@code detailMax} from full regions, up to {@code overviewMax} from the latent low-frequency band,
 * beyond that from the coarse map (later refined from children, see {@link MapTiles}).
 */
record MapWorld(String id, ServerLevel level, TerrainWorld terrain, int scale, int minY, int maxY, int seaLevel,
                Path saveDir, Path cacheDir, int detailMax, int overviewMax, int maxZoom) {

    static List<MapWorld> all(MinecraftServer server) {
        List<MapWorld> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            MapWorld w = of(server, level);
            if (w != null) out.add(w);
        }
        return out;
    }

    static MapWorld byId(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels())
            if (level.dimension().location().toString().equals(id)) return of(server, level);
        return null;
    }

    static MapWorld require(MinecraftServer server, String id) {
        MapWorld w = byId(server, id);
        if (w == null) throw new Http.Error(404, "no diffusion dimension " + id);
        return w;
    }

    private static MapWorld of(MinecraftServer server, ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof TerrainChunkGenerator gen) || gen.world() == null) return null;
        TerrainWorld t = gen.world();
        int s = t.scale;
        String id = level.dimension().location().toString();
        Path saveDir = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
        Path cache = server.getWorldPath(LevelResource.ROOT).resolve("fastdiffusion").resolve("map")
                .resolve(id.replace(':', '_').replace('/', '_')).resolve("v" + MapTiles.FORMAT + "-s" + s + "-" + Long.toHexString(level.getSeed()));
        int detailMax = t.regionLevelMax();
        int overviewMax = t.latentLevelMax();
        // Coarse tiles per map tile side grow as 2^z / (48 s); keep them to about two.
        int maxZoom = Math.max(overviewMax + 2, log2(96 * s));
        return new MapWorld(id, level, t, s, t.minY, t.minY + t.height - 1, t.seaLevel, saveDir, cache, detailMax,
                overviewMax, maxZoom);
    }

    private static int log2(int v) {
        return 31 - Integer.numberOfLeadingZeros(v);
    }
}
