package moe.phrolova.world;

import com.mojang.serialization.MapCodec;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.function.Predicate;
import moe.phrolova.engine.TerrainWorld;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.util.RandomSource;

/** Biomes classified natively from the generated terrain and its climate. */
public final class TerrainBiomeSource extends BiomeSource {
    public static final MapCodec<TerrainBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(RegistryOps.retrieveGetter(Registries.BIOME)).apply(i, i.stable(TerrainBiomeSource::new)));

    private final Holder<Biome>[] biomes;
    private volatile TerrainWorld world;

    @SuppressWarnings("unchecked")
    public TerrainBiomeSource(HolderGetter<Biome> lookup) {
        this.biomes = Arrays.stream(TerrainBiomes.KEYS).map(lookup::getOrThrow).toArray(Holder[]::new);
    }

    void bind(TerrainWorld world) {
        this.world = world;
    }

    public Holder<Biome> byId(int id) {
        return id < biomes.length ? biomes[id] : biomes[TerrainBiomes.PLAINS];
    }

    /**
     * Strongholds retain their seeded ring positions. Vanilla's optional biome relocation
     * would otherwise generate thousands of distant regions before a player can enter.
     * This view is used only by structure placement; actual structure checks use this source.
     */
    BiomeSource forStructurePlacement() {
        return new BiomeSource() {
            @Override protected MapCodec<? extends BiomeSource> codec() { return TerrainBiomeSource.CODEC; }
            @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return TerrainBiomeSource.this.collectPossibleBiomes(); }
            @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
                return TerrainBiomeSource.this.getNoiseBiome(x, y, z, sampler);
            }
            @Override public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius,
                    Predicate<Holder<Biome>> predicate, RandomSource random, Climate.Sampler sampler) {
                return null;
            }
        };
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Arrays.stream(biomes);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        TerrainWorld w = world;
        if (w == null) return biomes[TerrainBiomes.PLAINS];
        return byId(w.biomeId(QuartPos.toBlock(x), QuartPos.toBlock(z)));
    }
}
