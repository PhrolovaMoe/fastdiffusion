package moe.phrolova.compat.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LayerLightSectionStorage.class)
public interface LightSectionStorageAccessor {
    @Invoker("getDataLayerToWrite") DataLayer fastdiffusion$writableLayer(long section);
}
