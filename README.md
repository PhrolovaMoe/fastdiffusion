# FastDiffusion

Minecraft **1.21.1 / NeoForge 21.1**, Linux x86-64. Terrain Diffusion inference runs through **ggml Vulkan**, using F16 GGUF weights. The native engine and ggml build with Meson; the Java bridge uses JNI. No CUDA, ROCm, ONNX Runtime or Python is needed to play.

## Install

Put `build/libs/fastdiffusion-1.3.jar` in your instance's `mods` folder. Use Java 21 and a working Vulkan driver. The bundled CPU backend requires AVX2/FMA/F16C/BMI2; CPU inference is available but substantially slower.

Create a new world, select the **Diffusion** world type and use **Customize** to pick the scale: 1:1 to 1:6 covers 30, 15, 10, 7.5, 6 and 5 metres per block (default 1:6). **1:6** has build limits **Y=-64 through Y=2031** (2,096 blocks tall). Set **Generate ores: OFF** in Customize to skip ore deposits during pregeneration while keeping other decoration and stone/dirt/gravel patches. **Underground patches: OFF** also skips dirt, gravel, granite, diorite, andesite and tuff deposits without removing surface soil or beach/river disks. Both settings are saved with the world and default to ON. The Nether and End use their vanilla generators.

On first use, the mod downloads pinned, SHA-256-verified safetensors and configuration files, then converts them to a ~543 MiB F16 GGUF. Allow about 2 GiB of free space during installation. Source downloads are removed after conversion; interrupted downloads reuse files that already passed verification. Conversion writes a temporary file before replacing the final model.

The NeoForge Mods screen has a **Config** button. Settings are also in `config/fastdiffusion-common.toml`. Storage/device settings require a game restart. To keep files on another drive, set these **before creating the first diffusion world**:

```toml
[storage]
dataDirectory = "/home/phrolova/mounts/sdrive/Games/FastDiffusion"
modelFile = ""
autoDownload = true
```

An empty `modelFile` uses `<dataDirectory>/models/terrain-diffusion-30m-f16.gguf`. An absolute `modelFile` can point at an existing converted model. An empty `dataDirectory` uses `<game directory>/fastdiffusion`. Use absolute paths; `~` is not expanded. This setting moves models and extracted native libraries; Minecraft world saves remain in your instance's normal save directory.

For a dedicated server, use `level-type=fastdiffusion:diffusion` (scale 1:6) in `server.properties` **before generating a new world**. `fastdiffusion:scale_1` through `scale_6` select a specific scale; they are hidden from the world type list. Keep a world's preset fixed once generated.

## Browser atlas

Run `/fastdiffusion web` as an operator (or from the server console), then open the one-time login link. The mod serves the TanStack Start SPA and its API on **127.0.0.1:8790** by default. No separate web process is required. `/fastdiffusion web stop` stops the map; `/fastdiffusion web revoke` signs out all browser sessions. Login links expire after ten minutes; browser sessions default to thirty days and player-issued sessions require continued operator permission.

The atlas includes:

* WebGL2 terrain with height, biome, temperature, rainfall and slope layers, adjustable sunlight, contours, and instant highlight filters.
* Scenic location searches using coarse screening, latent overview sampling, summed-area biome coverage and sliding mountain maxima, followed by checks against full terrain. Results stream onto the map. Very large verification windows remain marked as estimates.
* Live player markers, following, and teleporting onto the generated surface. Teleporting generates the destination chunk first; oceans place players on the water surface.
* Shared world waypoints, coordinate navigation, shareable map URLs, a Ctrl+K search palette, and elevation profiles drawn across loaded terrain.
* Optional Chunky controls: draw and resize areas, preview terrain in 3D, inspect saved chunks, and start, pause, resume or cancel generation. Time and disk estimates are advisory; disk estimates come from existing region files.

**Meadow valley** searches Minecraft Meadows with surrounding mountains. Meadows form on gently sloping, cool upland grassland and open woodland, between 600 and 2,500 metres above sea level. This applies to newly generated terrain; already saved chunks retain their biomes. Badlands is not currently produced by the classifier. The biome chooser shows the IDs the generator actually produces. Mapping uses generated terrain height/climate fields; the preview does not include structures, trees, caves or player builds. Map generation does not save Minecraft chunks; Chunky and teleporting do.

For remote access, configure `[web]` in `config/fastdiffusion-common.toml`:

```toml
[web]
bind = "0.0.0.0"
port = 8790
publicUrl = "https://map.example.com"
autoStart = false
sessionDays = 30
```

`publicUrl` controls the login link. Route that HTTPS origin to the configured HTTP port; proxy `/api/events` without response buffering. For iframe embedding, append `embed=1` to a signed-in map URL to hide controls. Authentication still applies and the embedded browser must retain its session for the map origin. The SPA shell falls back for client routes, and all fonts/assets are bundled locally.

Development requires **Bun** in addition to the native/Java build tools. Gradle installs the frozen lockfile, builds the SPA into the JAR, and runs web type checking and the focused binary-tile/selection tests during `check`.

```sh
cd web
bun install --frozen-lockfile
bun run dev               # localhost:3000; API proxy defaults to localhost:8790
bun run typecheck
bun run test
```

Set `FASTDIFFUSION_API` to point the development proxy at another server. Log in through the development origin using a fresh `/login?code=...` link. Terrain tile caches live under the world's `fastdiffusion/map/` directory, isolated by dimension, seed, scale and format. Shared waypoints and hashed session records live under `fastdiffusion/web/` in the world save.

## Compatibility and performance

* **Chunky 1.4.23:** uses normal asynchronous chunk generation, including terrain, lighting, structures and features.
* **Distant Horizons 3.3.3:** direct LOD generation from the same heights, biome IDs and surface materials, with low-priority GPU requests. Nearby LODs sample full regions; distant LOD levels (up to 4,096 blocks per column) sample the much cheaper map grids, so DH never needs Minecraft chunks for distant terrain. LOD generation keeps its own small region cache and never evicts regions that chunk generation is using. DH normally pauses its distant generator while Chunky runs; FastDiffusion keeps it running, and chunks Chunky saves still replace the terrain-only LODs whenever DH's update queue keeps up. Distant LODs contain terrain and water; trees, caves and structures appear when actual chunks are generated. Install DH on the server or in a single-player instance to generate distant terrain. A remote client cannot run this pipeline without the server's world generator.
* **NeoForge configuration screen:** built-in configuration UI; no additional config mod required.

The DH/Chunky startup hook is deferred until Chunky has initialized. Strongholds keep their vanilla seeded ring positions, without the optional biome relocation search that would otherwise generate thousands of distant diffusion regions during startup. Structure generation still checks the actual terrain and biomes.

Models stay on the inference device. One GPU worker batches tile requests; native CPU workers finalize heights/biomes. Finished regions and intermediate tiles have bounded **in-memory** caches. Player prefetching is configurable. Native resources are released when the server stops. `/fastdiffusion stats` (operator permission) reports the device, completed/failed/queued regions and cumulative GPU/CPU time.

Slow synchronous world saves can dominate Chunky throughput on a hard disk. `sync-chunk-writes=false` avoids a synchronous write for each chunk, with Minecraft's usual durability tradeoff if the machine loses power. FastDiffusion does not change this setting for you.

### Fast surface pregeneration

For maps that prioritize the surface, enable this in `config/fastdiffusion-common.toml` and restart:

```toml
[worldgen]
surfaceOnly = true
```

This skips cave carving, ores, underground patches, geodes, fossils and dungeons in newly generated diffusion chunks. Terrain, surface materials, trees, vegetation, lakes and lighting remain. Existing saved chunks are retained. The option defaults to OFF. Structures remain controlled by Minecraft's world option; with structures OFF, the generator also uses a smaller dependency neighborhood and runs decoration concurrently where neighborhoods do not overlap.

For maximum Chunky throughput, add `-Dchunky.maxWorkingCount=1024` to the game's/server's **JVM arguments**. Chunky's default is 50. The larger limit uses more memory; the test server used about 9 GiB RSS with an approximately 8 GiB maximum Java heap. In a dedicated server's `server.properties`, `region-file-compression=lz4` can reduce compression work; Minecraft reads both old deflate chunks and newly written LZ4 chunks. Keep `sync-chunk-writes=false` if asynchronous writes suit your setup. These are opt-in settings; the mod does not alter Chunky's limit or the server's save options.

On the documented i5-12400F / RX 7700 XT setup, a fresh 16,641-chunk ocean selection improved from **199 to 1,092 chunks/s** with surface mode, 1,024 requests and LZ4. Default deflate compression reached **965 chunks/s**. Land/vegetation and final disk flushing can remain below 1,000; this is not a universal throughput guarantee. See [PERFORMANCE.md](PERFORMANCE.md) for profiling, surface checks, exact timings and reproduction commands.

## Build

Install Java 21, Bun, a C/C++20 compiler, Meson >=1.3, Ninja, pkg-config, Vulkan development files and `glslc` (shaderc). The pinned ggml and SPIRV-Headers sources are fetched through Meson wraps.

```sh
./tools/dev.sh build
```

The helper puts Gradle caches and temporary files in this checkout's `.work`, and prefers `/usr/lib/jvm/openjdk21` if installed. Set `FASTDIFFUSION_JAVA_HOME` to use another Java 21 installation. Building the JAR automatically builds and bundles `libfastdiffusion.so`. Its dynamic dependencies are the system Vulkan loader, glibc and libm.

```sh
# Native engine and developer CLI only
JAVA_HOME=/usr/lib/jvm/openjdk21 meson setup build/native native
meson compile -C build/native

# Development server, with optional DH installed for compatibility testing
./tools/dev.sh runServer -PwithDH

# Terrain-only render: seed 12345, scale 6, 4x4 regions
build/native/fd-cli render /path/to/model.gguf 12345 6 4 terrain.ppm

# Convert original checkpoints without Python
build/native/fd-cli convert /path/to/weights /path/to/pipeline_data.json /path/to/model.gguf
```

Chunky is a development runtime dependency. DH is compile-only unless `-PwithDH` is supplied. Neither is bundled. The model comparison helpers in `tools/` need Python/PyTorch only for development.

## Attribution

Based on Alexander Goslin's [Terrain Diffusion research](https://arxiv.org/abs/2512.08309), [model implementation](https://github.com/xandergos/terrain-diffusion) and [Minecraft reference mod](https://github.com/xandergos/terrain-diffusion-mc). Model checkpoints: [xandergos/terrain-diffusion-30m](https://huggingface.co/xandergos/terrain-diffusion-30m). FastDiffusion is GPLv3; the upstream Terrain Diffusion code, ggml and FastNoiseLite carry their MIT notices in `META-INF/licenses` inside the JAR.
