package moe.phrolova.world;

import moe.phrolova.engine.RegionData;
import java.util.List;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Fills chunks straight from the heightmap: terrain, surface layers, oceans and bedrock.
 * Sections entirely inside rock become single-value palettes, so tall worlds cost little more than short ones.
 */
public final class ColumnFiller {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState PODZOL = Blocks.PODZOL.defaultBlockState();
    private static final BlockState MUD = Blocks.MUD.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState RED_SAND = Blocks.RED_SAND.defaultBlockState();
    private static final BlockState TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();
    private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState POWDER_SNOW = Blocks.POWDER_SNOW.defaultBlockState();
    private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState CALCITE = Blocks.CALCITE.defaultBlockState();
    private static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    private static final List<BlockState> MATERIALS = List.of(AIR, STONE, DEEPSLATE, BEDROCK, WATER,
            GRASS, DIRT, COARSE_DIRT, PODZOL, MUD, SAND, SANDSTONE, RED_SAND, TERRACOTTA,
            GRAVEL, CLAY, SNOW_BLOCK, POWDER_SNOW, PACKED_ICE, CALCITE, ANDESITE);
    private static final int MATERIAL_BITS = 5;

    /** Rock below this many blocks under the lowest column top never needs per-block work. */
    private static final int MAX_SURFACE_DEPTH = 8;
    private static final float CLIFF_SLOPE = 1.4f;

    private final int minY, maxY, waterTop;
    private final SimplexNoise patches, depthNoise;
    private final long seed;

    ColumnFiller(long seed, int minY, int height, int seaLevel) {
        this.seed = seed;
        this.minY = minY;
        this.maxY = minY + height - 1;
        this.waterTop = seaLevel - 1;
        RandomSource rnd = RandomSource.create(seed ^ 0x5f3759dfL);
        this.patches = new SimplexNoise(rnd);
        this.depthNoise = new SimplexNoise(rnd);
    }

    /** Surface materials of one column: top block, then `depth` blocks of `under`, then optional `deep` layer. */
    private record Surface(BlockState top, BlockState under, int depth, BlockState deep, int deepDepth) {}

    private Surface surface(int biome, int h, float slope, int x, int z) {
        double p = patches.getValue(x / 24.0, z / 24.0);
        int depth = 3 + (int) ((depthNoise.getValue(x / 12.0, z / 12.0) + 1.0) * 1.5);
        if (h < waterTop) {
            int water = waterTop - h;
            if (biome == TerrainBiomes.WARM_OCEAN || biome == TerrainBiomes.DEEP_LUKEWARM_OCEAN) return new Surface(SAND, SAND, depth, SANDSTONE, 2);
            if (water <= 6) return new Surface(p > 0.4 ? CLAY : SAND, SAND, depth, SANDSTONE, 2);
            return new Surface(GRAVEL, GRAVEL, depth, null, 0);
        }
        boolean cliff = slope >= CLIFF_SLOPE;
        switch (biome) {
            case TerrainBiomes.DESERT:
                return cliff ? new Surface(SANDSTONE, SANDSTONE, depth, null, 0) : new Surface(SAND, SAND, depth, SANDSTONE, 4);
            case TerrainBiomes.BADLANDS:
                return new Surface(RED_SAND, TERRACOTTA, depth + 6, null, 0);
            case TerrainBiomes.BEACH:
                return new Surface(SAND, SAND, depth, SANDSTONE, 2);
            case TerrainBiomes.SNOWY_BEACH:
                return new Surface(SAND, SAND, depth, null, 0);
            case TerrainBiomes.STONY_SHORE:
                return new Surface(p > 0.3 ? GRAVEL : STONE, STONE, 1, null, 0);
            case TerrainBiomes.STONY_PEAKS:
                return new Surface(p > 0.25 ? CALCITE : STONE, p > 0.25 ? CALCITE : STONE, 2, null, 0);
            case TerrainBiomes.FROZEN_PEAKS:
                if (cliff) return new Surface(PACKED_ICE, STONE, 1, null, 0);
                return new Surface(p > 0.2 ? PACKED_ICE : SNOW_BLOCK, SNOW_BLOCK, depth, null, 0);
            case TerrainBiomes.SNOWY_SLOPES:
                if (cliff) return new Surface(STONE, STONE, 1, null, 0);
                return new Surface(p > 0.45 ? POWDER_SNOW : SNOW_BLOCK, SNOW_BLOCK, depth, null, 0);
            case TerrainBiomes.WINDSWEPT_HILLS:
                if (cliff || p > 0.3) return new Surface(p > 0.6 ? GRAVEL : STONE, STONE, 1, null, 0);
                return new Surface(GRASS, DIRT, depth, null, 0);
            default:
                break;
        }
        if (cliff) return new Surface(p > 0.5 ? ANDESITE : STONE, STONE, 1, null, 0);
        boolean steepish = slope >= CLIFF_SLOPE * 0.7f;
        return switch (biome) {
            case TerrainBiomes.SWAMP -> new Surface(GRASS, p > 0.2 ? MUD : DIRT, depth, null, 0);
            case TerrainBiomes.TAIGA, TerrainBiomes.TAIGA_SPARSE ->
                    new Surface(p > 0.45 ? PODZOL : p < -0.55 ? COARSE_DIRT : GRASS, DIRT, depth, null, 0);
            case TerrainBiomes.SAVANNA -> new Surface(p > 0.5 ? COARSE_DIRT : GRASS, DIRT, depth, null, 0);
            default -> new Surface(steepish && p > 0.2 ? COARSE_DIRT : GRASS, DIRT, steepish ? 1 : depth, null, 0);
        };
    }

    /** Positional coin flip for bedrock and the deepslate transition. */
    private boolean chance(int x, int y, int z, float p) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) < (long) (p * (1L << 24));
    }

    private BlockState rock(int x, int y, int z) {
        if (y <= minY + 4) {
            if (y == minY || chance(x, y, z, 1.0f - (y - minY) / 5.0f)) return BEDROCK;
        }
        if (y < 0) return DEEPSLATE;
        if (y < 8) return chance(x, y, z, 1.0f - y / 8.0f) ? DEEPSLATE : STONE;
        return STONE;
    }

    void fill(ChunkAccess chunk, RegionData region) {
        final int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        final int lx0 = x0 & 255, lz0 = z0 & 255;
        final int[] heights = new int[256];
        final Surface[] surfaces = new Surface[256];
        final int[] topMaterial = new int[256], underMaterial = new int[256], deepMaterial = new int[256];
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            int lx = lx0 + (i & 15), lz = lz0 + (i >> 4);
            int h = Math.min(region.height(lx, lz), maxY);
            heights[i] = h;
            surfaces[i] = surface(region.biome(lx, lz), h, region.slope(lx, lz), x0 + (i & 15), z0 + (i >> 4));
            topMaterial[i] = MATERIALS.indexOf(surfaces[i].top);
            underMaterial[i] = MATERIALS.indexOf(surfaces[i].under);
            deepMaterial[i] = surfaces[i].deep == null ? 0 : MATERIALS.indexOf(surfaces[i].deep);
            lowest = Math.min(lowest, h);
            highest = Math.max(highest, Math.max(h, waterTop));
        }
        final int solidTop = lowest - MAX_SURFACE_DEPTH - 6;  // deepest surface layer incl. `deep`

        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            int bottom = minY + si * 16, top = bottom + 15;
            if (bottom > highest) break;
            LevelChunkSection section = sections[si];
            if (top <= solidTop && bottom > minY + 4 && (bottom >= 8 || top < 0)) {
                // Uniform rock: swap in a single-value section instead of 4096 writes.
                BlockState fillState = top < 0 ? DEEPSLATE : STONE;
                sections[si] = new LevelChunkSection(
                        new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, fillState, PalettedContainer.Strategy.SECTION_STATES),
                        section.getBiomes());
                continue;
            }
            int[] states = new int[4096]; // zero is air; populate and pack once, without palette resizes.
            for (int i = 0; i < 256; i++) {
                int cx = i & 15, cz = i >> 4, wx = x0 + cx, wz = z0 + cz;
                int h = heights[i];
                Surface s = surfaces[i];
                int colTop = Math.min(top, Math.max(h, waterTop));
                for (int y = bottom; y <= colTop; y++) {
                    int state;
                    if (y > h) {
                        state = 4;
                    } else {
                        int d = h - y;
                        if (d == 0) state = topMaterial[i];
                        else if (d <= s.depth) state = underMaterial[i];
                        else if (s.deep != null && d <= s.depth + s.deepDepth) state = deepMaterial[i];
                        else {
                            BlockState rock = rock(wx, y, wz);
                            state = rock == STONE ? 1 : rock == DEEPSLATE ? 2 : 3;
                        }
                    }
                    states[((y & 15) << 8) | (cz << 4) | cx] = state;
                }
            }
            var palette = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES,
                    PalettedContainer.Strategy.SECTION_STATES.getConfiguration(Block.BLOCK_STATE_REGISTRY, MATERIAL_BITS),
                    new SimpleBitStorage(MATERIAL_BITS, 4096, states), MATERIALS);
            sections[si] = new LevelChunkSection(palette, section.getBiomes());
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int i = 0; i < 256; i++) {
            int cx = i & 15, cz = i >> 4, h = heights[i];
            // The state only feeds the heightmap predicates; every column top is solid or water.
            oceanFloor.update(cx, h, cz, STONE);
            worldSurface.update(cx, Math.max(h, waterTop), cz, STONE);
        }
        // Fast decoration can use these maps and normal ProtoChunk feature updates.
        // The regular feature task re-primes them after any cave/structure changes.
        for (Heightmap.Types type : net.minecraft.world.level.chunk.status.ChunkStatus.FINAL_HEIGHTMAPS) {
            Heightmap map = chunk.getOrCreateHeightmapUnprimed(type);
            for (int i = 0; i < 256; i++) {
                int h = heights[i], y = Math.max(h, waterTop);
                Surface s = surfaces[i];
                while (y >= minY) {
                    int d = h - y;
                    BlockState state = y > h ? WATER : d == 0 ? s.top : d <= s.depth ? s.under
                            : s.deep != null && d <= s.depth + s.deepDepth ? s.deep : STONE;
                    if (type.isOpaque().test(state)) {
                        map.update(i & 15, y, i >> 4, state);
                        break;
                    }
                    // Water is transparent only to the ocean-floor maps.
                    y = y > h ? h : y - 1;
                }
            }
        }
    }

    /** Block at a column position for structure checks (no surface detail). */
    BlockState baseState(int y, int h) {
        if (y <= h) return STONE;
        return y <= waterTop ? WATER : AIR;
    }

    int waterTop() {
        return waterTop;
    }

    /** Surface state shared with the distant-terrain renderer. */
    public BlockState surfaceState(int biome, int h, float slope, int x, int z) {
        return surface(biome, h, slope, x, z).top;
    }
}
