package moe.phrolova.compat.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import moe.phrolova.world.TerrainChunkGenerator;
import moe.phrolova.world.UniformPalette;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reuse the codec result for uniform sections, with a fresh NBT copy for each save. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
    @Unique private static final Map<ServerLevel, Map<Object, Tag>> fastdiffusion$uniformTags = new WeakHashMap<>();

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static <A> DataResult<Tag> fastdiffusion$encodeUniform(Codec<A> codec, DynamicOps<Tag> ops, A value,
            ServerLevel level, ChunkAccess chunk) {
        if (ops == NbtOps.INSTANCE && level.getChunkSource().getGenerator() instanceof TerrainChunkGenerator
                && value instanceof UniformPalette<?> palette) {
            Object single = palette.fastdiffusion$uniformValue();
            if (single != null) {
                Map<Object, Tag> tags;
                synchronized (fastdiffusion$uniformTags) {
                    tags = fastdiffusion$uniformTags.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
                }
                Tag template = tags.computeIfAbsent(single, ignored -> codec.encodeStart(ops, value).getOrThrow());
                // Cached compounds/lists must never be shared with mutable saved chunk NBT.
                return DataResult.success(template.copy());
            }
        }
        return codec.encodeStart(ops, value);
    }
}
