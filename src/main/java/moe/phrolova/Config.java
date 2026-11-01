package moe.phrolova;

import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Common config; editable in-game through the mod list's config button. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Where FastDiffusion keeps its files").push("storage");
    }

    private static final ModConfigSpec.ConfigValue<String> DATA_DIRECTORY = BUILDER
            .comment("Directory for models and the extracted native library. Empty: <game directory>/fastdiffusion.",
                    "Point this at a drive with ~2 GB free if your game directory lives on a small drive.")
            .gameRestart()
            .define("dataDirectory", "");

    private static final ModConfigSpec.ConfigValue<String> MODEL_FILE = BUILDER
            .comment("Model file (GGUF). Empty: <dataDirectory>/models/" + "terrain-diffusion-30m-f16.gguf")
            .gameRestart()
            .define("modelFile", "");

    private static final ModConfigSpec.BooleanValue AUTO_DOWNLOAD = BUILDER
            .comment("Download and convert the model on first use if the model file is missing (~1 GB download).")
            .define("autoDownload", true);

    static {
        BUILDER.pop().comment("Inference").push("inference");
    }

    private static final ModConfigSpec.IntValue GPU_DEVICE = BUILDER
            .comment("Vulkan device index. -1: first GPU, -2: CPU only (very slow).")
            .gameRestart()
            .defineInRange("gpuDevice", -1, -2, 16);

    private static final ModConfigSpec.IntValue CPU_WORKERS = BUILDER
            .comment("Threads for post-processing generated terrain. 0: automatic.")
            .gameRestart()
            .defineInRange("cpuWorkers", 0, 0, 64);

    private static final ModConfigSpec.IntValue TILE_CACHE_MB = BUILDER
            .comment("Native cache for intermediate model outputs, per world (MiB).")
            .gameRestart()
            .defineInRange("tileCacheMb", 1024, 64, 16384);

    static {
        BUILDER.pop().comment("World generation").push("worldgen");
    }

    private static final ModConfigSpec.IntValue REGION_CACHE = BUILDER
            .comment("Finished 256x256 regions kept in memory (~200 KiB each).")
            .defineInRange("regionCache", 512, 16, 16384);

    private static final ModConfigSpec.IntValue PREFETCH_RADIUS = BUILDER
            .comment("Regions (256 blocks) generated ahead around each player. 0 disables prefetching.")
            .defineInRange("prefetchRadius", 2, 0, 16);

    private static final ModConfigSpec.BooleanValue SURFACE_ONLY = BUILDER
            .comment("Fast surface generation: skip caves, ores, underground patches, geodes, fossils and dungeons.",
                    "Keeps terrain, surface materials, trees, vegetation, lighting and the world's structures option.",
                    "Applies only to newly generated chunks in diffusion worlds; restart to change it.")
            .gameRestart()
            .define("surfaceOnly", false);

    static {
        BUILDER.pop().comment("Web map (operators only; open it with /fastdiffusion web)").push("web");
    }

    private static final ModConfigSpec.ConfigValue<String> WEB_BIND = BUILDER
            .comment("Address the web map listens on. 127.0.0.1 keeps it on this machine; 0.0.0.0 exposes it to the network.")
            .define("bind", "127.0.0.1");

    private static final ModConfigSpec.IntValue WEB_PORT = BUILDER
            .comment("TCP port of the web map.")
            .defineInRange("port", 8790, 1, 65535);

    private static final ModConfigSpec.ConfigValue<String> WEB_PUBLIC_URL = BUILDER
            .comment("Base URL used in login links, e.g. https://map.example.com behind a reverse proxy. Empty: http://<bind>:<port>.")
            .define("publicUrl", "");

    private static final ModConfigSpec.BooleanValue WEB_AUTO_START = BUILDER
            .comment("Start the web map with the server instead of on the first /fastdiffusion web.")
            .define("autoStart", false);

    private static final ModConfigSpec.IntValue WEB_SESSION_DAYS = BUILDER
            .comment("Days a browser stays signed in.")
            .defineInRange("sessionDays", 30, 1, 365);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}

    public static Path dataDirectory() {
        String dir = DATA_DIRECTORY.get().trim();
        return dir.isEmpty() ? FMLPaths.GAMEDIR.get().resolve("fastdiffusion") : Path.of(dir);
    }

    public static Path modelFile() {
        String file = MODEL_FILE.get().trim();
        return file.isEmpty() ? dataDirectory().resolve("models").resolve("terrain-diffusion-30m-f16.gguf") : Path.of(file);
    }

    public static boolean autoDownload() { return AUTO_DOWNLOAD.get(); }
    public static int gpuDevice() { return GPU_DEVICE.get(); }
    public static int cpuWorkers() { return CPU_WORKERS.get(); }
    public static long tileCacheBytes() { return TILE_CACHE_MB.get() * 1024L * 1024L; }
    public static int regionCache() { return REGION_CACHE.get(); }
    public static int prefetchRadius() { return PREFETCH_RADIUS.get(); }
    public static boolean surfaceOnly() { return SURFACE_ONLY.get(); }
    public static String webBind() { return WEB_BIND.get().trim(); }
    public static int webPort() { return WEB_PORT.get(); }
    public static String webPublicUrl() { return WEB_PUBLIC_URL.get().trim(); }
    public static boolean webAutoStart() { return WEB_AUTO_START.get(); }
    public static int webSessionDays() { return WEB_SESSION_DAYS.get(); }
}
