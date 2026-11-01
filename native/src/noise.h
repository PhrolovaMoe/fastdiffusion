// Counter-based Gaussian noise: a pure function of (seed, stream, channel, i, j).
// Overlapping tiles therefore see identical noise, which keeps tile blending seamless.
#pragma once

#include <cmath>
#include <cstdint>

namespace fd {

inline uint64_t mix64(uint64_t x) {
    x ^= x >> 30;
    x *= 0xbf58476d1ce4e5b9ULL;
    x ^= x >> 27;
    x *= 0x94d049bb133111ebULL;
    x ^= x >> 31;
    return x;
}

inline uint64_t hash_coords(uint64_t seed, uint32_t stream, uint32_t channel, int64_t i, int64_t j) {
    uint64_t h = mix64(seed ^ (0x9e3779b97f4a7c15ULL * (stream + 1)));
    h = mix64(h ^ ((uint64_t)channel * 0xd1b54a32d192ed03ULL));
    h = mix64(h ^ (uint64_t)i);
    h = mix64(h ^ ((uint64_t)j * 0x8cb92ba72f3d8dd7ULL));
    return h;
}

// Standard normal via Box-Muller on two independent 32-bit halves of one hash.
inline float gaussian(uint64_t seed, uint32_t stream, uint32_t channel, int64_t i, int64_t j) {
    const uint64_t h = hash_coords(seed, stream, channel, i, j);
    const double u1 = ((double)(h >> 32) + 1.0) * (1.0 / 4294967297.0);  // (0, 1]
    const double u2 = (double)(h & 0xffffffffULL) * (1.0 / 4294967296.0);
    return (float)(std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * M_PI * u2));
}

// Fills dst[c][r][col] for the window [i0, i0+h) x [j0, j0+w).
inline void gaussian_patch(float* dst, uint64_t seed, uint32_t stream, int channels, int64_t i0, int64_t j0, int h, int w) {
    for (int c = 0; c < channels; c++)
        for (int r = 0; r < h; r++)
            for (int col = 0; col < w; col++)
                *dst++ = gaussian(seed, stream, (uint32_t)c, i0 + r, j0 + col);
}

}  // namespace fd
