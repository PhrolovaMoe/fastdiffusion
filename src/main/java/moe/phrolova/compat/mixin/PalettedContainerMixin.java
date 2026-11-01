package moe.phrolova.compat.mixin;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import moe.phrolova.world.UniformPalette;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tall diffusion chunks are mostly uniform rock/air: serialize those in constant time. */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> implements UniformPalette<T> {
    @Shadow private volatile PalettedContainer.Data<T> data;
    @Shadow public abstract void acquire();
    @Shadow public abstract void release();

    @Override
    public T fastdiffusion$uniformValue() {
        var snapshot = data;
        return snapshot.palette().getSize() == 1 ? snapshot.palette().valueFor(0) : null;
    }

    @Inject(method = "getAll", at = @At("HEAD"), cancellable = true)
    private void fastdiffusion$visitUniform(Consumer<T> consumer, CallbackInfo callback) {
        var snapshot = data;
        if (snapshot.palette().getSize() == 1) {
            consumer.accept(snapshot.palette().valueFor(0));
            callback.cancel();
        }
    }

    @Inject(method = "pack", at = @At("HEAD"), cancellable = true)
    private void fastdiffusion$packUniform(IdMap<T> registry, PalettedContainer.Strategy strategy,
            CallbackInfoReturnable<PalettedContainerRO.PackedData<T>> callback) {
        acquire();
        try {
            if (data.palette().getSize() == 1) {
                // Identical to vanilla's compacted output, without a 4096-entry int array,
                // unpack, palette remap and temporary hash palette for each solid/empty section.
                callback.setReturnValue(new PalettedContainerRO.PackedData<>(
                        List.of(data.palette().valueFor(0)), Optional.empty()));
            }
        } finally {
            release();
        }
    }
}
