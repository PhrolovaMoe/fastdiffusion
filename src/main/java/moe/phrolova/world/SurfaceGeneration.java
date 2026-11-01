package moe.phrolova.world;

import moe.phrolova.compat.mixin.ChunkMapAccessor;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.Util;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Drop structure-only dependency rings when neither structures nor cave carvers can use them. */
public final class SurfaceGeneration {
    private static final ChunkPyramid PYRAMID = build();
    private static final Map<WorldGenContext, DecorationQueue> QUEUES = new java.util.WeakHashMap<>();

    private SurfaceGeneration() {}

    public static ChunkPyramid pyramid(GeneratingChunkMap map) {
        if (map instanceof ChunkMapAccessor accessor) {
            var context = accessor.fastdiffusion$context();
            if (context.generator() instanceof TerrainChunkGenerator terrain && terrain.surfaceOnly()
                    && !context.level().getServer().getWorldData().worldGenOptions().generateStructures()) return PYRAMID;
        }
        return ChunkPyramid.GENERATION_PYRAMID;
    }

    private static ChunkPyramid build() {
        var builder = new ChunkPyramid.Builder();
        for (var step : ChunkPyramid.GENERATION_PYRAMID.steps()) {
            builder.step(step.targetStatus(), b -> {
                b.setTask(step.task()).blockStateWriteRadius(step.blockStateWriteRadius());
                if (step.targetStatus() == ChunkStatus.FEATURES) b.setTask(SurfaceGeneration::decorate);
                var requirements = step.directDependencies();
                for (int radius = 0; radius < requirements.size(); radius++) {
                    var status = requirements.get(radius);
                    if (radius > 0 && status == ChunkStatus.STRUCTURE_STARTS) continue;
                    b.addRequirement(status, radius);
                }
                // The vanilla carver task still checks adjacent chunks for blending masks.
                if (step.targetStatus() == ChunkStatus.CARVERS) b.addRequirement(ChunkStatus.EMPTY, 1);
                return b;
            });
        }
        return builder.build();
    }

    private static CompletableFuture<ChunkAccess> decorate(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        DecorationQueue queue;
        synchronized (QUEUES) { queue = QUEUES.computeIfAbsent(context, k -> new DecorationQueue(Util.backgroundExecutor())); }
        return queue.submit(chunk.getPos().x, chunk.getPos().z, () -> {
            // ColumnFiller already initialized final heightmaps. Neighbor decorations keep
            // them current; a top-down rescan of every column is unnecessary here.
            var region = new WorldGenRegion(context.level(), cache, step, chunk);
            context.generator().applyBiomeDecoration(region, chunk, context.level().structureManager().forWorldGenRegion(region));
            Blender.generateBorderTicks(region, chunk);
            return chunk;
        });
    }

}
