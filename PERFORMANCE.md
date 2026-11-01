# Chunky profiling — 2026-10-13

The diffusion model is not the bottleneck in the reproduced ~200 CPS workload. Minecraft spends the time expanding and serializing palettes, scheduling chunk dependencies, carving caves, rescanning heightmaps, placing features and propagating light. Removing ore deposits alone leaves those costs intact.

## Reproduction and measurements

Intel i5-12400F (6 cores / 12 threads), Radeon RX 7700 XT / RADV, Java 21, Minecraft 1.21.1, NeoForge 21.1.259, Chunky 1.4.23. Seed 12345, scale 6, Y=-64..2031, structures OFF, ores OFF, underground patches OFF, no players or DH, `sync-chunk-writes=false`. World saves are on the checkout's HDD. Vulkan shader caches were warm. Each ocean comparison used a newly populated test save and a fresh server/native world; only the spawn area's native data was initially cached.

Ocean selection: square centered at (-8192,-8192), radius 1,024 blocks, 16,641 requested chunks. Generation timing runs from issuing `chunky start` to the timestamp of its actual 100% finish log. Save totals include a subsequent `save-all flush` and up to one second of finish-observation delay.

| Run | Requests in flight | Compression | JFR | Generation | Generation CPS | Through save flush | CPS including flush |
|---|---:|---|---|---:|---:|---:|---:|
| Original generator, both deposit options off | 50 | deflate | yes | 83.799 s | 198.6 | 86.636 s | 192.1 |
| Final surface mode | 1,024 | deflate | no | 17.247 s | 964.9 | 20.510 s | 811.4 |
| Final surface mode | 1,024 | LZ4 | no | 15.232 s | 1,092.5 | 18.565 s | 896.4 |

The target is demonstrated for full chunk **generation** in the ocean case. It is not yet demonstrated for sustained generation plus physical save flushing across all terrain. An additional 16,641-chunk land selection at (2320,7888), radius 1,024, with surface mode, 1,024 requests, LZ4 and JFR took 21.417 seconds to finish generation (777 CPS), and 25.406 seconds through flushing (655 CPS). Different area, recording overhead, terrain relief and vegetation prevent treating that as the same workload as the ocean comparison. A smaller original land selection (4,225 chunks, radius 512) took 17.747 seconds (238 CPS).

Chunky removes its task from the active map after submitting its last request, before all generation futures have finished. `chunky progress` reporting "No tasks running" is therefore insufficient to time completion, especially with a larger concurrency limit. The profiling helper waits for the actual 100% finish log; earlier exploratory timings based on task removal are excluded from this table.

## What the profile showed

JFR records 2 ms Java execution samples, sampled allocations, GC pauses and Minecraft's per-status chunk-generation events. Java samples do not measure native Vulkan work; `/fastdiffusion stats` supplies the native totals. Status durations include asynchronous waiting and overlap; adding them is not a wall-clock timing.

In the original 16,641-chunk ocean run, 67,109 Java execution samples split into these non-overlapping categories:

| Category | Samples | Share |
|---|---:|---:|
| Chunk serialization | 16,349 | 24.4% |
| Cave carving | 6,695 | 10.0% |
| Heightmap scans | 4,968 | 7.4% |
| Biome decoration, excluding the above scans | 7,087 | 10.6% |
| Lighting | 7,276 | 10.8% |
| Region compression/writes | 4,848 | 7.2% |
| Terrain section filling | 3,163 | 4.7% |
| Scheduling and other Java work | 16,723 | 24.9% |

`PalettedContainer.pack` appeared in 13,580 inclusive samples. Vanilla allocates a 4,096-entry integer array, unpacks and remaps every block even for a section containing one stone/air state. Scale 6 has 131 sections per chunk, making that disproportionately expensive. `/fastdiffusion stats` showed only about 2.9 seconds of additional GPU work for the original 83.8-second task: the inference device was mostly waiting for Minecraft.

Once underground work and repeated scans are removed, the remaining work is spread among lighting, vegetation, saving and scheduling. A recorded optimized ocean run reduced the total to 12,570 Java samples; caves disappeared and heightmap scans fell to 24 samples. Lighting was still 2,596 samples. This evidence favors optimizing the chunk/light/save pipeline before porting vanilla trees to Vulkan. A GPU tree implementation would also have to reproduce placement, collision checks and neighboring-chunk effects to preserve appearance.

## Implemented changes

* Optional `[worldgen] surfaceOnly=true`, default false, applied when the generator binds at server/world startup. It suppresses caves, deposits, geodes, fossils and dungeons. The world's persisted ore/patch flags and structures choice are retained.
* With surface mode and structures OFF, generation dependencies omit the structure-only eight-chunk rings. Biome, feature, lighting and blending-neighbor requirements remain. Structures ON uses the original dependency pyramid.
* Vanilla decoration runs on background workers. A per-world queue gives each task exclusive access to its 3×3 neighborhood, keeps overlapping tasks in submission order, and allows disjoint tasks to proceed without blocking worker threads. Feature order, per-feature random seeds and vanilla tree implementations remain.
* Mixed terrain sections are built as packed material indices; uniform rock sections keep their single-state representation. This removes palette expansion/copying and per-block counter updates during terrain filling. Section counters are initialized by Minecraft's section constructor.
* Final heightmaps are initialized directly from terrain columns. The fast feature task consumes these maps, which neighboring features update normally, instead of rescanning every column. The original feature task still rescans when used outside the fast dependency pyramid.
* Decoration inspects one biome section per diffusion chunk, because its biome classification is identical at every height. Uniform palette visitation and packing avoid scanning the entire container.
* Chunk saving caches uniform section codec results per level and copies their NBT for each save; mutable saved tags are never shared. Mixed containers still use the normal codec and palette compaction.
* For unobstructed ocean columns, skylight is seeded with the exact 14..1 direct-water gradient, and border propagation remains vanilla. The entire band is checked before modifying light data. Ice, coral, trees, shorelines, builds and any other obstruction fall back to vanilla lighting. Existing stronger light is retained.

A larger linear light-storage cache was tested and removed because it did not show a meaningful benefit. No GPU feature rewrite or native model change was needed for the measured ocean improvement.

## Surface and correctness checks

* `./tools/dev.sh build` passes Java compilation, web type checking and both web tests, the native biome classifier test, and the deterministic decoration-queue test. The queue test covers overlapping neighborhoods, disjoint work, cleanup and error propagation.
* Decoding actual saved chunks before and after the terrain/feature changes found identical biome palettes and all saved heightmaps in the 4,225-chunk ocean comparison. Material differences were underwater plants/water from decoration ordering; terrain materials were retained.
* Comparing saved light arrays with and without the ocean shortcut found **4,225/4,225 identical** sky/block light results in that selection.
* A 4,225-chunk land crop retained birch, oak and spruce. Original: 496 chunks with trees, 5,624 trunk blocks and 58,295 leaf blocks. Optimized: 493 chunks with trees, 5,626 trunk blocks and 58,033 leaf blocks. Vanilla placement is unchanged, but removal of caves and scheduling changes do not promise identical vegetation at every block. The tests establish matching terrain/biomes and comparable vegetation, not a visual review of every biome.
* Test servers completed generation, flushed their saves and shut down without chunk-generation/save errors. Test worlds and JFR recordings are retained under `.work/`; existing user worlds were not modified.

## Run it and profile it

Enable surface mode in the common config, restart, and use Minecraft's existing structures option. For the measured fastest server configuration, set `region-file-compression=lz4`, `sync-chunk-writes=false`, and add `-Dchunky.maxWorkingCount=1024` to the server/game JVM arguments. Chunky's default is 50; a smaller limit is appropriate when memory is constrained. The 1,024-request test process used roughly 9 GiB RSS with an approximately 8 GiB maximum Java heap. The mod does not change these server/Chunky settings.

Development runs expose the concurrency setting with:

```sh
./tools/dev.sh runServer -PtestRunDir=.work/your-disposable-server -PchunkyWorkingCount=1024
```

Use a fresh selection in a disposable test save, a fixed seed/settings, and an already-running local RCON server. The helper starts generation and flushes its saves:

```sh
RCON_PASSWORD='<test server password>' python3 tools/profile_chunky.py <java-pid> .work/run.jfr --radius 1024
# Same measurement without JFR overhead:
RCON_PASSWORD='<test server password>' python3 tools/profile_chunky.py <java-pid> .work/timing.jfr --radius 1024 --no-jfr
# Summarize a retained recording without exporting gigabytes of JSON:
/usr/lib/jvm/openjdk21/bin/java tools/SummarizeJfr.java .work/run.jfr
```

The helper defaults to the ocean selection and reads the server's `logs/latest.log` via its process working directory. `--center`, `--port`, `--log` and `--java-home` can override those assumptions. A reused/generated selection is not a throughput benchmark.
