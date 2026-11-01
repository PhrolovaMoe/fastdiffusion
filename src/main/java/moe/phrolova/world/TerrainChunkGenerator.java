package moe.phrolova.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import moe.phrolova.Config;
import moe.phrolova.engine.RegionData;
import moe.phrolova.engine.TerrainService;
import moe.phrolova.engine.TerrainWorld;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Terrain from the native diffusion pipeline. Extends the noise generator so carvers, structures,
 * features and mob spawning behave like vanilla; terrain shape, biomes and surfaces come from regions.
 */
public final class TerrainChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<TerrainChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings),
            Codec.intRange(1, 6).fieldOf("scale").forGetter(g -> g.scale),
            Codec.BOOL.optionalFieldOf("generate_ores", true).forGetter(g -> g.generateOres),
            Codec.BOOL.optionalFieldOf("generate_underground_patches", true).forGetter(g -> g.generateUndergroundPatches)
    ).apply(i, i.stable(TerrainChunkGenerator::new)));

    public static final int SEA_LEVEL = 63;

    private final int scale;
    private final boolean generateOres;
    private final boolean generateUndergroundPatches;
    private volatile TerrainWorld world;
    private volatile ColumnFiller filler;
    private boolean surfaceOnly;

    public TerrainChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, int scale) {
        this(biomeSource, settings, scale, true);
    }

    public TerrainChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, int scale, boolean generateOres) {
        this(biomeSource, settings, scale, generateOres, true);
    }

    public TerrainChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, int scale,
                                 boolean generateOres, boolean generateUndergroundPatches) {
        super(biomeSource, settings);
        this.scale = scale;
        this.generateOres = generateOres;
        this.generateUndergroundPatches = generateUndergroundPatches;
    }

    public int scale() {
        return scale;
    }

    public boolean generateOres() {
        return generateOres && !surfaceOnly;
    }

    public boolean generateUndergroundPatches() {
        return generateUndergroundPatches && !surfaceOnly;
    }

    public boolean surfaceOnly() { return surfaceOnly; }

    public ColumnFiller columnFiller() { return filler; }

    /** Native world backing this generator; null before the level is created. */
    public TerrainWorld world() {
        return world;
    }

    // ChunkMap calls this once with the level seed while the level is being constructed.
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
        bind(seed);
        return ChunkGeneratorStructureState.createForNormal(randomState, seed,
                ((TerrainBiomeSource) biomeSource).forStructurePlacement(), structureSets);
    }

    private synchronized void bind(long seed) {
        if (world != null) return;
        int minY = getMinY(), height = getGenDepth();
        surfaceOnly = Config.surfaceOnly();
        world = TerrainService.get().openWorld(seed, scale, minY, height, SEA_LEVEL);
        filler = new ColumnFiller(seed, minY, height, SEA_LEVEL);
        if (biomeSource instanceof TerrainBiomeSource source) source.bind(world);
        WorldRegistry.register(this);
    }

    /** Releases the native world; called when the server stops. */
    public synchronized void release() {
        if (world != null) {
            world.close();
            world = null;
        }
    }

    private TerrainWorld requireWorld() {
        TerrainWorld w = world;
        if (w == null) throw new IllegalStateException("FastDiffusion generator used before its level was created");
        return w;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        TerrainBiomeSource source = (TerrainBiomeSource) biomeSource;
        return requireWorld().regionAtBlock(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), TerrainWorld.PRIORITY_CHUNK)
                .thenApplyAsync(region -> {
                    chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> source.byId(region.biome((qx << 2) & 255, (qz << 2) & 255)),
                            randomState.sampler());
                    return chunk;
                }, Util.backgroundExecutor());
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        return requireWorld().regionAtBlock(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), TerrainWorld.PRIORITY_CHUNK)
                .thenApplyAsync(region -> {
                    filler.fill(chunk, region);
                    return chunk;
                }, Util.backgroundExecutor());
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
        // Surfaces are placed by ColumnFiller during fillFromNoise.
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes,
                             StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {
        if (!surfaceOnly) super.applyCarvers(level, seed, random, biomes, structures, chunk, step);
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        if (structures.shouldGenerateStructures()) super.createReferences(level, structures, chunk);
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int h = requireWorld().surfaceY(x, z);
        int waterTop = filler.waterTop();
        if (h < waterTop && type.isOpaque().test(filler.baseState(waterTop, h))) return waterTop + 1;
        return h + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int h = requireWorld().surfaceY(x, z);
        int minY = Math.max(getMinY(), height.getMinBuildHeight());
        int top = Math.min(getMinY() + getGenDepth(), height.getMaxBuildHeight());
        BlockState[] column = new BlockState[Math.max(0, top - minY)];
        for (int y = minY; y < top; y++) column[y - minY] = filler.baseState(y, h);
        return new NoiseColumn(minY, column);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        TerrainWorld w = world;
        if (w == null) return;
        RegionData r = w.regionAtBlock(pos.getX(), pos.getZ(), TerrainWorld.PRIORITY_BLOCKING).getNow(null);
        String column = r == null ? "pending" : "surface " + r.height(pos.getX() & 255, pos.getZ() & 255)
                + ", slope " + String.format("%.2f", r.slope(pos.getX() & 255, pos.getZ() & 255));
        info.add("FastDiffusion 1:" + scale + " (" + (30.0f / scale) + " m/block) region " + (pos.getX() >> 8) + "," + (pos.getZ() >> 8)
                + " " + column);
    }
}
