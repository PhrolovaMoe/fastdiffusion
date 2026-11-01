package moe.phrolova.compat.mixin;

import moe.phrolova.world.TerrainChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Seed the exact direct-sky gradient in unobstructed ocean water; vanilla handles its borders. */
@Mixin(SkyLightEngine.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class SurfaceSkyLightMixin extends LightEngine {
    protected SurfaceSkyLightMixin(LightChunkGetter source, SkyLightSectionStorage storage) { super(source, storage); }

    @Inject(method = "propagateLightSources", at = @At("HEAD"))
    private void fastdiffusion$waterGradient(ChunkPos pos, CallbackInfo callback) {
        if (!(chunkSource.getLevel() instanceof ServerLevel level)
                || !(level.getChunkSource().getGenerator() instanceof TerrainChunkGenerator terrain) || !terrain.surfaceOnly()) return;
        var chunk = chunkSource.getChunkForLighting(pos.x, pos.z);
        if (chunk == null || chunk.getSkyLightSources() == null) return;
        int top = TerrainChunkGenerator.SEA_LEVEL - 1;
        var block = new BlockPos.MutableBlockPos();
        // Validate the entire band before touching light data. Coral, ice, trees, builds,
        // shorelines and non-water columns retain the complete vanilla lighting path.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (chunk.getSkyLightSources().getLowestSourceY(x, z) != top + 1) return;
            for (int y = top; y > top - 14; y--) {
                var state = chunk.getBlockState(block.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z));
                if (!state.is(Blocks.WATER) && !state.is(Blocks.SEAGRASS) && !state.is(Blocks.TALL_SEAGRASS)
                        && !state.is(Blocks.KELP) && !state.is(Blocks.KELP_PLANT)) return;
            }
        }
        var layer = ((LightSectionStorageAccessor) storage).fastdiffusion$writableLayer(
                SectionPos.asLong(pos.x, SectionPos.blockToSectionCoord(top), pos.z));
        if (layer == null) return;
        for (int y = top; y > top - 14; y--) {
            int light = 14 - (top - y);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                // Never decrease light that may have already arrived from a neighboring
                // column with a lower sky source (e.g. an open surface lake).
                if (layer.get(x, y & 15, z) < light) layer.set(x, y & 15, z, light);
                if (light <= 1) continue;
                long packed = BlockPos.asLong(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                if (x == 0) enqueueIncrease(packed, QueueEntry.increaseOnlyOneDirection(light, true, Direction.WEST));
                if (x == 15) enqueueIncrease(packed, QueueEntry.increaseOnlyOneDirection(light, true, Direction.EAST));
                if (z == 0) enqueueIncrease(packed, QueueEntry.increaseOnlyOneDirection(light, true, Direction.NORTH));
                if (z == 15) enqueueIncrease(packed, QueueEntry.increaseOnlyOneDirection(light, true, Direction.SOUTH));
            }
        }
    }
}
