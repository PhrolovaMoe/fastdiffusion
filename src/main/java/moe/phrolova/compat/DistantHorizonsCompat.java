package moe.phrolova.compat;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import moe.phrolova.engine.GridData;
import moe.phrolova.engine.RegionData;
import moe.phrolova.engine.TerrainWorld;
import moe.phrolova.world.TerrainBiomeSource;
import moe.phrolova.world.TerrainChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Optional DH API 4 integration. Distant terrain never needs full Minecraft chunks: every LOD detail level is
 * sampled straight from the terrain model, from full regions near the player and from map grids further out.
 */
public final class DistantHorizonsCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Coarsest DH detail level served directly (4096 blocks per column), as DH's own surface generator does. */
    private static final byte MAX_DETAIL = 12;
    /** A request is 64 columns across, a quarter of a 256-sample map tile, so latent grids stay as cheap two levels further out. */
    private static final int LATENT_EXTRA_LEVELS = 2;
    /** Finished regions kept for neighbouring LOD requests (about 200 KiB each). */
    private static final int REGION_CACHE = 64;

    private static final AtomicInteger GENERATORS = new AtomicInteger();

    private DistantHorizonsCompat() {}

    /** Whether any level currently has a FastDiffusion LOD generator; it keeps running while Chunky does. */
    public static boolean generating() {
        return GENERATORS.get() > 0;
    }

    public static void register() {
        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> event) {
                IDhApiLevelWrapper level = event.value.levelWrapper;
                if (!(level.getWrappedMcObject() instanceof ServerLevel server)) return;
                if (!(server.getChunkSource().getGenerator() instanceof TerrainChunkGenerator generator)) return;
                if (generator.world() == null) return;
                var result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(level, new Generator(level, generator));
                if (result.success) LOGGER.info("FastDiffusion direct LOD generation enabled for {}", server.dimension().location());
                else LOGGER.warn("Could not register FastDiffusion LOD generator: {}", result.message);
            }
        });
    }

    /** Terrain sampled at the requested columns. */
    private interface Samples {
        int height(int x, int z);
        int biome(int x, int z);
        float slope(int x, int z);
    }

    private static final class Generator implements IDhApiWorldGenerator {
        private final IDhApiLevelWrapper level;
        private final TerrainChunkGenerator generator;
        private final TerrainWorld world;
        private final Map<BlockState, IDhApiBlockStateWrapper> blocks = new ConcurrentHashMap<>();
        private final IDhApiBiomeWrapper[] biomes = new IDhApiBiomeWrapper[256];
        private final Map<Long, CompletableFuture<RegionData>> regions = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<RegionData>> eldest) {
                return size() > REGION_CACHE && eldest.getValue().isDone();
            }
        };
        private volatile boolean closed;

        Generator(IDhApiLevelWrapper level, TerrainChunkGenerator generator) {
            this.level = level;
            this.generator = generator;
            this.world = generator.world();
            GENERATORS.incrementAndGet();
        }

        @Override public EDhApiWorldGeneratorReturnType getReturnType() { return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES; }
        @Override public byte getLargestDataDetailLevel() { return MAX_DETAIL; }
        @Override public void preGeneratorTaskStart() {}

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            GENERATORS.decrementAndGet();
            synchronized (regions) { regions.clear(); }
        }

        private IDhApiBlockStateWrapper block(BlockState state) {
            return blocks.computeIfAbsent(state, s -> DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{s}, level));
        }

        // Racy lazy initialisation is fine: wrappers for the same biome are interchangeable.
        private IDhApiBiomeWrapper biome(int id) {
            IDhApiBiomeWrapper b = biomes[id];
            if (b == null) {
                b = DhApi.Delayed.wrapperFactory.getBiomeWrapper(
                        new Object[]{((TerrainBiomeSource) generator.getBiomeSource()).byId(id)}, level);
                biomes[id] = b;
            }
            return b;
        }

        @Override
        public CompletableFuture<Void> generateLod(int chunkX, int chunkZ, int lodX, int lodZ, byte detail,
                IDhApiFullDataSource data, EDhApiDistantGeneratorMode mode, ExecutorService executor,
                Consumer<IDhApiFullDataSource> consumer) {
            if (closed) return CompletableFuture.failedFuture(new CancellationException("LOD generator closed"));
            if (detail < 0 || detail > MAX_DETAIL) return CompletableFuture.failedFuture(new IllegalArgumentException("unsupported LOD detail " + detail));
            int x0 = chunkX << 4, z0 = chunkZ << 4, step = 1 << detail, width = data.getWidthInDataColumns();
            List<CompletableFuture<?>> parts = new ArrayList<>();
            CompletableFuture<Samples> samples = detail <= world.regionLevelMax()
                    ? fromRegions(x0, z0, step, width, parts)
                    : fromGrid(x0, z0, step, width, detail <= world.latentLevelMax() + LATENT_EXTRA_LEVELS, parts);
            CompletableFuture<Void> done = samples.thenAcceptAsync(s -> {
                if (closed) throw new CancellationException("LOD generator closed");
                fill(data, x0, z0, step, width, s);
                consumer.accept(data);
            }, executor);
            // DH cancels tasks it no longer needs; a task's map grid is dropped with it (cached regions are copies).
            done.whenComplete((v, error) -> {
                if (error != null) parts.forEach(p -> p.cancel(false));
            });
            return done;
        }

        /**
         * A region for LODs. Full-detail requests are a sixteenth of a region, so finished regions are kept in a small
         * cache of their own; map-style requests share regions cached for gameplay but never insert into that cache,
         * so distant LODs cannot evict the regions chunk generation is working through. Regions are not cancelled
         * with a task, since neighbouring tasks usually want them too.
         */
        private CompletableFuture<RegionData> region(int rx, int rz) {
            long key = ((long) rx << 32) | (rz & 0xffffffffL);
            synchronized (regions) {
                CompletableFuture<RegionData> f = regions.get(key);
                if (f == null || f.isCompletedExceptionally()) {
                    f = world.regionForMap(rx, rz, TerrainWorld.PRIORITY_LOD);
                    regions.put(key, f);
                }
                return f.copy();
            }
        }

        /** Full regions, sampled at column centers. */
        private CompletableFuture<Samples> fromRegions(int x0, int z0, int step, int width, List<CompletableFuture<?>> parts) {
            int span = width * step;
            int rx0 = x0 >> RegionData.SHIFT, rz0 = z0 >> RegionData.SHIFT;
            int n = Math.max(1, span >> RegionData.SHIFT);
            RegionData[] tiles = new RegionData[n * n];
            for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
                int slot = j * n + i;
                parts.add(region(rx0 + i, rz0 + j).thenAccept(r -> tiles[slot] = r));
            }
            int half = step / 2;
            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(v -> new Samples() {
                private RegionData at(int bx, int bz) {
                    return tiles[((bz >> RegionData.SHIFT) - rz0) * n + (bx >> RegionData.SHIFT) - rx0];
                }
                @Override public int height(int x, int z) {
                    int bx = x0 + x * step + half, bz = z0 + z * step + half;
                    return at(bx, bz).height(bx & 255, bz & 255);
                }
                @Override public int biome(int x, int z) {
                    int bx = x0 + x * step + half, bz = z0 + z * step + half;
                    return at(bx, bz).biome(bx & 255, bz & 255);
                }
                @Override public float slope(int x, int z) {
                    int bx = x0 + x * step + half, bz = z0 + z * step + half;
                    return at(bx, bz).slope(bx & 255, bz & 255);
                }
            });
        }

        /** A map grid one sample wider on each side, so slopes have neighbours at the edges. */
        private CompletableFuture<Samples> fromGrid(int x0, int z0, int step, int width, boolean latent, List<CompletableFuture<?>> parts) {
            CompletableFuture<GridData> grid = world.grid(x0 - step, z0 - step, step, width + 2, width + 2, latent, TerrainWorld.PRIORITY_LOD);
            parts.add(grid);
            return grid.thenApply(g -> new Samples() {
                @Override public int height(int x, int z) { return g.y(x + 1, z + 1); }
                @Override public int biome(int x, int z) { return g.biome(x + 1, z + 1); }
                @Override public float slope(int x, int z) {
                    float dx = g.y(x + 2, z + 1) - g.y(x, z + 1), dz = g.y(x + 1, z + 2) - g.y(x + 1, z);
                    return (float) Math.sqrt(dx * dx + dz * dz) / (2 * step);
                }
            });
        }

        /** Writes block-sized, top-down datapoints with Y relative to the level's bottom, as DH requires. */
        private void fill(IDhApiFullDataSource data, int x0, int z0, int step, int width, Samples s) {
            int minY = world.minY, top = world.height, sea = world.seaLevel - minY;
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState water = Blocks.WATER.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();
            List<DhApiTerrainDataPoint> column = new ArrayList<>(4);
            int half = step / 2;
            for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
                int y = Math.clamp(s.height(x, z), minY + 1, minY + top - 1);
                int h = y - minY, biomeId = s.biome(x, z);
                IDhApiBiomeWrapper biome = biome(biomeId);
                BlockState surface = generator.columnFiller().surfaceState(biomeId, y, s.slope(x, z),
                        x0 + x * step + half, z0 + z * step + half);
                column.clear();
                add(column, Math.max(h + 1, sea), top, air, biome, 15);
                add(column, h + 1, sea, water, biome, 15);
                add(column, h, h + 1, surface, biome, 15);
                add(column, 0, h, stone, biome, 0);
                data.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.LIGHT, column);
            }
        }

        private void add(List<DhApiTerrainDataPoint> column, int bottom, int top, BlockState state,
                IDhApiBiomeWrapper biome, int sky) {
            if (top > bottom) column.add(DhApiTerrainDataPoint.create((byte) 0, 0, sky, bottom, top, block(state), biome));
        }
    }
}
