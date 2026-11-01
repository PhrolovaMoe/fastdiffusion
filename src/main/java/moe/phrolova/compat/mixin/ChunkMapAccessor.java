package moe.phrolova.compat.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("worldGenContext") WorldGenContext fastdiffusion$context();
}
