package moe.phrolova.compat.mixin;

import moe.phrolova.world.TerrainChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Diffusion biomes are identical at every height; inspect one section per neighbor. */
@Mixin(ChunkGenerator.class)
public abstract class BiomeDecorationMixin {
    @Redirect(method = "lambda$applyBiomeDecoration$6", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] fastdiffusion$horizontalBiomes(ChunkAccess chunk,
            WorldGenLevel level, Set<Holder<Biome>> biomes, ChunkPos pos) {
        var sections = chunk.getSections();
        return level.getLevel().getChunkSource().getGenerator() instanceof TerrainChunkGenerator
                ? new LevelChunkSection[]{sections[0]} : sections;
    }
}
