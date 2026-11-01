// Synthetic climate/elevation conditioning map: fractal Perlin noise quantile-matched to
// real WorldClim/ETOPO distributions (port of terrain_diffusion.inference.synthetic_map).
#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "FastNoiseLite.h"

namespace fd {

struct SyntheticMapTables {
    std::vector<std::vector<float>> data_quantiles;  // [5][n]
    std::vector<float> frequency_mult;               // [5]
    float a_temp_std, b_temp_std, temp_std_p1, temp_std_p99;
};

class SyntheticMap {
public:
    static constexpr int kChannels = 5;
    SyntheticMap(uint64_t seed, const SyntheticMapTables& tables);

    // Channels [elev_sqrt, temp, temp_std, precip, precip_std] for rows [i0, i0+h), cols [j0, j0+w),
    // written as out[c][r][col].
    void sample(int64_t i0, int64_t j0, int h, int w, float* out) const;

private:
    std::array<FastNoiseLite, kChannels> noise_;
    std::array<std::vector<float>, kChannels> noise_quantiles_;
    const SyntheticMapTables& tables_;
};

}  // namespace fd
