package moe.phrolova.world;

import moe.phrolova.FastDiffusion;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/** Biome ids produced by the native classifier (enum Biome in native/src/postprocess.h), in order. */
public final class TerrainBiomes {
    public static final int PLAINS = 0, SNOWY_PLAINS = 1, DESERT = 2, SWAMP = 3, FOREST = 4, TAIGA = 5, SNOWY_TAIGA = 6,
            SAVANNA = 7, WINDSWEPT_HILLS = 8, JUNGLE = 9, BADLANDS = 10, MEADOW = 11, GROVE = 12, SNOWY_SLOPES = 13,
            FROZEN_PEAKS = 14, STONY_PEAKS = 15, WARM_OCEAN = 16, OCEAN = 17, COLD_OCEAN = 18, FROZEN_OCEAN = 19,
            FOREST_SPARSE = 20, TAIGA_SPARSE = 21, SNOWY_TAIGA_SPARSE = 22, DEEP_OCEAN = 23, DEEP_COLD_OCEAN = 24,
            DEEP_FROZEN_OCEAN = 25, DEEP_LUKEWARM_OCEAN = 26, BEACH = 27, SNOWY_BEACH = 28, STONY_SHORE = 29;

    static final ResourceKey<Biome>[] KEYS = keys();

    private TerrainBiomes() {}

    @SuppressWarnings("unchecked")
    private static ResourceKey<Biome>[] keys() {
        return new ResourceKey[] {
                Biomes.PLAINS, Biomes.SNOWY_PLAINS, Biomes.DESERT, Biomes.SWAMP, Biomes.FOREST, Biomes.TAIGA,
                Biomes.SNOWY_TAIGA, Biomes.SAVANNA, Biomes.WINDSWEPT_HILLS, Biomes.JUNGLE, Biomes.BADLANDS, Biomes.MEADOW,
                own("dry_grove"), Biomes.SNOWY_SLOPES, Biomes.FROZEN_PEAKS, Biomes.STONY_PEAKS, Biomes.WARM_OCEAN,
                Biomes.OCEAN, Biomes.COLD_OCEAN, Biomes.FROZEN_OCEAN, own("forest_sparse"), own("taiga_sparse"),
                own("snowy_taiga_sparse"), Biomes.DEEP_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.DEEP_FROZEN_OCEAN,
                Biomes.DEEP_LUKEWARM_OCEAN, Biomes.BEACH, Biomes.SNOWY_BEACH, Biomes.STONY_SHORE,
        };
    }

    private static ResourceKey<Biome> own(String name) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(FastDiffusion.MODID, name));
    }

    public static int count() {
        return KEYS.length;
    }

    /** Biome id, e.g. {@code minecraft:plains}. */
    public static String key(int id) {
        return KEYS[id].location().toString();
    }

    /** False for ids the native classifier never emits (they exist for id stability). */
    public static boolean generated(int id) {
        return id != BADLANDS;
    }

    public static boolean isOcean(int id) {
        return id == WARM_OCEAN || id == OCEAN || id == COLD_OCEAN || id == FROZEN_OCEAN || id >= DEEP_OCEAN && id <= DEEP_LUKEWARM_OCEAN;
    }
}
