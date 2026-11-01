import java.nio.file.Path;
import java.util.*;
import jdk.jfr.consumer.*;

/** Usage: java tools/SummarizeJfr.java recording.jfr. No dependencies beyond Java 21. */
class SummarizeJfr {
    public static void main(String[] args) throws Exception {
        Map<String, Long> methods = new HashMap<>(), leaves = new HashMap<>(), threads = new HashMap<>();
        Map<String, Long> allocations = new HashMap<>(), allocationSites = new HashMap<>();
        Map<String, Long> categories = new LinkedHashMap<>();
        Map<String, double[]> stages = new TreeMap<>();
        long samples = 0;
        double gc = 0;
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                var e = recording.readEvent();
                String type = e.getEventType().getName();
                if (type.equals("jdk.ExecutionSample")) {
                    samples++;
                    threads.merge(e.getThread("sampledThread").getJavaName(), 1L, Long::sum);
                    var stack = e.getStackTrace();
                    if (stack == null) continue;
                    Set<String> seen = new HashSet<>();
                    for (var f : stack.getFrames()) {
                        String name = method(f);
                        if (seen.add(name)) methods.merge(name, 1L, Long::sum);
                    }
                    String category = has(seen, "ChunkSerializer.write") ? "Chunk serialization"
                            : has(seen, "RegionFileStorage.write") ? "Region compression/writes"
                            : has(seen, "ColumnFiller.fill") ? "Terrain sections"
                            : has(seen, "NoiseBasedChunkGenerator.applyCarvers") ? "Cave carving"
                            : has(seen, "Heightmap.primeHeightmaps") ? "Heightmap scans"
                            : has(seen, "ChunkGenerator.applyBiomeDecoration") ? "Biome decoration"
                            : has(seen, "LightEngine.runLightUpdates") || has(seen, "SkyLightEngine.propagateLightSources")
                            || has(seen, "ChunkSkyLightSources.fillFrom") ? "Lighting"
                            : "Scheduling/other";
                    categories.merge(category, 1L, Long::sum);
                    if (!stack.getFrames().isEmpty()) leaves.merge(method(stack.getFrames().getFirst()), 1L, Long::sum);
                } else if (type.equals("jdk.ObjectAllocationSample")) {
                    long weight = e.getLong("weight");
                    allocations.merge(e.getClass("objectClass").getName(), weight, Long::sum);
                    var stack = e.getStackTrace();
                    if (stack != null && !stack.getFrames().isEmpty())
                        allocationSites.merge(method(stack.getFrames().getFirst()), weight, Long::sum);
                } else if (type.equals("minecraft.ChunkGeneration")) {
                    double[] stat = stages.computeIfAbsent(e.getString("status"), k -> new double[2]);
                    stat[0]++;
                    stat[1] += e.getDuration().toNanos() / 1e6;
                } else if (type.equals("jdk.GarbageCollection")) {
                    gc += e.getDuration("sumOfPauses").toNanos() / 1e6;
                }
            }
        }
        System.out.printf("Execution samples: %d; GC pauses: %.1f ms%n", samples, gc);
        print("Threads (samples)", threads, 20, 1);
        print("Exclusive categories (execution samples)", categories, 20, 1);
        print("Inclusive methods (samples; overlapping, not CPU milliseconds)", methods, 30, 1);
        Map<String, Long> generation = new HashMap<>();
        methods.forEach((k, v) -> {
            if (k.startsWith("net.minecraft.world.") || k.contains("ChunkSerializer") || k.contains("ChunkMap.save")
                    || k.startsWith("moe.phrolova.")) generation.put(k, v);
        });
        print("Generation/serialization methods (inclusive samples)", generation, 40, 1);
        print("Leaf methods (samples)", leaves, 20, 1);
        print("Sampled allocation estimates (MiB)", allocations, 15, 1048576);
        print("Sampled allocation sites (MiB)", allocationSites, 15, 1048576);
        System.out.println("Chunk stages: count / total ms / mean ms (includes async waiting)");
        stages.forEach((k, v) -> System.out.printf("%s: %.0f / %.1f / %.3f%n", k, v[0], v[1], v[1] / v[0]));
    }

    private static String method(RecordedFrame f) {
        return f.getMethod().getType().getName() + "." + f.getMethod().getName();
    }

    private static boolean has(Set<String> methods, String suffix) {
        return methods.stream().anyMatch(method -> method.endsWith(suffix));
    }

    private static void print(String title, Map<String, Long> values, int limit, double divisor) {
        System.out.println(title);
        values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                .forEach(e -> System.out.printf("  %10.1f %s%n", e.getValue() / divisor, e.getKey()));
    }
}
