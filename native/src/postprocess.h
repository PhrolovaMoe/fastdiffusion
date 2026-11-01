// CPU post-processing: Laplacian elevation decode, climate, biome rules.
#pragma once

#include <cstdint>
#include <vector>

namespace fd {

struct Raster {
    int h = 0, w = 0;
    std::vector<float> v;
    Raster() = default;
    Raster(int h_, int w_, float fill = 0.0f) : h(h_), w(w_), v((size_t)h_ * w_, fill) {}
    float& at(int r, int c) { return v[(size_t)r * w + c]; }
    float at(int r, int c) const { return v[(size_t)r * w + c]; }
    // Bilinear sample at fractional (pixel-center) coordinates, clamped to the border.
    float sample(float y, float x) const;
    // Catmull-Rom sample; smooth across pixel boundaries, so contours have no grid-aligned kinks.
    float sample_cubic(float y, float x) const;
};

// Bilinear resize, align_corners=False (PyTorch semantics).
Raster bilinear_resize(const Raster& src, int h, int w);

// laplacian_denoise + laplacian_decode of terrain_diffusion.data.laplacian_encoder.
// residual: full-resolution residual; lowres: low-frequency map at 1/lc resolution.
// Returns the decoded map (signed-sqrt elevation) at full resolution.
Raster laplacian_decode_denoised(const Raster& residual, const Raster& lowres, float sigma);

// Windowed land-weighted regression of temperature on elevation (lapse rate).
// Output rasters are (h - win + 1) x (w - win + 1): sea-level temperature and beta (degC per m).
void local_baseline_temperature(const Raster& temp, const Raster& elev, int win, float fallback_threshold,
                                Raster& t_sea, Raster& beta);

// Biome ids shared with the Java side (FastDiffusionBiomes.java).
enum Biome : uint8_t {
    PLAINS = 0, SNOWY_PLAINS, DESERT, SWAMP, FOREST, TAIGA, SNOWY_TAIGA, SAVANNA, WINDSWEPT_HILLS, JUNGLE,
    BADLANDS, MEADOW, GROVE, SNOWY_SLOPES, FROZEN_PEAKS, STONY_PEAKS, WARM_OCEAN, OCEAN, COLD_OCEAN,
    FROZEN_OCEAN, FOREST_SPARSE, TAIGA_SPARSE, SNOWY_TAIGA_SPARSE, DEEP_OCEAN, DEEP_COLD_OCEAN, DEEP_FROZEN_OCEAN,
    DEEP_LUKEWARM_OCEAN, BEACH, SNOWY_BEACH, STONY_SHORE, BIOME_COUNT
};

struct BiomeInputs {
    float elev;        // meters (smooth)
    float band_elev;   // meters, perturbed; selects the lowland/mountain bands
    float slope;       // rise over run
    float temp;        // degC, already perturbed by noise
    float temp_std;    // seasonal temperature std * 100
    float precip;      // mm/year, already perturbed by noise
    float precip_cv;   // precipitation coefficient of variation
    float snow_noise;  // additive snow temperature noise
};

Biome classify_biome(const BiomeInputs& in);
bool has_snow(const BiomeInputs& in);
bool is_ocean(Biome b);

// Deep-ocean variants for the abyss and beach/shore variants for low land next to the sea.
Biome refine_biome(Biome b, const BiomeInputs& in, bool near_ocean, float beach_max_elev);

}  // namespace fd
