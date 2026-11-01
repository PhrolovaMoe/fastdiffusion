package moe.phrolova.world;

import java.util.ArrayDeque;
import java.util.concurrent.CompletionException;

/** Deterministic executor checks ordering, disjoint concurrency, cleanup and failure propagation. */
public final class DecorationQueueTest {
    public static void main(String[] args) {
        var pending = new ArrayDeque<Runnable>();
        var queue = new DecorationQueue(pending::add);
        var first = queue.submit(-1, -1, () -> "first");
        var overlap = queue.submit(1, 1, () -> {
            require(first.isDone(), "diagonally overlapping work ran too soon");
            return "second";
        });
        var disjoint = queue.submit(4, -1, () -> "independent");
        require(pending.size() == 2, "disjoint work must be runnable immediately");
        pending.removeLast().run();
        require(disjoint.isDone() && !overlap.isDone(), "independent work was serialized");
        pending.removeFirst().run();
        require(pending.size() == 1, "overlap was not released after predecessor");
        pending.removeFirst().run();
        require(overlap.join().equals("second"), "overlap failed");
        var afterCleanup = queue.submit(-1, -1, () -> "reused");
        pending.removeFirst().run();
        require(afterCleanup.join().equals("reused"), "completed neighborhood was not released");

        var failure = queue.submit(0, 0, () -> { throw new IllegalStateException("expected"); });
        var dependent = queue.submit(1, 0, () -> { throw new AssertionError("must not execute after failure"); });
        pending.removeFirst().run();
        require(failure.isCompletedExceptionally() && dependent.isCompletedExceptionally(), "generation errors must propagate");
        require(pending.isEmpty(), "failed dependent was scheduled");
        try { dependent.join(); throw new AssertionError("missing failure"); }
        catch (CompletionException expected) { require(expected.getCause() instanceof IllegalStateException, "wrong cause"); }
        System.out.println("Decoration queue checks passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
