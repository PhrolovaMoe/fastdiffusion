// Developer CLI: model checks against PyTorch references, benchmarks, terrain dumps.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "convert.h"
#include "engine.h"
#include "pipeline.h"
#include "service.h"
#include "unet.h"

using namespace fd;

namespace {

std::vector<float> read_f32(const std::string& path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) {
        std::fprintf(stderr, "cannot read %s\n", path.c_str());
        std::exit(1);
    }
    std::vector<float> v(f.tellg() / sizeof(float));
    f.seekg(0);
    f.read(reinterpret_cast<char*>(v.data()), v.size() * sizeof(float));
    return v;
}

double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct Case {
    const char* name;
    int n, size;
};
const Case kCases[] = {{"coarse", 1, 64}, {"base", 2, 64}, {"decoder", 1, 256}};

int cmd_check(const std::string& model, const std::string& ref, int device) {
    EngineOptions opts;
    opts.device = device;
    Engine engine(model, opts);
    int failures = 0;
    for (const Case& c : kCases) {
        UNet net(engine, c.name, std::string(c.name) != "decoder");
        const std::string p = ref + "/" + c.name;
        auto x = read_f32(p + ".x.bin");
        auto t = read_f32(p + ".t.bin");
        auto yref = read_f32(p + ".y.bin");
        std::vector<std::vector<float>> conds;
        std::vector<const float*> cond_ptrs;
        for (size_t i = 0; i < net.conds().size(); i++) {
            conds.push_back(read_f32(p + ".cond" + std::to_string(i) + ".bin"));
            cond_ptrs.push_back(conds.back().data());
        }
        std::vector<float> emb((size_t)c.n * net.emb_channels());
        net.embed(c.n, t.data(), cond_ptrs, emb.data());
        std::vector<float> y(yref.size());
        net.forward(c.n, c.size, c.size, x.data(), emb.data(), y.data());  // warm-up / shader compile
        const double t0 = now_ms();
        net.forward(c.n, c.size, c.size, x.data(), emb.data(), y.data());
        const double t1 = now_ms();

        double err2 = 0, ref2 = 0, maxerr = 0;
        for (size_t i = 0; i < y.size(); i++) {
            const double d = (double)y[i] - yref[i];
            err2 += d * d;
            ref2 += (double)yref[i] * yref[i];
            maxerr = std::max(maxerr, std::fabs(d));
        }
        const double rel = std::sqrt(err2 / ref2);
        const bool ok = rel < 1e-2;
        failures += !ok;
        std::printf("%-8s n=%d %dx%d  rel_rmse=%.2e  max_abs=%.3e  %.2f ms  %s\n", c.name, c.n, c.size, c.size, rel, maxerr,
                    t1 - t0, ok ? "OK" : "MISMATCH");
    }
    return failures ? 1 : 0;
}

int cmd_bench(const std::string& model, int device, const char* which, int n, int size, int iters) {
    EngineOptions opts;
    opts.device = device;
    Engine engine(model, opts);
    UNet net(engine, which, std::string(which) != "decoder");
    std::vector<float> x((size_t)n * net.in_channels() * size * size, 0.1f);
    std::vector<float> emb((size_t)n * net.emb_channels(), 0.1f);
    std::vector<float> y((size_t)n * net.out_channels() * size * size);
    net.forward(n, size, size, x.data(), emb.data(), y.data());
    const double t0 = now_ms();
    for (int i = 0; i < iters; i++) net.forward(n, size, size, x.data(), emb.data(), y.data());
    const double dt = (now_ms() - t0) / iters;
    std::printf("%s n=%d %dx%d: %.2f ms/iter, %.2f ms/tile\n", which, n, size, size, dt, dt / n);
    return 0;
}

// Renders an n x n grid of regions centred on the spawn search result (or a given block) into a shaded PPM.
int cmd_render(const std::string& model, uint64_t seed, int scale, int n, const std::string& out_path, const int64_t* centre) {
    ServiceOptions so;
    so.model_path = model;
    Service svc(so);
    WorldParams wp;
    wp.seed = seed;
    wp.scale = scale;
    wp.min_y = -64;
    wp.height = scale >= 6 ? 2096 : 512 + 384 * (scale - 1);
    const int world = svc.open_world(wp);

    double t0 = now_ms();
    int64_t sx = 0, sz = 0;
    if (centre) {
        sx = centre[0];
        sz = centre[1];
    } else {
        svc.find_land(world, sx, sz);
        std::printf("land near origin: x=%lld z=%lld (%.0f ms)\n", (long long)sx, (long long)sz, now_ms() - t0);
    }

    const int64_t rx0 = (sx >> 8) - n / 2, rz0 = (sz >> 8) - n / 2;
    std::vector<std::vector<uint8_t>> bufs(n * n, std::vector<uint8_t>(kRegionBytes));
    t0 = now_ms();
    for (int i = 0; i < n * n; i++) svc.request_region(world, rx0 + i % n, rz0 + i / n, 1, bufs[i].data());
    int done = 0, failed = 0;
    Completion c[64];
    while (done + failed < n * n) {
        const int k = svc.poll(c, 64, 1000);
        for (int i = 0; i < k; i++) {
            if (c[i].status) {
                failed++;
                std::fprintf(stderr, "region failed: %s\n", svc.take_error(c[i].id).c_str());
            } else {
                done++;
            }
        }
    }
    const double dt = now_ms() - t0;
    const auto st = svc.stats();
    std::printf("%d regions (%d chunks) in %.0f ms -> %.0f chunks/s  [gpu %.0f ms in %llu batches, cpu %.0f ms]\n", done,
                done * 256, dt, done * 256 / (dt / 1000.0), st.gpu_ms, (unsigned long long)st.gpu_batches, st.cpu_ms);

    const int W = n * kRegionSize;
    std::vector<int16_t> h((size_t)W * W);
    std::vector<uint8_t> biome((size_t)W * W);
    for (int i = 0; i < n * n; i++) {
        const auto* hh = reinterpret_cast<const int16_t*>(bufs[i].data() + kRegionHeightOffset);
        const uint8_t* bb = bufs[i].data() + kRegionBiomeOffset;
        for (int r = 0; r < kRegionSize; r++)
            for (int q = 0; q < kRegionSize; q++) {
                const size_t o = (size_t)((i / n) * kRegionSize + r) * W + (i % n) * kRegionSize + q;
                h[o] = hh[r * kRegionSize + q];
                biome[o] = bb[(r / 4) * kRegionQuarts + q / 4];
            }
    }
    static const uint8_t pal[BIOME_COUNT][3] = {
        {141, 179, 96}, {240, 240, 250}, {230, 210, 150}, {120, 110, 40}, {50, 120, 40}, {40, 90, 70}, {200, 220, 220},
        {190, 180, 90}, {130, 140, 120}, {40, 140, 20}, {200, 110, 50}, {150, 190, 110}, {190, 210, 200}, {230, 235, 245},
        {220, 230, 255}, {140, 140, 140}, {40, 120, 170}, {30, 70, 160}, {40, 70, 130}, {120, 140, 180}, {90, 140, 70},
        {70, 110, 80}, {180, 200, 200}, {20, 50, 130}, {30, 50, 110}, {100, 120, 170}, {30, 100, 150}, {230, 220, 160},
        {235, 235, 225}, {120, 120, 110}};
    FILE* f = std::fopen(out_path.c_str(), "wb");
    std::fprintf(f, "P6 %d %d 255\n", W, W);
    for (int r = 0; r < W; r++)
        for (int q = 0; q < W; q++) {
            const int y = h[(size_t)r * W + q];
            const int yl = h[(size_t)r * W + std::max(0, q - 1)], yu = h[(size_t)std::max(0, r - 1) * W + q];
            const float shade = std::clamp(1.0f + 0.08f * (float)((y - yl) + (y - yu)), 0.5f, 1.4f);
            const uint8_t* p = pal[std::min<int>(biome[(size_t)r * W + q], BIOME_COUNT - 1)];
            uint8_t rgb[3];
            for (int k = 0; k < 3; k++) rgb[k] = (uint8_t)std::clamp(p[k] * shade, 0.0f, 255.0f);
            std::fwrite(rgb, 1, 3, f);
        }
    std::fclose(f);
    int16_t lo = 32767, hi = -32768;
    for (int16_t v : h) lo = std::min(lo, v), hi = std::max(hi, v);
    std::printf("height range: [%d, %d], wrote %s\n", lo, hi, out_path.c_str());
    return failed ? 1 : 0;
}

// Samples one map grid (w x w at `step` blocks per pixel, centred on a block) and writes a shaded PPM.
int cmd_grid(const std::string& model, uint64_t seed, int scale, int64_t cx, int64_t cz, double step, int w, int source,
             const std::string& out_path) {
    ServiceOptions so;
    so.model_path = model;
    Service svc(so);
    WorldParams wp;
    wp.seed = seed;
    wp.scale = scale;
    wp.min_y = -64;
    wp.height = scale >= 6 ? 2096 : 512 + 384 * (scale - 1);
    const int world = svc.open_world(wp);
    GridRequest req;
    req.x0 = cx - w * step / 2;
    req.z0 = cz - w * step / 2;
    req.step = step;
    req.w = req.h = w;
    req.source = source ? GridSource::Latent : GridSource::Coarse;
    std::vector<uint8_t> out(grid_bytes(w, w));
    for (int pass = 0; pass < 2; pass++) {  // second pass: cached tiles, CPU only
        const double t0 = now_ms();
        svc.request_grid(world, req, 1, out.data());
        Completion c;
        while (svc.poll(&c, 1, 1000) == 0) {}
        if (c.status) {
            std::fprintf(stderr, "grid failed: %s\n", svc.take_error(c.id).c_str());
            return 1;
        }
        const auto st = svc.stats();
        std::printf("pass %d: %dx%d @ %.1f blocks/px in %.0f ms [gpu %.0f ms, cpu %.0f ms]\n", pass, w, w, step, now_ms() - t0,
                    st.gpu_ms, st.cpu_ms);
    }
    const auto* hh = reinterpret_cast<const int16_t*>(out.data());
    const uint8_t* bb = out.data() + (size_t)w * w * 2;
    FILE* f = std::fopen(out_path.c_str(), "wb");
    std::fprintf(f, "P6 %d %d 255\n", w, w);
    int lo = 32767, hi = -32768, hist[BIOME_COUNT] = {};
    for (int r = 0; r < w; r++)
        for (int q = 0; q < w; q++) {
            const int y = hh[r * w + q], yl = hh[r * w + std::max(0, q - 1)], yu = hh[std::max(0, r - 1) * w + q];
            lo = std::min(lo, y), hi = std::max(hi, y);
            hist[std::min<int>(bb[r * w + q], BIOME_COUNT - 1)]++;
            const float shade = std::clamp(1.0f + 0.02f * (float)((y - yl) + (y - yu)) / (float)step * 4, 0.5f, 1.4f);
            const uint8_t base = (uint8_t)std::clamp(40 + (y + 64) / 10, 0, 255);
            uint8_t rgb[3] = {base, base, base};
            if (y < 62) rgb[0] = 30, rgb[1] = 60, rgb[2] = 140;
            for (uint8_t& v : rgb) v = (uint8_t)std::clamp(v * shade, 0.0f, 255.0f);
            std::fwrite(rgb, 1, 3, f);
        }
    std::fclose(f);
    std::printf("height range [%d, %d]; biomes:", lo, hi);
    for (int b = 0; b < BIOME_COUNT; b++)
        if (hist[b]) std::printf(" %d:%d", b, hist[b]);
    std::printf("\nwrote %s\n", out_path.c_str());
    return 0;
}

// Dumps native-resolution elevation (float32 meters, size x size) centred on the land search result,
// for statistical comparison with the PyTorch WorldPipeline.
int cmd_elev(const std::string& model, uint64_t seed, int size, const std::string& out_path) {
    EngineOptions eo;
    Models models(model, eo);
    WorldParams wp;
    wp.seed = seed;
    World world(models, wp, (size_t)2 << 30);
    int64_t ci = 0, cj = 0;
    world.find_land(cj, ci);  // scale 1: blocks are native pixels
    const int64_t i0 = ci - size / 2, j0 = cj - size / 2;
    std::printf("land near origin: native (%lld, %lld)\n", (long long)ci, (long long)cj);

    std::vector<float> out((size_t)size * size);
    const double t0 = now_ms();
    for (int64_t rz = i0 >> 8; rz <= (i0 + size - 1) >> 8; rz++)
        for (int64_t rx = j0 >> 8; rx <= (j0 + size - 1) >> 8; rx++) {
            Plan plan;
            world.plan_region(rx, rz, plan);
            world.execute(plan);
            const RegionInputs in = world.gather_region(rx, rz, plan);
            const Raster dec = laplacian_decode_denoised(in.residual, in.lowfreq, 5.0f);
            for (int64_t i = std::max(i0, rz * 256); i < std::min(i0 + size, rz * 256 + 256); i++)
                for (int64_t j = std::max(j0, rx * 256); j < std::min(j0 + size, rx * 256 + 256); j++) {
                    const float e = dec.at((int)(i - in.win.pi0), (int)(j - in.win.pj0));
                    out[(size_t)(i - i0) * size + (j - j0)] = std::copysign(e * e, e);
                }
        }
    std::printf("%dx%d native px in %.0f ms\n", size, size, now_ms() - t0);
    std::ofstream(out_path, std::ios::binary).write(reinterpret_cast<const char*>(out.data()), out.size() * sizeof(float));
    return 0;
}

void usage() {
    std::fprintf(stderr,
                 "usage:\n"
                 "  fd-cli check <model.gguf> <ref_dir> [device]\n"
                 "  fd-cli grid <model.gguf> <seed> <scale> <x> <z> <blocks/px> <size> <0 coarse|1 latent> <out.ppm>\n"
                 "  fd-cli bench <model.gguf> <coarse|base|decoder> <batch> <size> [iters] [device]\n"
                 "  fd-cli convert <weights_dir> <pipeline_data.json> <out.gguf>\n"
                 "  fd-cli render <model.gguf> <seed> <scale> <regions_per_side> <out.ppm> [centre_x centre_z]\n"
                 "  fd-cli elev <model.gguf> <seed> <size> <out.f32>\n");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        usage();
        return 2;
    }
    const std::string cmd = argv[1];
    try {
        if (cmd == "check" && argc >= 4) return cmd_check(argv[2], argv[3], argc > 4 ? std::atoi(argv[4]) : -1);
        if (cmd == "grid" && argc >= 11)
            return cmd_grid(argv[2], std::strtoull(argv[3], nullptr, 10), std::atoi(argv[4]), std::atoll(argv[5]),
                            std::atoll(argv[6]), std::atof(argv[7]), std::atoi(argv[8]), std::atoi(argv[9]), argv[10]);
        if (cmd == "bench" && argc >= 6)
            return cmd_bench(argv[2], argc > 7 ? std::atoi(argv[7]) : -1, argv[3], std::atoi(argv[4]), std::atoi(argv[5]),
                             argc > 6 ? std::atoi(argv[6]) : 10);
        if (cmd == "convert" && argc >= 5) {
            convert_model(argv[2], argv[3], argv[4]);
            return 0;
        }
        if (cmd == "render" && argc >= 7) {
            const int64_t centre[2] = {argc >= 9 ? std::atoll(argv[7]) : 0, argc >= 9 ? std::atoll(argv[8]) : 0};
            return cmd_render(argv[2], std::strtoull(argv[3], nullptr, 10), std::atoi(argv[4]), std::atoi(argv[5]), argv[6],
                              argc >= 9 ? centre : nullptr);
        }
        if (cmd == "elev" && argc >= 6) return cmd_elev(argv[2], std::strtoull(argv[3], nullptr, 10), std::atoi(argv[4]), argv[5]);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
    usage();
    return 2;
}
