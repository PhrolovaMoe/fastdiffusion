package moe.phrolova.compat;

import java.util.function.Consumer;
import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.GenerationTask;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.api.ChunkyAPI;
import org.popcraft.chunky.event.task.GenerationTaskFinishEvent;

/**
 * Optional Chunky integration for the web map. Loads Chunky classes: check
 * {@code ModList.get().isLoaded("chunky")} before touching this class. World names are dimension ids,
 * e.g. {@code minecraft:overworld}.
 */
public final class ChunkyCompat {
    /** A running task. {@code total} is the number of chunks in the selection. */
    public record Task(String world, String shape, String pattern, double centerX, double centerZ, double radiusX,
                       double radiusZ, long done, long total, float percent, double rate, long etaSeconds, int chunkX,
                       int chunkZ) {}

    /** A progress update, delivered on Chunky's worker thread. */
    public record Progress(String world, long done, boolean complete, float percent, double rate, long etaSeconds,
                           long chunkX, long chunkZ) {}

    private static volatile Consumer<Progress> progressSink = p -> {};
    public record Finished(Task task, boolean cancelled, boolean complete) {}

    private static volatile Consumer<Finished> completeSink = w -> {};
    private static Chunky hooked;

    private ChunkyCompat() {}

    /** Routes Chunky progress to the given sinks; hooks Chunky's API once per Chunky instance. */
    public static synchronized boolean listen(Consumer<Progress> onProgress, Consumer<Finished> onComplete) {
        progressSink = onProgress;
        completeSink = onComplete;
        Chunky chunky = chunky();
        if (chunky == null) return false;
        if (chunky != hooked) {
            hooked = chunky;
            ChunkyAPI api = chunky.getApi();
            api.onGenerationProgress(e -> progressSink.accept(new Progress(e.world(), e.chunks(), e.complete(), e.progress(),
                    e.rate(), e.hours() * 3600 + e.minutes() * 60 + e.seconds(), e.x(), e.z())));
            // The public completion event also fires on pause. The task event carries the
            // actual count and cancellation state, so paused tasks are never shown as complete.
            chunky.getEventBus().subscribe(GenerationTaskFinishEvent.class, e -> {
                GenerationTask t = e.generationTask();
                completeSink.accept(new Finished(snapshot(t), t.isCancelled(), t.getCount() >= t.getChunkIterator().total()));
            });
        }
        return true;
    }

    public static synchronized void unlisten() {
        progressSink = p -> {};
        completeSink = w -> {};
    }

    public static boolean available() {
        return chunky() != null;
    }

    public static boolean start(String world, String shape, double x, double z, double rx, double rz, String pattern) {
        Chunky chunky = require();
        if (chunky.getApi().isRunning(world)) return false;
        return chunky.getApi().startTask(world, shape, x, z, rx, rz, pattern);
    }

    public static boolean pause(String world) {
        return require().getApi().pauseTask(world);
    }

    public static boolean resume(String world) {
        return require().getApi().continueTask(world);
    }

    public static boolean cancel(String world) {
        return require().getApi().cancelTask(world);
    }

    /** The running task of a world, or null. */
    public static Task task(String world) {
        Chunky chunky = chunky();
        if (chunky == null) return null;
        GenerationTask t = chunky.getGenerationTasks().get(world);
        if (t == null || t.isCancelled()) return null;
        return snapshot(t);
    }

    private static Task snapshot(GenerationTask t) {
        Selection s = t.getSelection();
        GenerationTask.Progress p = t.getProgress();
        return new Task(s.world().getName(), s.shape(), s.pattern().getType(), s.centerX(), s.centerZ(), s.radiusX(), s.radiusZ(),
                t.getCount(), t.getChunkIterator().total(), p.getPercentComplete(), p.getRate(),
                p.getHours() * 3600 + p.getMinutes() * 60 + p.getSeconds(), p.getChunkX(), p.getChunkZ());
    }

    private static Chunky chunky() {
        try {
            return ChunkyProvider.get();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Chunky require() {
        Chunky chunky = chunky();
        if (chunky == null) throw new IllegalStateException("Chunky is not ready");
        return chunky;
    }
}
