#include "synthetic_map.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <thread>

namespace fd {

namespace {

constexpr float kBaseFrequency = 0.05f;
constexpr int kOctaves[SyntheticMap::kChannels] = {4, 2, 4, 4, 4};
constexpr int kNoiseQuantiles = 64;

// Quantiles of the noise itself on a fixed 1024x1024 grid (stride 32), as upstream does.
std::vector<float> build_noise_quantiles(const FastNoiseLite& fnl) {
    std::vector<float> values(1024 * 1024);
    size_t k = 0;
    for (int r = 0; r < 1024; r++)
        for (int c = 0; c < 1024; c++) values[k++] = fnl.GetNoise((float)(c * 32), (float)(r * 32));
    std::sort(values.begin(), values.end());

    const float eps = 1e-4f;
    std::vector<float> q(kNoiseQuantiles);
    const size_t n = values.size();
    for (int i = 0; i < kNoiseQuantiles; i++) {
        const float pct = eps + i * (1.0f - 2 * eps) / (kNoiseQuantiles - 1);
        const float idx = pct * (n - 1);
        const size_t lo = (size_t)idx, hi = std::min(lo + 1, n - 1);
        q[i] = values[lo] + (idx - lo) * (values[hi] - values[lo]);
    }
    float min_diff = std::numeric_limits<float>::max();
    for (int i = 1; i < kNoiseQuantiles; i++)
        if (q[i] > q[i - 1]) min_diff = std::min(min_diff, q[i] - q[i - 1]);
    if (min_diff == std::numeric_limits<float>::max()) min_diff = 1e-10f;
    for (int i = 1; i < kNoiseQuantiles; i++)
        if (q[i] <= q[i - 1]) q[i] = q[i - 1] + min_diff * 0.1f;
    return q;
}

// np.interp with clamping. Both tables have the same length.
float interp(float x, const std::vector<float>& xp, const std::vector<float>& fp) {
    const size_t n = xp.size();
    if (x <= xp[0]) return fp[0];
    if (x >= xp[n - 1]) return fp[n - 1];
    const size_t hi = std::upper_bound(xp.begin(), xp.end(), x) - xp.begin();
    const size_t lo = hi - 1;
    const float t = (x - xp[lo]) / (xp[hi] - xp[lo]);
    return fp[lo] + t * (fp[hi] - fp[lo]);
}

}  // namespace

SyntheticMap::SyntheticMap(uint64_t seed, const SyntheticMapTables& tables) : tables_(tables) {
    std::array<std::thread, kChannels> workers;
    for (int ch = 0; ch < kChannels; ch++) {
        FastNoiseLite& fnl = noise_[ch];
        fnl.SetSeed((int)((seed + ch + 1) & 0x7fffffffULL));
        fnl.SetNoiseType(FastNoiseLite::NoiseType_Perlin);
        fnl.SetFrequency(kBaseFrequency * tables.frequency_mult[ch]);
        fnl.SetFractalType(FastNoiseLite::FractalType_FBm);
        fnl.SetFractalOctaves(kOctaves[ch]);
        fnl.SetFractalLacunarity(2.0f);
        fnl.SetFractalGain(0.5f);
        workers[ch] = std::thread([this, ch] { noise_quantiles_[ch] = build_noise_quantiles(noise_[ch]); });
    }
    for (auto& t : workers) t.join();
}

void SyntheticMap::sample(int64_t i0, int64_t j0, int h, int w, float* out) const {
    const size_t plane = (size_t)h * w;
    const auto& dq = tables_.data_quantiles;
    for (int r = 0; r < h; r++) {
        for (int c = 0; c < w; c++) {
            float v[kChannels];
            for (int ch = 0; ch < kChannels; ch++) {
                const float n = noise_[ch].GetNoise((float)(j0 + c), (float)(i0 + r));
                v[ch] = interp(n, noise_quantiles_[ch], dq[ch]);
            }
            float elev = v[0], temp = v[1], temp_std = v[2], precip = v[3], precip_std = v[4];

            float lapse = std::clamp(-6.5f + 0.0015f * precip, -9.8f, -4.0f) / 1000.0f;
            temp = std::clamp(temp + lapse * std::max(0.0f, elev), -10.0f, 40.0f);

            const float baseline = tables_.a_temp_std * temp + tables_.b_temp_std;
            const float t01 = (temp_std - tables_.temp_std_p1) / (tables_.temp_std_p99 - tables_.temp_std_p1);
            const float baseline_clipped = std::max(tables_.temp_std_p1, -baseline);
            temp_std = std::max(t01 * (tables_.temp_std_p99 - baseline_clipped) + baseline_clipped + baseline, 20.0f);

            precip_std *= std::max(0.0f, (185.0f - 0.04111f * precip) / 185.0f);
            const float elev_sqrt = std::copysign(std::sqrt(std::fabs(elev)), elev);

            const size_t idx = (size_t)r * w + c;
            out[0 * plane + idx] = elev_sqrt;
            out[1 * plane + idx] = temp;
            out[2 * plane + idx] = temp_std;
            out[3 * plane + idx] = precip;
            out[4 * plane + idx] = precip_std;
        }
    }
}

}  // namespace fd
