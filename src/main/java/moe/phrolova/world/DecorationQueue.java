package moe.phrolova.world;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Features read/write a 3x3 neighborhood; overlapping tasks retain submission order. */
final class DecorationQueue {
    private final Map<Long, CompletableFuture<?>> tails = new HashMap<>();
    private final Executor executor;
    private static final CompletableFuture<Void> READY = CompletableFuture.completedFuture(null);

    DecorationQueue(Executor executor) { this.executor = executor; }

    synchronized <T> CompletableFuture<T> submit(int chunkX, int chunkZ, Supplier<T> work) {
        long[] keys = new long[9];
        CompletableFuture<?>[] before = new CompletableFuture<?>[9];
        int i = 0;
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            long key = ((long) (chunkX + x) & 0xffffffffL) | ((long) (chunkZ + z) << 32);
            keys[i] = key;
            before[i++] = tails.getOrDefault(key, READY);
        }
        var result = CompletableFuture.allOf(before).thenApplyAsync(ignored -> work.get(), executor);
        for (long key : keys) tails.put(key, result);
        result.whenComplete((value, error) -> {
            synchronized (this) { for (long key : keys) tails.remove(key, result); }
        });
        return result;
    }
}
