#include "pipeline.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>

#include "FastNoiseLite.h"
#include "log.h"
#include "noise.h"

namespace fd {

namespace {

// Tile geometry per stage (units: coarse px, latent px, native px).
constexpr int kCoarseSize = 64, kCoarseStride = 48, kCoarseCh = 6;
constexpr int kLatentSize = 64, kLatentStride = 32, kLatentCh = 5;
constexpr int kDecoderSize = 256, kDecoderStride = 192;
constexpr int kNativePerCoarse = 256;  // native px per coarse px
constexpr int kLatentPerCoarse = 32;

constexpr int kCoarseSteps = 20;
constexpr int kLatentBatch = 8;
constexpr int kDecoderBatch = 2;
constexpr int kCoarseBatch = 8;

constexpr float kSigmaData = 0.5f, kSigmaMin = 0.002f, kSigmaMax = 80.0f, kRho = 7.0f;
constexpr float kLowfreqMean = -31.4f, kLowfreqStd = 38.6f;
constexpr float kCondMeans[7] = {14.99f, 11.65f, 15.87f, 619.26f, 833.12f, 69.40f, 0.66f};
constexpr float kCondStds[7] = {21.72f, 21.78f, 10.40f, 452.29f, 738.09f, 34.59f, 0.47f};
constexpr float kLaplacianSigma = 5.0f;
constexpr int kClimateWindow = 15;

// Noise streams.
enum : uint32_t { kStreamCoarseCond = 1, kStreamCoarseSample, kStreamLatentInit, kStreamLatentStep, kStreamDecoder };

inline int64_t floor_div(int64_t a, int64_t b) { return a / b - ((a % b != 0) && ((a < 0) != (b < 0))); }
inline int64_t ceil_div(int64_t a, int64_t b) { return -floor_div(-a, b); }

std::vector<float> linear_weight_window(int s) {
    std::vector<float> w((size_t)s * s);
    const float mid = (s - 1) / 2.0f, eps = 1e-3f;
    for (int r = 0; r < s; r++) {
        const float wy = 1 - (1 - eps) * std::min(1.0f, std::fabs(r - mid) / mid);
        for (int c = 0; c < s; c++) {
            const float wx = 1 - (1 - eps) * std::min(1.0f, std::fabs(c - mid) / mid);
            w[(size_t)r * s + c] = wy * wx;
        }
    }
    return w;
}

// Weighted blend of channels [c0, c0 + nc) of a tiled plane over a window. out: [nc][h][w].
void blend(const TileMap& tiles, int size, int stride, const std::vector<float>& window, int c0, int nc,
           int64_t i0, int64_t j0, int h, int w, float* out) {
    const size_t plane = (size_t)h * w;
    std::vector<float> wsum(plane, 0.0f);
    std::fill(out, out + nc * plane, 0.0f);
    const size_t tplane = (size_t)size * size;
    for (int64_t ti = floor_div(i0 - size, stride) + 1; ti <= floor_div(i0 + h - 1, stride); ti++) {
        for (int64_t tj = floor_div(j0 - size, stride) + 1; tj <= floor_div(j0 + w - 1, stride); tj++) {
            auto it = tiles.find({ti, tj});
            if (it == tiles.end() || !it->second) throw std::logic_error("blend: tile not available");
            const float* t = it->second->data();
            const int64_t ti0 = ti * stride, tj0 = tj * stride;
            const int64_t r0 = std::max(i0, ti0), r1 = std::min(i0 + h, ti0 + size);
            const int64_t q0 = std::max(j0, tj0), q1 = std::min(j0 + w, tj0 + size);
            for (int64_t r = r0; r < r1; r++) {
                const size_t trow = (size_t)(r - ti0) * size, orow = (size_t)(r - i0) * w;
                for (int64_t q = q0; q < q1; q++) {
                    const size_t tidx = trow + (size_t)(q - tj0), oidx = orow + (size_t)(q - j0);
                    const float wt = window[tidx];
                    wsum[oidx] += wt;
                    for (int c = 0; c < nc; c++) out[c * plane + oidx] += wt * t[(size_t)(c0 + c) * tplane + tidx];
                }
            }
        }
    }
    for (int c = 0; c < nc; c++)
        for (size_t p = 0; p < plane; p++) out[c * plane + p] /= wsum[p];
}

// Karras sigmas + DPM-Solver++(2M) as EDMDPMSolverMultistepScheduler (midpoint, final sigma 0).
struct DpmSolver {
    std::vector<float> sigmas;
    int step_index = 0, lower_order = 0;
    std::vector<float> prev_x0;

    explicit DpmSolver(int n) : sigmas(n + 1) {
        const float min_inv = std::pow(kSigmaMin, 1.0f / kRho), max_inv = std::pow(kSigmaMax, 1.0f / kRho);
        for (int i = 0; i < n; i++) sigmas[i] = std::pow(max_inv + (float)i / (n - 1) * (min_inv - max_inv), kRho);
        sigmas[n] = 0.0f;
    }

    // In-place update of `sample` given the raw model output at the current sigma.
    void step(const std::vector<float>& model_out, std::vector<float>& sample) {
        const int n = (int)sigmas.size() - 1;
        const float s = sigmas[step_index], t = sigmas[step_index + 1];
        const float sd2 = kSigmaData * kSigmaData;
        const float c_skip = sd2 / (s * s + sd2), c_out = s * kSigmaData / std::sqrt(s * s + sd2);
        std::vector<float> x0(sample.size());
        for (size_t i = 0; i < sample.size(); i++) x0[i] = c_skip * sample[i] + c_out * model_out[i];

        const float ratio = t / s;
        if (lower_order < 1 || step_index == n - 1) {
            for (size_t i = 0; i < sample.size(); i++) sample[i] = ratio * sample[i] - (ratio - 1.0f) * x0[i];
        } else {
            const double h = std::log((double)s / t), h0 = std::log((double)sigmas[step_index - 1] / s);
            const float r0 = (float)(h0 / h);
            const float d0c = -(ratio - 1.0f), d1c = -0.5f * (ratio - 1.0f);
            for (size_t i = 0; i < sample.size(); i++) {
                const float d1 = (x0[i] - prev_x0[i]) / r0;
                sample[i] = ratio * sample[i] + d0c * x0[i] + d1c * d1;
            }
        }
        prev_x0 = std::move(x0);
        if (lower_order < 2) lower_order++;
        step_index++;
    }
};

PipelineConfig load_config(const Engine& e) {
    PipelineConfig c;
    c.coarse_means = e.f32_array("fd.pipeline.coarse_means");
    c.coarse_stds = e.f32_array("fd.pipeline.coarse_stds");
    c.cond_snr = e.f32_array("fd.pipeline.cond_snr");
    c.residual_mean = e.f32("fd.pipeline.residual_mean");
    c.residual_std = e.f32("fd.pipeline.residual_std");
    c.native_resolution = e.f32("fd.pipeline.native_resolution");
    c.latent_compression = (int)e.u32("fd.pipeline.latent_compression");
    if (c.latent_compression != 8) throw std::runtime_error("unsupported latent compression");
    return c;
}

SyntheticMapTables load_tables(const Engine& e) {
    SyntheticMapTables t;
    const int n = (int)e.u32("fd.synth.n_quantiles");
    const auto& dq = e.host("synth.data_quantiles");
    for (int c = 0; c < SyntheticMap::kChannels; c++) t.data_quantiles.emplace_back(dq.begin() + c * n, dq.begin() + (c + 1) * n);
    t.frequency_mult = e.f32_array("fd.pipeline.frequency_mult");
    t.a_temp_std = e.f32("fd.synth.a_temp_std");
    t.b_temp_std = e.f32("fd.synth.b_temp_std");
    t.temp_std_p1 = e.f32("fd.synth.temp_std_p1");
    t.temp_std_p99 = e.f32("fd.synth.temp_std_p99");
    return t;
}

FastNoiseLite make_fbm(int seed, float freq, int octaves, float gain) {
    FastNoiseLite n(seed);
    n.SetNoiseType(FastNoiseLite::NoiseType_Perlin);
    n.SetFrequency(freq);
    n.SetFractalType(FastNoiseLite::FractalType_FBm);
    n.SetFractalOctaves(octaves);
    n.SetFractalLacunarity(2.0f);
    n.SetFractalGain(gain);
    return n;
}

}  // namespace

// ---- Models ----------------------------------------------------------------------------------

Models::Models(const std::string& path, const EngineOptions& opts)
    : engine(path, opts),
      coarse(engine, "coarse", true),
      base(engine, "base", true),
      decoder(engine, "decoder", false),
      cfg(load_config(engine)),
      synth(load_tables(engine)) {}

// ---- TileCache -------------------------------------------------------------------------------

TilePtr TileCache::get(const TileKey& k) {
    auto it = map_.find(k);
    if (it == map_.end()) return nullptr;
    lru_.splice(lru_.begin(), lru_, it->second.lru);
    return it->second.tile;
}

void TileCache::put(const TileKey& k, TilePtr t) {
    auto it = map_.find(k);
    if (it != map_.end()) {
        bytes_ -= it->second.tile->size() * sizeof(float);
        lru_.erase(it->second.lru);
        map_.erase(it);
    }
    bytes_ += t->size() * sizeof(float);
    lru_.push_front(k);
    map_[k] = {std::move(t), lru_.begin()};
    while (bytes_ > max_bytes_ && lru_.size() > 1) {
        auto victim = map_.find(lru_.back());
        bytes_ -= victim->second.tile->size() * sizeof(float);
        map_.erase(victim);
        lru_.pop_back();
    }
}

void TileCache::clear() {
    map_.clear();
    lru_.clear();
    bytes_ = 0;
}

// ---- World -----------------------------------------------------------------------------------

World::World(Models& models, const WorldParams& params, size_t cache_bytes)
    : m_(models),
      params_(params),
      synth_(params.seed, models.synth),
      coarse_cache_(cache_bytes / 16),
      init_cache_(cache_bytes * 3 / 16),
      step_cache_(cache_bytes * 4 / 16),
      decoder_cache_(cache_bytes * 8 / 16),
      win64_(linear_weight_window(64)),
      win256_(linear_weight_window(256)),
      detail_seed_(mix64(params.seed ^ 0x5eedf00dULL)) {
    if (params.scale < 1 || params.scale > 16) throw std::invalid_argument("scale must be in [1, 16]");
}

NativeWindow World::region_window(int64_t rx, int64_t rz) const {
    const int s = params_.scale;
    constexpr int kBorder = 2;  // blocks: one for the slope output, one for the Sobel used by detail/biomes
    const int lc = m_.cfg.latent_compression;
    const int64_t pad = (int64_t)((int)(kLaplacianSigma * 2) / 2 * 2 + 1) / 2 + 1;  // low-res padding (6)
    auto native_lo = [&](int64_t b) { return (int64_t)std::floor((b + 0.5) / s - 0.5); };
    NativeWindow w{};
    const int64_t bz0 = rz * kRegionSize - kBorder, bz1 = rz * kRegionSize + kRegionSize + kBorder;
    const int64_t bx0 = rx * kRegionSize - kBorder, bx1 = rx * kRegionSize + kRegionSize + kBorder;
    w.ni0 = native_lo(bz0);
    w.ni1 = native_lo(bz1 - 1) + 2;
    w.nj0 = native_lo(bx0);
    w.nj1 = native_lo(bx1 - 1) + 2;
    w.pi0 = floor_div(w.ni0 - pad * lc, lc) * lc;
    w.pi1 = ceil_div(w.ni1 + pad * lc, lc) * lc;
    w.pj0 = floor_div(w.nj0 - pad * lc, lc) * lc;
    w.pj1 = ceil_div(w.nj1 + pad * lc, lc) * lc;
    const int half = kClimateWindow / 2;
    // Two coarse px beyond the region on each side: the cubic climate stencil must never clamp,
    // or neighbouring regions would disagree along their shared edge.
    w.ci0 = floor_div(w.ni0, kNativePerCoarse) - 2 - half;
    w.ci1 = floor_div(w.ni1, kNativePerCoarse) + 3 + half;
    w.cj0 = floor_div(w.nj0, kNativePerCoarse) - 2 - half;
    w.cj1 = floor_div(w.nj1, kNativePerCoarse) + 3 + half;
    return w;
}

void World::need_coarse_px(int64_t ci0, int64_t ci1, int64_t cj0, int64_t cj1, Plan& plan) {
    for (int64_t ti = floor_div(ci0 - kCoarseSize, kCoarseStride) + 1; ti <= floor_div(ci1 - 1, kCoarseStride); ti++)
        for (int64_t tj = floor_div(cj0 - kCoarseSize, kCoarseStride) + 1; tj <= floor_div(cj1 - 1, kCoarseStride); tj++) {
            const TileKey k{ti, tj};
            if (plan.coarse.count(k) || plan.coarse_missing.count(k)) continue;
            if (TilePtr t = coarse_cache_.get(k)) plan.coarse[k] = t;
            else plan.coarse_missing.insert(k);
        }
}

void World::need_latents(int64_t li0, int64_t li1, int64_t lj0, int64_t lj1, Plan& plan) {
    for (int64_t ti = floor_div(li0 - kLatentSize, kLatentStride) + 1; ti <= floor_div(li1 - 1, kLatentStride); ti++)
        for (int64_t tj = floor_div(lj0 - kLatentSize, kLatentStride) + 1; tj <= floor_div(lj1 - 1, kLatentStride); tj++) {
            const TileKey k{ti, tj};
            if (plan.step.count(k) || plan.step_missing.count(k)) continue;
            if (TilePtr t = step_cache_.get(k)) {
                plan.step[k] = t;
                continue;
            }
            plan.step_missing.insert(k);
            need_coarse_px(ti - 1, ti + 3, tj - 1, tj + 3, plan);
            for (int64_t di = -1; di <= 1; di++)
                for (int64_t dj = -1; dj <= 1; dj++) {
                    const TileKey ik{ti + di, tj + dj};
                    if (plan.init.count(ik) || plan.init_missing.count(ik)) continue;
                    if (TilePtr t = init_cache_.get(ik)) {
                        plan.init[ik] = t;
                    } else {
                        plan.init_missing.insert(ik);
                        need_coarse_px(ik.i - 1, ik.i + 3, ik.j - 1, ik.j + 3, plan);
                    }
                }
        }
}

void World::plan_region(int64_t rx, int64_t rz, Plan& plan) {
    const NativeWindow w = region_window(rx, rz);
    const int lc = m_.cfg.latent_compression;
    const int64_t lat = kDecoderSize / lc, lat_stride = kDecoderStride / lc;
    for (int64_t ti = floor_div(w.pi0 - kDecoderSize, kDecoderStride) + 1; ti <= floor_div(w.pi1 - 1, kDecoderStride); ti++)
        for (int64_t tj = floor_div(w.pj0 - kDecoderSize, kDecoderStride) + 1; tj <= floor_div(w.pj1 - 1, kDecoderStride); tj++) {
            const TileKey k{ti, tj};
            if (plan.decoder.count(k) || plan.decoder_missing.count(k)) continue;
            if (TilePtr t = decoder_cache_.get(k)) {
                plan.decoder[k] = t;
            } else {
                plan.decoder_missing.insert(k);
                need_latents(ti * lat_stride, ti * lat_stride + lat, tj * lat_stride, tj * lat_stride + lat, plan);
            }
        }
    need_latents(w.pi0 / lc, w.pi1 / lc, w.pj0 / lc, w.pj1 / lc, plan);
    need_coarse_px(w.ci0, w.ci1, w.cj0, w.cj1, plan);
}

void World::execute(Plan& plan) {
    auto sorted = [](const KeySet& s) {
        std::vector<TileKey> v(s.begin(), s.end());
        std::sort(v.begin(), v.end(), [](const TileKey& a, const TileKey& b) { return a.i != b.i ? a.i < b.i : a.j < b.j; });
        return v;
    };
    if (!plan.coarse_missing.empty()) run_coarse(sorted(plan.coarse_missing), plan);
    if (!plan.init_missing.empty()) run_latent(sorted(plan.init_missing), true, plan);
    if (!plan.step_missing.empty()) run_latent(sorted(plan.step_missing), false, plan);
    if (!plan.decoder_missing.empty()) run_decoder(sorted(plan.decoder_missing), plan);
    plan.coarse_missing.clear();
    plan.init_missing.clear();
    plan.step_missing.clear();
    plan.decoder_missing.clear();
}

void World::run_coarse(const std::vector<TileKey>& keys, Plan& plan) {
    constexpr int S = kCoarseSize;
    constexpr size_t plane = (size_t)S * S;
    const auto& cfg = m_.cfg;
    static constexpr int kMeanIdx[5] = {0, 2, 3, 4, 5};

    std::vector<float> cond_vals(5), cos_t(5), sin_t(5);
    for (int c = 0; c < 5; c++) {
        const float t = std::atan(cfg.cond_snr[c]);
        cos_t[c] = std::cos(t);
        sin_t[c] = std::sin(t);
        cond_vals[c] = std::log(cfg.cond_snr[c] / 8.0f);
    }

    for (size_t b0 = 0; b0 < keys.size(); b0 += kCoarseBatch) {
        const int n = (int)std::min<size_t>(kCoarseBatch, keys.size() - b0);
        std::vector<float> cond_mixed((size_t)n * 5 * plane), sample((size_t)n * kCoarseCh * plane);
        std::vector<float> syn(5 * plane), noise(5 * plane);
        DpmSolver sched(kCoarseSteps);
        for (int b = 0; b < n; b++) {
            const TileKey& k = keys[b0 + b];
            const int64_t i0 = k.i * kCoarseStride, j0 = k.j * kCoarseStride;
            synth_.sample(i0, j0, S, S, syn.data());
            for (size_t p = 0; p < plane; p++) {
                float& t = syn[plane + p];
                if (t <= 20.0f) t = (t - 20.0f) * 1.25f + 20.0f;
            }
            gaussian_patch(noise.data(), params_.seed, kStreamCoarseCond, 5, i0, j0, S, S);
            for (int c = 0; c < 5; c++) {
                const float mean = cfg.coarse_means[kMeanIdx[c]], sd = cfg.coarse_stds[kMeanIdx[c]];
                for (size_t p = 0; p < plane; p++)
                    cond_mixed[((size_t)b * 5 + c) * plane + p] =
                        cos_t[c] * (syn[c * plane + p] - mean) / sd + sin_t[c] * noise[c * plane + p];
            }
            gaussian_patch(&sample[(size_t)b * kCoarseCh * plane], params_.seed, kStreamCoarseSample, kCoarseCh, i0, j0, S, S);
        }
        for (float& v : sample) v *= sched.sigmas[0];

        std::vector<float> x_in((size_t)n * 11 * plane), out(sample.size()), emb((size_t)n * m_.coarse.emb_channels());
        std::vector<float> cnoise(n);
        std::vector<std::vector<float>> conds(5, std::vector<float>(n));
        for (int c = 0; c < 5; c++) std::fill(conds[c].begin(), conds[c].end(), cond_vals[c]);
        std::vector<const float*> cond_ptrs;
        for (auto& c : conds) cond_ptrs.push_back(c.data());

        for (int step = 0; step < kCoarseSteps; step++) {
            const float sigma = sched.sigmas[step];
            const float c_in = 1.0f / std::sqrt(sigma * sigma + kSigmaData * kSigmaData);
            for (int b = 0; b < n; b++) {
                float* dst = &x_in[(size_t)b * 11 * plane];
                const float* src = &sample[(size_t)b * kCoarseCh * plane];
                for (size_t p = 0; p < kCoarseCh * plane; p++) dst[p] = src[p] * c_in;
                std::memcpy(dst + kCoarseCh * plane, &cond_mixed[(size_t)b * 5 * plane], 5 * plane * sizeof(float));
            }
            std::fill(cnoise.begin(), cnoise.end(), std::atan(sigma / kSigmaData));
            m_.coarse.embed(n, cnoise.data(), cond_ptrs, emb.data());
            m_.coarse.forward(n, S, S, x_in.data(), emb.data(), out.data());
            sched.step(out, sample);
        }

        for (int b = 0; b < n; b++) {
            auto tile = std::make_shared<Tile>((size_t)kCoarseCh * plane);
            const float* src = &sample[(size_t)b * kCoarseCh * plane];
            for (int c = 0; c < kCoarseCh; c++)
                for (size_t p = 0; p < plane; p++)
                    (*tile)[c * plane + p] = src[c * plane + p] / kSigmaData * cfg.coarse_stds[c] + cfg.coarse_means[c];
            for (size_t p = 0; p < plane; p++) (*tile)[plane + p] = (*tile)[p] - (*tile)[plane + p];  // p5 = elev - p5
            plan.coarse[keys[b0 + b]] = tile;
            coarse_cache_.put(keys[b0 + b], tile);
        }
    }
}

void World::latent_conditioning(const TileKey& k, const Plan& plan, float* out58) const {
    float v[kCoarseCh * 16];
    blend(plan.coarse, kCoarseSize, kCoarseStride, win64_, 0, kCoarseCh, k.i - 1, k.j - 1, 4, 4, v);
    float img[7][16];
    for (int c = 0; c < kCoarseCh; c++)
        for (int p = 0; p < 16; p++) {
            const float x = (v[c * 16 + p] - kCondMeans[c]) / kCondStds[c];
            img[c][p] = std::isnan(x) ? 0.0f : x;
        }
    for (int p = 0; p < 16; p++) img[6][p] = (1.0f - kCondMeans[6]) / kCondStds[6];

    // mp_concat of [means(16), p5(16), climate means(4), mask(16), histogram(5), noise level(1)]
    constexpr int dims[6] = {16, 16, 4, 16, 5, 1};
    const float C = std::sqrt(58.0f * 6.0f);
    float scale[6];
    for (int i = 0; i < 6; i++) scale[i] = C / std::sqrt((float)dims[i]) / 6.0f;
    int o = 0;
    for (int p = 0; p < 16; p++) out58[o++] = img[0][p] * scale[0];
    for (int p = 0; p < 16; p++) out58[o++] = img[1][p] * scale[1];
    for (int c = 2; c < 6; c++) {
        const float m = (img[c][5] + img[c][6] + img[c][9] + img[c][10]) / 4.0f;  // center 2x2 of the 4x4
        out58[o++] = m * scale[2];
    }
    for (int p = 0; p < 16; p++) out58[o++] = img[6][p] * scale[3];
    for (int p = 0; p < 5; p++) out58[o++] = 0.0f;  // histogram_raw is all zeros for this model
    out58[o++] = (0.0f - 0.5f) * std::sqrt(12.0f) * scale[5];
}

void World::run_latent(const std::vector<TileKey>& keys, bool init, Plan& plan) {
    constexpr int S = kLatentSize;
    constexpr size_t plane = (size_t)S * S, tile_elems = kLatentCh * plane;
    const float t = init ? std::atan(kSigmaMax / kSigmaData) : std::atan(0.35f / kSigmaData);
    const float ct = std::cos(t), st = std::sin(t);
    const uint32_t stream = init ? kStreamLatentInit : kStreamLatentStep;

    for (size_t b0 = 0; b0 < keys.size(); b0 += kLatentBatch) {
        const int n = (int)std::min<size_t>(kLatentBatch, keys.size() - b0);
        std::vector<float> x_t((size_t)n * tile_elems), model_in(x_t.size()), out(x_t.size());
        std::vector<float> cond((size_t)n * 58), noise(tile_elems), prev(tile_elems);
        for (int b = 0; b < n; b++) {
            const TileKey& k = keys[b0 + b];
            latent_conditioning(k, plan, &cond[(size_t)b * 58]);
            const int64_t i0 = k.i * kLatentStride, j0 = k.j * kLatentStride;
            if (init) std::fill(prev.begin(), prev.end(), 0.0f);
            else blend(plan.init, S, kLatentStride, win64_, 0, kLatentCh, i0, j0, S, S, prev.data());
            gaussian_patch(noise.data(), params_.seed, stream, kLatentCh, i0, j0, S, S);
            float* xt = &x_t[(size_t)b * tile_elems];
            float* mi = &model_in[(size_t)b * tile_elems];
            for (size_t p = 0; p < tile_elems; p++) {
                xt[p] = ct * prev[p] * kSigmaData + st * noise[p] * kSigmaData;
                mi[p] = xt[p] / kSigmaData;
            }
        }
        std::vector<float> labels(n, t), emb((size_t)n * m_.base.emb_channels());
        m_.base.embed(n, labels.data(), {cond.data()}, emb.data());
        m_.base.forward(n, S, S, model_in.data(), emb.data(), out.data());

        for (int b = 0; b < n; b++) {
            auto tile = std::make_shared<Tile>(tile_elems);
            const float* xt = &x_t[(size_t)b * tile_elems];
            const float* raw = &out[(size_t)b * tile_elems];
            for (size_t p = 0; p < tile_elems; p++) (*tile)[p] = (ct * xt[p] + st * kSigmaData * raw[p]) / kSigmaData;
            const TileKey& k = keys[b0 + b];
            if (init) {
                plan.init[k] = tile;
                init_cache_.put(k, tile);
            } else {
                plan.step[k] = tile;
                step_cache_.put(k, tile);
            }
        }
    }
}

void World::run_decoder(const std::vector<TileKey>& keys, Plan& plan) {
    constexpr int S = kDecoderSize;
    constexpr size_t plane = (size_t)S * S;
    const int lc = m_.cfg.latent_compression;
    const int ls = S / lc;
    const float t = std::atan(kSigmaMax / kSigmaData), ct = std::cos(t), st = std::sin(t);

    for (size_t b0 = 0; b0 < keys.size(); b0 += kDecoderBatch) {
        const int n = (int)std::min<size_t>(kDecoderBatch, keys.size() - b0);
        std::vector<float> x_t((size_t)n * plane), model_in((size_t)n * 5 * plane), out((size_t)n * plane);
        std::vector<float> lat((size_t)4 * ls * ls);
        for (int b = 0; b < n; b++) {
            const TileKey& k = keys[b0 + b];
            const int64_t i0 = k.i * kDecoderStride, j0 = k.j * kDecoderStride;
            blend(plan.step, kLatentSize, kLatentStride, win64_, 0, 4, i0 / lc, j0 / lc, ls, ls, lat.data());
            float* xt = &x_t[(size_t)b * plane];
            gaussian_patch(xt, params_.seed, kStreamDecoder, 1, i0, j0, S, S);
            float* mi = &model_in[(size_t)b * 5 * plane];
            for (size_t p = 0; p < plane; p++) {
                xt[p] *= st * kSigmaData;
                mi[p] = xt[p] / kSigmaData;
            }
            for (int c = 0; c < 4; c++)
                for (int r = 0; r < S; r++) {
                    const float* src = &lat[((size_t)c * ls + r / lc) * ls];
                    float* dst = &mi[(size_t)(1 + c) * plane + (size_t)r * S];
                    for (int q = 0; q < S; q++) dst[q] = src[q / lc];
                }
        }
        std::vector<float> labels(n, t), emb((size_t)n * m_.decoder.emb_channels());
        m_.decoder.embed(n, labels.data(), {}, emb.data());
        m_.decoder.forward(n, S, S, model_in.data(), emb.data(), out.data());
        for (int b = 0; b < n; b++) {
            auto tile = std::make_shared<Tile>(plane);
            const float* xt = &x_t[(size_t)b * plane];
            const float* raw = &out[(size_t)b * plane];
            for (size_t p = 0; p < plane; p++) (*tile)[p] = (ct * xt[p] + st * kSigmaData * raw[p]) / kSigmaData;
            plan.decoder[keys[b0 + b]] = tile;
            decoder_cache_.put(keys[b0 + b], tile);
        }
    }
}

RegionInputs World::gather_region(int64_t rx, int64_t rz, const Plan& plan) const {
    RegionInputs in;
    in.rx = rx;
    in.rz = rz;
    in.win = region_window(rx, rz);
    const NativeWindow& w = in.win;
    const int lc = m_.cfg.latent_compression;

    in.residual = Raster((int)(w.pi1 - w.pi0), (int)(w.pj1 - w.pj0));
    blend(plan.decoder, kDecoderSize, kDecoderStride, win256_, 0, 1, w.pi0, w.pj0, in.residual.h, in.residual.w,
          in.residual.v.data());
    for (float& v : in.residual.v) v = v * m_.cfg.residual_std + m_.cfg.residual_mean;

    in.lowfreq = Raster(in.residual.h / lc, in.residual.w / lc);
    blend(plan.step, kLatentSize, kLatentStride, win64_, 4, 1, w.pi0 / lc, w.pj0 / lc, in.lowfreq.h, in.lowfreq.w,
          in.lowfreq.v.data());
    for (float& v : in.lowfreq.v) v = v * kLowfreqStd + kLowfreqMean;

    const int ch = (int)(w.ci1 - w.ci0), cw = (int)(w.cj1 - w.cj0);
    std::vector<float> coarse((size_t)kCoarseCh * ch * cw);
    blend(plan.coarse, kCoarseSize, kCoarseStride, win64_, 0, kCoarseCh, w.ci0, w.cj0, ch, cw, coarse.data());
    for (int c = 0; c < kCoarseCh; c++) {
        in.coarse[c] = Raster(ch, cw);
        std::copy_n(&coarse[(size_t)c * ch * cw], (size_t)ch * cw, in.coarse[c].v.begin());
    }
    return in;
}

void World::finalize_region(const RegionInputs& in, uint8_t* out) const {
    const NativeWindow& w = in.win;
    const int s = params_.scale;
    const float block_m = m_.cfg.native_resolution / s;  // meters per block
    constexpr int kBorder = 2, G = kRegionSize + 2 * kBorder;
    const int64_t bz0 = in.rz * kRegionSize - kBorder, bx0 = in.rx * kRegionSize - kBorder;

    // Elevation in meters on the native window.
    const Raster dec = laplacian_decode_denoised(in.residual, in.lowfreq, kLaplacianSigma);
    Raster elev((int)(w.ni1 - w.ni0), (int)(w.nj1 - w.nj0));
    for (int r = 0; r < elev.h; r++)
        for (int c = 0; c < elev.w; c++) {
            const float e = dec.at((int)(w.ni0 - w.pi0) + r, (int)(w.nj0 - w.pj0) + c);
            elev.at(r, c) = std::copysign(e * e, e);
        }

    // Bilinear upsample to blocks (pixel centers: native u = (b + 0.5) / s - 0.5).
    Raster smooth(G, G);
    std::vector<float> uz(G), ux(G);
    for (int k = 0; k < G; k++) {
        uz[k] = (float)((bz0 + k + 0.5) / s - 0.5 - (double)w.ni0);
        ux[k] = (float)((bx0 + k + 0.5) / s - 0.5 - (double)w.nj0);
    }
    for (int r = 0; r < G; r++)
        for (int c = 0; c < G; c++) smooth.at(r, c) = elev.sample(uz[r], ux[c]);

    auto sobel = [&](int r, int c) {
        const float dx = (smooth.at(r - 1, c + 1) + 2 * smooth.at(r, c + 1) + smooth.at(r + 1, c + 1) - smooth.at(r - 1, c - 1) -
                          2 * smooth.at(r, c - 1) - smooth.at(r + 1, c - 1)) / 8.0f;
        const float dy = (smooth.at(r + 1, c - 1) + 2 * smooth.at(r + 1, c) + smooth.at(r + 1, c + 1) - smooth.at(r - 1, c - 1) -
                          2 * smooth.at(r - 1, c) - smooth.at(r - 1, c + 1)) / 8.0f;
        return std::sqrt(dx * dx + dy * dy);
    };

    // Detail noise on slopes when one native pixel spans several blocks.
    Raster fine(G - 2, G - 2);
    {
        const int seed = (int)(detail_seed_ & 0x7fffffff);
        const FastNoiseLite coarse_n = make_fbm(seed + 99999, 1.0f / 24.0f, 3, 0.5f);
        const FastNoiseLite fine_n = make_fbm(seed + 88888, 1.0f / 6.0f, 2, 0.6f);
        const float rel = block_m / m_.cfg.native_resolution;
        const float norm = 40.0f * rel, amp_c = 100.0f * rel, amp_f = 70.0f * rel;
        for (int r = 1; r < G - 1; r++)
            for (int c = 1; c < G - 1; c++) {
                float e = smooth.at(r, c);
                if (s > 1 && e >= 0.0f) {
                    float sf = std::min(1.0f, sobel(r, c) / norm);
                    sf = sf * sf * std::sqrt(sf);
                    if (sf > 0.0f) {
                        const float nx = (float)(bx0 + c), ny = (float)(bz0 + r);
                        e += (coarse_n.GetNoise(nx, ny) * amp_c + fine_n.GetNoise(nx, ny) * amp_f) * sf;
                    }
                }
                fine.at(r - 1, c - 1) = e;
            }
    }

    std::vector<int> ys((size_t)(G - 2) * (G - 2));
    for (int r = 0; r < G - 2; r++)
        for (int c = 0; c < G - 2; c++) ys[(size_t)r * (G - 2) + c] = to_y(fine.at(r, c));

    auto* heights = reinterpret_cast<int16_t*>(out + kRegionHeightOffset);
    uint8_t* slopes = out + kRegionSlopeOffset;
    for (int r = 0; r < kRegionSize; r++)
        for (int c = 0; c < kRegionSize; c++) {
            auto y = [&](int rr, int cc) { return (float)ys[(size_t)(rr + 1) * (G - 2) + (cc + 1)]; };
            heights[r * kRegionSize + c] = (int16_t)y(r, c);
            const float gx = (y(r, c + 1) - y(r, c - 1)) * 0.5f, gz = (y(r + 1, c) - y(r - 1, c)) * 0.5f;
            slopes[r * kRegionSize + c] = (uint8_t)std::min(255.0f, std::sqrt(gx * gx + gz * gz) * 32.0f);
        }

    const ClimateField climate(in.coarse, w.ci0, w.cj0, params_, block_m);
    uint8_t* biomes = out + kRegionBiomeOffset;
    uint8_t* temps = out + kRegionTempOffset;
    uint8_t* precips = out + kRegionPrecipOffset;
    for (int qz = 0; qz < kRegionQuarts; qz++)
        for (int qx = 0; qx < kRegionQuarts; qx++) {
            const int r = kBorder + qz * 4 + 2, c = kBorder + qx * 4 + 2;  // cell center in `smooth`
            const BiomeInputs bi = climate.inputs((double)(bx0 + c), (double)(bz0 + r), smooth.at(r, c), sobel(r, c) / block_m);
            bool near_ocean = false;
            for (int dz = -3; dz <= 3 && !near_ocean; dz += 3)
                for (int dx = -3; dx <= 3 && !near_ocean; dx += 3) near_ocean = smooth.at(r + dz, c + dx) < 0.0f;
            const float beach_max = std::max(4.0f, 1.5f * block_m);
            const int q = qz * kRegionQuarts + qx;
            biomes[q] = refine_biome(classify_biome(bi), bi, near_ocean, beach_max);
            temps[q] = encode_temp(bi.temp);
            precips[q] = encode_precip(bi.precip);
        }
}

int World::to_y(float m) const {
    const float block_m = m_.cfg.native_resolution / params_.scale;
    const int max_y = params_.min_y + params_.height - 2, min_y = params_.min_y + 1;
    const int surface = params_.sea_level - 1;
    const float sqrt10 = std::sqrt(10.0f);
    const int y = m >= 0.0f ? surface + (int)std::floor(m / block_m) : surface + (int)(-std::sqrt(-m + 10.0f) + sqrt10) - 1;
    return std::clamp(y, min_y, max_y);
}

// ---- ClimateField ----------------------------------------------------------------------------

namespace {
// Climate noise lives in meters, so biome patterns scale with the world instead of staying a fixed
// number of blocks wide. Upstream wavelengths are in blocks at its default 1:2 (15 m/block).
constexpr double kRefBlockM = 15.0;
// Ecotone noise: fine-grained jitter of the class thresholds, so neighbouring biomes interleave
// in patches across a transition band instead of meeting along one contour line.
constexpr double kEcotoneWavelengthM = 800.0;
// fBm(4 octaves) has a standard deviation of ~0.19: ~0.3 degC, ~4 % moisture and ~12 m.
constexpr float kEcoTempC = 1.6f, kEcoMoisture = 0.21f, kEcoElevM = 63.0f;

// Meters scaled to noise units in double precision; float block coordinates lose precision far out.
float noise_at(const FastNoiseLite& n, double xm, double zm, double wavelength_m) {
    return n.GetNoise((float)(xm / wavelength_m), (float)(zm / wavelength_m));
}
}  // namespace

ClimateField::ClimateField(const Raster* coarse, int64_t ci0, int64_t cj0, const WorldParams& params, float block_m)
    : coarse_(coarse), ci0_(ci0), cj0_(cj0), scale_(params.scale), block_m_(block_m) {
    // Lapse-rate regression on the coarse map.
    Raster coarse_elev(coarse[0].h, coarse[0].w);
    for (size_t p = 0; p < coarse_elev.v.size(); p++) {
        const float v = std::max(0.0f, coarse[0].v[p]);
        coarse_elev.v[p] = v * v;
    }
    local_baseline_temperature(coarse[2], coarse_elev, kClimateWindow, 0.02f, t_sea_, beta_);

    const uint64_t bseed = mix64(params.seed ^ 0xb10e5ULL);
    const int seed_a = (int)(bseed & 0x7fffffff), seed_b = (int)((bseed >> 32) & 0x7fffffff);
    temp_n_ = make_fbm(seed_a, 1.0f, 3, 0.5f);
    temp_fine_n_ = make_fbm(seed_b, 1.0f, 2, 0.5f);
    precip_n_ = make_fbm(seed_a, 1.0f, 5, 0.5f);
    const uint64_t eseed = mix64(bseed ^ 0xec0703eULL);
    eco_temp_n_ = make_fbm((int)(eseed & 0x7fffffff), 1.0f, 4, 0.5f);
    eco_moist_n_ = make_fbm((int)((eseed >> 32) & 0x7fffffff), 1.0f, 4, 0.5f);
    eco_elev_n_ = make_fbm((int)(mix64(eseed) & 0x7fffffff), 1.0f, 4, 0.5f);
}

BiomeInputs ClimateField::inputs(double bx, double bz, float elev, float slope, bool ecotones) const {
    const int s = scale_, half = kClimateWindow / 2;  // t_sea/beta index 0 <-> coarse px ci0 + half
    const double ni = (bz + 0.5) / s - 0.5, nj = (bx + 0.5) / s - 0.5;
    const float yi = (float)((ni + 0.5) / kNativePerCoarse - 0.5 - (double)(ci0_ + half));
    const float yj = (float)((nj + 0.5) / kNativePerCoarse - 0.5 - (double)(cj0_ + half));
    const double xm = bx * block_m_, zm = bz * block_m_;

    const float tc = noise_at(temp_n_, xm, zm, 500 * kRefBlockM), tf = noise_at(temp_fine_n_, xm, zm, 128 * kRefBlockM);
    const float eco_t = ecotones ? noise_at(eco_temp_n_, xm, zm, kEcotoneWavelengthM) : 0.0f;
    const float eco_m = ecotones ? noise_at(eco_moist_n_, xm, zm, kEcotoneWavelengthM) : 0.0f;
    const float eco_e = ecotones ? noise_at(eco_elev_n_, xm, zm, kEcotoneWavelengthM) : 0.0f;

    BiomeInputs bi{};
    bi.elev = elev;
    bi.band_elev = bi.elev + kEcoElevM * eco_e;
    bi.slope = slope;
    const float b = beta_.sample_cubic(yi, yj);
    bi.temp = t_sea_.sample_cubic(yi, yj) + b * std::max(0.0f, bi.elev) + 0.4f * tc + 0.2f * tf + kEcoTempC * eco_t;
    bi.temp_std = coarse_[3].sample_cubic(yi + half, yj + half);
    bi.precip = std::max(0.0f, coarse_[4].sample_cubic(yi + half, yj + half)) *
                std::max(0.0f, 1.0f + 0.2f * noise_at(precip_n_, xm, zm, 500 * kRefBlockM) + kEcoMoisture * eco_m);
    bi.precip_cv = coarse_[5].sample_cubic(yi + half, yj + half);
    bi.snow_noise = 3.0f * tc + 2.0f * tf;
    return bi;
}

// ---- Map grids -------------------------------------------------------------------------------

void World::grid_windows(const GridRequest& req, int64_t& li0, int64_t& li1, int64_t& lj0, int64_t& lj1, int64_t& ci0,
                         int64_t& ci1, int64_t& cj0, int64_t& cj1) const {
    const double s = params_.scale, lc = m_.cfg.latent_compression;
    // Native coordinates of the outermost samples, including the one-pixel slope border.
    const double u0 = (req.x0 - 0.5 * req.step) / s - 0.5, u1 = (req.x0 + (req.w + 0.5) * req.step) / s - 0.5;
    const double v0 = (req.z0 - 0.5 * req.step) / s - 0.5, v1 = (req.z0 + (req.h + 0.5) * req.step) / s - 0.5;
    auto lat = [&](double u) { return (int64_t)std::floor((u + 0.5) / lc - 0.5); };
    auto crs = [&](double u) { return (int64_t)std::floor((u + 0.5) / kNativePerCoarse - 0.5); };
    li0 = lat(v0) - 1;
    li1 = lat(v1) + 3;
    lj0 = lat(u0) - 1;
    lj1 = lat(u1) + 3;
    const int half = kClimateWindow / 2;
    ci0 = crs(v0) - 2 - half;
    ci1 = crs(v1) + 3 + half;
    cj0 = crs(u0) - 2 - half;
    cj1 = crs(u1) + 3 + half;
}

void World::plan_grid(const GridRequest& req, Plan& plan) {
    if (req.w <= 0 || req.h <= 0 || req.w > 4096 || req.h > 4096 || !(req.step > 0)) throw std::invalid_argument("bad grid size");
    int64_t li0, li1, lj0, lj1, ci0, ci1, cj0, cj1;
    grid_windows(req, li0, li1, lj0, lj1, ci0, ci1, cj0, cj1);
    if (ci1 - ci0 > 1024 || cj1 - cj0 > 1024) throw std::invalid_argument("grid spans too much of the coarse map");
    if (req.source == GridSource::Latent) {
        if (li1 - li0 > 2048 || lj1 - lj0 > 2048) throw std::invalid_argument("grid spans too many latent tiles");
        need_latents(li0, li1, lj0, lj1, plan);
    }
    need_coarse_px(ci0, ci1, cj0, cj1, plan);
}

GridInputs World::gather_grid(const GridRequest& req, const Plan& plan) const {
    GridInputs in;
    in.req = req;
    int64_t li1, lj1, ci1, cj1;
    grid_windows(req, in.li0, li1, in.lj0, lj1, in.ci0, ci1, in.cj0, cj1);
    if (req.source == GridSource::Latent) {
        in.lowfreq = Raster((int)(li1 - in.li0), (int)(lj1 - in.lj0));
        blend(plan.step, kLatentSize, kLatentStride, win64_, 4, 1, in.li0, in.lj0, in.lowfreq.h, in.lowfreq.w,
              in.lowfreq.v.data());
        for (float& v : in.lowfreq.v) v = v * kLowfreqStd + kLowfreqMean;
    }
    const int ch = (int)(ci1 - in.ci0), cw = (int)(cj1 - in.cj0);
    std::vector<float> coarse((size_t)kCoarseCh * ch * cw);
    blend(plan.coarse, kCoarseSize, kCoarseStride, win64_, 0, kCoarseCh, in.ci0, in.cj0, ch, cw, coarse.data());
    for (int c = 0; c < kCoarseCh; c++) {
        in.coarse[c] = Raster(ch, cw);
        std::copy_n(&coarse[(size_t)c * ch * cw], (size_t)ch * cw, in.coarse[c].v.begin());
    }
    return in;
}

void World::finalize_grid(const GridInputs& in, uint8_t* out) const {
    const GridRequest& g = in.req;
    const double s = params_.scale, lc = m_.cfg.latent_compression;
    const float block_m = m_.cfg.native_resolution / params_.scale;
    const int W = g.w + 2, H = g.h + 2;

    // Elevation (meters) at every pixel center plus a one-pixel border for slopes.
    Raster elev(H, W);
    for (int r = 0; r < H; r++) {
        const double v = (g.z0 + (r - 0.5) * g.step) / s - 0.5;
        for (int c = 0; c < W; c++) {
            const double u = (g.x0 + (c - 0.5) * g.step) / s - 0.5;
            float e;
            if (g.source == GridSource::Latent) {
                e = in.lowfreq.sample((float)((v + 0.5) / lc - 0.5 - (double)in.li0), (float)((u + 0.5) / lc - 0.5 - (double)in.lj0));
            } else {
                e = in.coarse[0].sample_cubic((float)((v + 0.5) / kNativePerCoarse - 0.5 - (double)in.ci0),
                                              (float)((u + 0.5) / kNativePerCoarse - 0.5 - (double)in.cj0));
            }
            elev.at(r, c) = std::copysign(e * e, e);
        }
    }

    const ClimateField climate(in.coarse, in.ci0, in.cj0, params_, block_m);
    const float spacing_m = (float)(g.step * block_m);
    const bool ecotones = spacing_m < 400.0f;
    const float beach_max = std::max(4.0f, 1.5f * block_m);
    const size_t n = (size_t)g.w * g.h;
    auto* heights = reinterpret_cast<int16_t*>(out);
    uint8_t* biomes = out + n * 2;
    uint8_t* temps = biomes + n;
    uint8_t* precips = temps + n;
    for (int r = 0; r < g.h; r++) {
        const double bz = g.z0 + (r + 0.5) * g.step - 0.5;
        for (int c = 0; c < g.w; c++) {
            const double bx = g.x0 + (c + 0.5) * g.step - 0.5;
            const float e = elev.at(r + 1, c + 1);
            const float gx = (elev.at(r + 1, c + 2) - elev.at(r + 1, c)) / (2 * spacing_m);
            const float gz = (elev.at(r + 2, c + 1) - elev.at(r, c + 1)) / (2 * spacing_m);
            const BiomeInputs bi = climate.inputs(bx, bz, e, std::sqrt(gx * gx + gz * gz), ecotones);
            const bool near_ocean = elev.at(r, c + 1) < 0.0f || elev.at(r + 2, c + 1) < 0.0f || elev.at(r + 1, c) < 0.0f ||
                                    elev.at(r + 1, c + 2) < 0.0f;
            const size_t o = (size_t)r * g.w + c;
            heights[o] = (int16_t)to_y(e);
            biomes[o] = refine_biome(classify_biome(bi), bi, near_ocean, beach_max);
            temps[o] = encode_temp(bi.temp);
            precips[o] = encode_precip(bi.precip);
        }
    }
}

void World::find_land(int64_t& bx, int64_t& bz) {
    constexpr int R = 24;  // coarse px search radius (~6000 native px)
    Plan plan;
    need_coarse_px(-R - 1, R + 2, -R - 1, R + 2, plan);
    execute(plan);
    const int n = 2 * R + 3;
    std::vector<float> c0((size_t)kCoarseCh * n * n);
    blend(plan.coarse, kCoarseSize, kCoarseStride, win64_, 0, 1, -R - 1, -R - 1, n, n, c0.data());
    auto at = [&](int i, int j) { return c0[(size_t)(i + R + 1) * n + (j + R + 1)]; };
    int64_t best = -1;
    bx = bz = 0;
    for (int i = -R; i <= R; i++)
        for (int j = -R; j <= R; j++) {
            bool land = at(i, j) > 4.0f;  // signed-sqrt meters: > 16 m
            for (int di = -1; di <= 1 && land; di++)
                for (int dj = -1; dj <= 1 && land; dj++) land = at(i + di, j + dj) > 0.0f;
            const int64_t d = (int64_t)i * i + (int64_t)j * j;
            if (land && (best < 0 || d < best)) {
                best = d;
                bz = (int64_t)std::llround((i + 0.5) * kNativePerCoarse * params_.scale);
                bx = (int64_t)std::llround((j + 0.5) * kNativePerCoarse * params_.scale);
            }
        }
    if (best < 0) log_warn("no land found near the origin on the coarse map");
}

}  // namespace fd
