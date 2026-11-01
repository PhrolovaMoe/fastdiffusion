package moe.phrolova.compat.mixin;

import moe.phrolova.world.WorldRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH 3.3.3 binds Chunky's provider before Chunky initializes on ServerStartedEvent. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor", remap = false)
public abstract class DhChunkyStartupMixin {
    @Inject(method = "tryRunFirstTimeSetup", at = @At("HEAD"), cancellable = true, require = 0)
    private void fastdiffusion$deferChunkyBinding(CallbackInfo ci) {
        if (!WorldRegistry.serverStarted()) ci.cancel();
    }
}
