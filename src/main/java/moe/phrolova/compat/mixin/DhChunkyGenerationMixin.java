package moe.phrolova.compat.mixin;

import moe.phrolova.compat.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH 3.3.3 pauses its distant generator while Chunky runs, so the two never compete for Minecraft's chunk pipeline.
 * FastDiffusion LODs never touch that pipeline and run below chunk priority on the terrain service, so they keep
 * going; chunks Chunky saves still replace them whenever DH's update queue keeps up.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor", remap = false)
public abstract class DhChunkyGenerationMixin {
    @Inject(method = "onGenEvent", at = @At("HEAD"), cancellable = true, require = 0)
    private void fastdiffusion$keepLodGeneration(CallbackInfo ci) {
        if (DistantHorizonsCompat.generating()) ci.cancel();
    }
}
