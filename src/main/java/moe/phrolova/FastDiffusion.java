package moe.phrolova;

import com.mojang.serialization.MapCodec;
import moe.phrolova.world.TerrainBiomeSource;
import moe.phrolova.world.TerrainChunkGenerator;
import moe.phrolova.web.WebHooks;
import moe.phrolova.world.WorldRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import moe.phrolova.compat.DistantHorizonsCompat;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(FastDiffusion.MODID)
public final class FastDiffusion {
    public static final String MODID = "fastdiffusion";

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MODID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MODID);

    static {
        CHUNK_GENERATORS.register("terrain", () -> TerrainChunkGenerator.CODEC);
        BIOME_SOURCES.register("terrain", () -> TerrainBiomeSource.CODEC);
    }

    public FastDiffusion(IEventBus modBus, ModContainer container) {
        CHUNK_GENERATORS.register(modBus);
        BIOME_SOURCES.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(WorldRegistry.class);
        NeoForge.EVENT_BUS.register(WebHooks.class);
        if (ModList.get().isLoaded("distanthorizons")) DistantHorizonsCompat.register();
    }
}
