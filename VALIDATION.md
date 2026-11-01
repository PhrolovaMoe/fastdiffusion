# Validation — 2026-10-12

Hardware: Radeon RX 7700 XT, RADV NAVI32, Intel i5-12400F. Java 21, NeoForge 21.1.259, Minecraft 1.21.1. F16 model, seed 12345, scale 6. GPU shader caches were already warm. These are measurements on this machine, not a performance guarantee.

| Check | Result |
|---|---|
| `./tools/dev.sh build` | Passed; JAR includes the native library and all six presets |
| Coarse model against PyTorch | Relative RMSE 0.440%, maximum absolute error 0.0536 |
| Base model against PyTorch | Relative RMSE 0.476%, maximum absolute error 0.0756 |
| Decoder against PyTorch | Relative RMSE 0.850%, maximum absolute error 0.0194 |
| All eight pinned download assets | Local original files match their embedded SHA-256 checksums |
| Terrain-only 4×4-region render | 4,096 chunk-equivalents in 1.600 seconds; 2,560 per second |
| Fresh server with Chunky 1.4.23 and DH 3.3.3 | Ready in 6.913 seconds; direct LOD generator registered |
| DH pregeneration at (16384, 16384), radius 32 chunks | Completed in ~3.1 seconds, API validation enabled, no errors |
| Chunky square at (-8192, -8192), radius 512 blocks | 4,225 full chunks in 33 seconds; approximately 128 chunks/second |
| Native stats after both pregenerators | 65 regions, zero failed, zero queued; GPU 5.4 seconds, CPU 0.3 seconds |
| Fresh server without DH | Ready in 6.820 seconds; optional integration/mixin does not prevent startup |
| Combined server shutdown | Saved all dimensions and exited cleanly |
| JAR dependency inspection | Vulkan loader, glibc and libm; no CUDA/ROCm/ONNX runtime; no bundled Chunky/DH classes |

Full-chunk testing used `sync-chunk-writes=false`. The initial test with synchronous writes stalled in the disk writer (`pwrite`, waiting on an ext4 journal commit), rather than in the terrain engine. Stronghold biome relocation was also generating distant diffusion regions during startup; the final implementation keeps the seeded ring positions and completes startup without those searches.

The native render measures heights/biomes only. Chunky includes Minecraft's structures, features, lighting and saving. DH produces terrain-only LODs. These three timings measure different work and should not be compared as interchangeable CPS numbers.

The NeoForge config screen registration compiles; interactive client rendering and the config screen have not yet been visually tested. Reference comparisons use the existing PyTorch fixtures and a 1% relative-RMSE threshold.

Reproduction logs and generated terrain images are in `.work/` and are intentionally excluded from source control.

## Browser atlas and Meadows — 2026-10-13

On the same Java 21 / NeoForge 21.1.259 / RX 7700 XT setup:

* TanStack Start SPA shell and all assets are bundled under `web/` in the mod JAR. The packaged HTTP server served the login route, map, binary terrain tiles and authenticated SSE stream in Chromium at desktop and 390px mobile widths.
* An unauthenticated `/api/info` request returned 401. A one-time command link successfully signed in and connected the live stream.
* Binary tile decoding and Chunky selection rounding pass focused Bun tests. Native Meadow classification passes nine inclusion/exclusion cases without a model or GPU. Gradle `check` runs type checking, both web tests, and the native test.
* A 9,000-block search around seed 12345's spawn returned five Meadows checked on full terrain. One result at **X=2320, Z=7888** had ground Y=231, 100% sampled Meadow coverage within the 128-block verification window, and a nearby sampled peak at Y=340. The integration query used a 50-block minimum relief; this does not claim that the stricter default Meadow valley preset finds a match at that seed's spawn.
* A verification player joined the actual Minecraft server. SSE reflected its position, a web teleport, and a subsequent in-game `/tp`. Teleporting to the Meadow generated the target chunk and returned **2320.5, 232, 7888.5**; Minecraft's `/execute if biome ... minecraft:meadow` passed at the destination.
* Shared waypoint create/read/delete succeeded. Chunky started and completed a 25-chunk selection. Its asynchronous pause transition is represented separately from a paused task; pause settled as paused (preserving the real count), and resume/cancel returned success through the server API. Chunky's finish event also fires on pause, so the integration reads the task count/cancel state instead of assuming completion.
* The map/server started with Chunky absent and hid generation controls. Shutdown saved all dimensions and exited cleanly.

The player integration test exposed a pre-existing feature-order cycle between the custom dry grove and vanilla stony shore. Swapping dry grove's sugar cane/pumpkin order resolved it; the combined vanilla/custom feature ordering was checked for cycles, and newly generated Meadow chunks completed successfully afterward. The browser preview displays procedural terrain, not trees/structures/player builds. Existing saved chunks retain their previous biomes after the Meadow classifier update; version 2 map tiles invalidate the old procedural biome cache.

## Chunky profiling and surface mode — 2026-10-13

See [PERFORMANCE.md](PERFORMANCE.md) for the profiling method, settings, per-area limits and reproduction commands. On the same hardware, a matched fresh 16,641-chunk ocean selection with structures, ores and underground patches disabled took **83.799 seconds** in the original generator (198.6 CPS). The final surface mode with 1,024 requests took **17.247 seconds** with deflate (964.9 CPS), or **15.232 seconds** with LZ4 (1,092.5 CPS). Including explicit final save flushing, the LZ4 run took 18.565 seconds (896.4 CPS, including up to one second of completion polling delay). The larger recorded land workload remained below 1,000 CPS.

The full build and the focused decoration-queue checks pass. Saved biome palettes and heightmaps match across the 4,225-chunk ocean comparison; saved light arrays match in all 4,225 chunks with/without the ocean lighting shortcut. A 4,225-chunk land crop retained vanilla birch/oak/spruce generation with 5,624 versus 5,626 trunk blocks and 58,295 versus 58,033 leaf blocks. Surface mode defaults to OFF, structures remain optional, and existing user saves were not changed.

Test server saves, temporary protocol client, browser logs and build logs are in `.work/`, excluded from source control.

## Distant Horizons LOD generation — 2026-10-13

DH 3.3.3 rejected most of the previous direct LODs: datapoints carried the request's detail level instead of block size, Y was absolute rather than relative to the level bottom (rendering errors such as `Y-:4032`), and columns tagged `SURFACE` never counted as finished for full-detail sections. The generator only served detail levels 0–2, so DH split distant sections into 256-block requests. DH also paused its generator for as long as Chunky ran, which left distant terrain to its single-threaded ingest of 2,096-block-tall chunks.

Same hardware, dedicated test server with DH 3.3.3 and Chunky 1.4.23, seed 12345, scale 6, surface mode, structures OFF, LZ4, 1,024 Chunky requests, `sync-chunk-writes=false`:

| Check | Result |
|---|---|
| `dh pregen` radius 128 chunks (66,049 chunks, full detail) | 12.2 seconds (~5,400 CPS), zero errors with DH API validation |
| Regions for that pregen | 289 with the LOD region cache, against 4,101 without it (CPU post-processing 2.1 s against 29.1 s) |
| Chunky radius 1,024 blocks, five fresh land squares alone | 699, 690, 801, 665 CPS (mean ~714) |
| Same with a concurrent radius-256 `dh pregen` | 605, 607, 695, 527, 626 CPS (mean ~612); DH reached 1,000–3,000 CPS meanwhile |
| DH's "Chunky running. Disabling DH world gen" | Not logged; distant generation continued during Chunky |
| `./tools/dev.sh build` | Passed |

Full-detail pregen is the worst case for the LOD path; a client requests coarse levels for distant terrain, which use one map-grid request per 64×64-column section. Coarse grid levels and in-game rendering were not exercised by the headless server and have not been visually checked. The remaining Chunky cost while LODs generate is DH's own CPU work (data packing, compression, SQLite writes and ingesting Chunky's chunks); native GPU and CPU totals stayed small.
