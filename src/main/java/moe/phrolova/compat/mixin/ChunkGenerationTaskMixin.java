package moe.phrolova.compat.mixin;

import moe.phrolova.world.SurfaceGeneration;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkGenerationTask.class)
public abstract class ChunkGenerationTaskMixin {
    @Shadow @Final private GeneratingChunkMap chunkMap;

    @Redirect(method = "create", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/chunk/status/ChunkPyramid;GENERATION_PYRAMID:Lnet/minecraft/world/level/chunk/status/ChunkPyramid;"))
    private static ChunkPyramid fastdiffusion$createSurfaceTask(GeneratingChunkMap map, ChunkStatus status, ChunkPos pos) {
        return SurfaceGeneration.pyramid(map);
    }

    @Redirect(method = {"getRadiusForLayer", "scheduleChunkInLayer"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/chunk/status/ChunkPyramid;GENERATION_PYRAMID:Lnet/minecraft/world/level/chunk/status/ChunkPyramid;"))
    private ChunkPyramid fastdiffusion$surfaceDependencies() {
        return SurfaceGeneration.pyramid(chunkMap);
    }
}
