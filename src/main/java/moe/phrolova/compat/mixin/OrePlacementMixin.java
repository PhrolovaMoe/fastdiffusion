package moe.phrolova.compat.mixin;

import moe.phrolova.world.TerrainChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.neoforge.common.Tags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skip disabled ore deposits and underground patches before their placement attempts. */
@Mixin(PlacedFeature.class)
public abstract class OrePlacementMixin {
    @Inject(method = {"place", "placeWithBiomeCheck"}, at = @At("HEAD"), cancellable = true)
    private void fastdiffusion$skipDeposits(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
                                      BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (!(generator instanceof TerrainChunkGenerator terrain)) return;
        var feature = ((PlacedFeature) (Object) this).feature().value();
        if (terrain.surfaceOnly() && (feature.feature() == Feature.GEODE
                || feature.feature() == Feature.FOSSIL || feature.feature() == Feature.MONSTER_ROOM)) {
            callback.setReturnValue(false);
            return;
        }
        if (terrain.generateOres() && terrain.generateUndergroundPatches()) return;
        var config = feature.config();
        if (!(config instanceof OreConfiguration deposit)) return;
        if (terrain.surfaceOnly()) {
            callback.setReturnValue(false);
            return;
        }
        boolean skip = deposit.targetStates.stream().anyMatch(target -> {
            var state = target.state;
            return (!terrain.generateOres() && state.is(Tags.Blocks.ORES))
                    || (!terrain.generateUndergroundPatches() && (state.is(Blocks.DIRT)
                    || state.is(Blocks.GRAVEL) || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE)
                    || state.is(Blocks.ANDESITE) || state.is(Blocks.TUFF)));
        });
        if (skip) callback.setReturnValue(false);
    }
}
