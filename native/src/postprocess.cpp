#include "postprocess.h"

#include <algorithm>
#include <cmath>

namespace fd {

float Raster::sample(float y, float x) const {
    y = std::clamp(y, 0.0f, (float)(h - 1));
    x = std::clamp(x, 0.0f, (float)(w - 1));
    const int y0 = (int)y, x0 = (int)x;
    const int y1 = std::min(h - 1, y0 + 1), x1 = std::min(w - 1, x0 + 1);
    const float wy = y - y0, wx = x - x0;
    return (1 - wy) * ((1 - wx) * at(y0, x0) + wx * at(y0, x1)) + wy * ((1 - wx) * at(y1, x0) + wx * at(y1, x1));
}

float Raster::sample_cubic(float y, float x) const {
    const int y0 = (int)std::floor(y), x0 = (int)std::floor(x);
    const float ty = y - y0, tx = x - x0;
    auto weights = [](float t, float* w) {
        const float t2 = t * t, t3 = t2 * t;
        w[0] = 0.5f * (-t3 + 2 * t2 - t);
        w[1] = 0.5f * (3 * t3 - 5 * t2 + 2);
        w[2] = 0.5f * (-3 * t3 + 4 * t2 + t);
        w[3] = 0.5f * (t3 - t2);
    };
    float wy[4], wx[4];
    weights(ty, wy);
    weights(tx, wx);
    float s = 0;
    for (int i = 0; i < 4; i++) {
        const int r = std::clamp(y0 - 1 + i, 0, h - 1);
        float row = 0;
        for (int j = 0; j < 4; j++) row += wx[j] * at(r, std::clamp(x0 - 1 + j, 0, w - 1));
        s += wy[i] * row;
    }
    return s;
}

Raster bilinear_resize(const Raster& src, int h, int w) {
    Raster dst(h, w);
    std::vector<int> c0(w), c1(w);
    std::vector<float> wc(w);
    for (int c = 0; c < w; c++) {
        const float sc = (c + 0.5f) * src.w / w - 0.5f;
        const int f = (int)std::floor(sc);
        wc[c] = sc - f;
        c0[c] = std::clamp(f, 0, src.w - 1);
        c1[c] = std::clamp(f + 1, 0, src.w - 1);
    }
    for (int r = 0; r < h; r++) {
        const float sr = (r + 0.5f) * src.h / h - 0.5f;
        const int f = (int)std::floor(sr);
        const float wr = sr - f;
        const int r0 = std::clamp(f, 0, src.h - 1), r1 = std::clamp(f + 1, 0, src.h - 1);
        const float* a = &src.v[(size_t)r0 * src.w];
        const float* b = &src.v[(size_t)r1 * src.w];
        float* out = &dst.v[(size_t)r * w];
        for (int c = 0; c < w; c++) {
            const float top = a[c0[c]] + wc[c] * (a[c1[c]] - a[c0[c]]);
            const float bot = b[c0[c]] + wc[c] * (b[c1[c]] - b[c0[c]]);
            out[c] = top + wr * (bot - top);
        }
    }
    return dst;
}

namespace {

// Pads by one pixel using linear extrapolation, resizes, then crops back (extrapolate=True).
Raster bilinear_resize_extrapolated(const Raster& src, int h, int w) {
    const int sh = src.h, sw = src.w;
    Raster p(sh + 2, sw + 2);
    for (int r = 0; r < sh; r++)
        for (int c = 0; c < sw; c++) p.at(r + 1, c + 1) = src.at(r, c);
    for (int c = 1; c <= sw; c++) {
        p.at(0, c) = sh > 1 ? 2 * src.at(0, c - 1) - src.at(1, c - 1) : src.at(0, c - 1);
        p.at(sh + 1, c) = sh > 1 ? 2 * src.at(sh - 1, c - 1) - src.at(sh - 2, c - 1) : src.at(sh - 1, c - 1);
    }
    for (int r = 0; r < sh + 2; r++) {
        p.at(r, 0) = sw > 1 ? 2 * p.at(r, 1) - p.at(r, 2) : p.at(r, 1);
        p.at(r, sw + 1) = sw > 1 ? 2 * p.at(r, sw) - p.at(r, sw - 1) : p.at(r, sw);
    }
    const int nh = (int)std::lround(h + 2.0 * h / sh);
    const int nw = (int)std::lround(w + 2.0 * w / sw);
    Raster resized = bilinear_resize(p, nh, nw);
    const int ph = (int)std::lround((double)h / sh), pw = (int)std::lround((double)w / sw);
    Raster out(h, w);
    for (int r = 0; r < h; r++)
        for (int c = 0; c < w; c++) out.at(r, c) = resized.at(r + ph, c + pw);
    return out;
}

// Separable Gaussian blur with clamped borders (kernel size as torchvision gaussian_blur).
Raster gaussian_blur(const Raster& src, float sigma) {
    const int ks = ((int)(sigma * 2) / 2) * 2 + 1, half = ks / 2;
    std::vector<float> k(ks);
    float sum = 0;
    for (int i = 0; i < ks; i++) {
        const float x = (float)(i - half);
        k[i] = std::exp(-0.5f * x * x / (sigma * sigma));
        sum += k[i];
    }
    for (float& v : k) v /= sum;
    Raster tmp(src.h, src.w), out(src.h, src.w);
    for (int r = 0; r < src.h; r++)
        for (int c = 0; c < src.w; c++) {
            float s = 0;
            for (int i = 0; i < ks; i++) s += src.at(r, std::clamp(c + i - half, 0, src.w - 1)) * k[i];
            tmp.at(r, c) = s;
        }
    for (int r = 0; r < src.h; r++)
        for (int c = 0; c < src.w; c++) {
            float s = 0;
            for (int i = 0; i < ks; i++) s += tmp.at(std::clamp(r + i - half, 0, src.h - 1), c) * k[i];
            out.at(r, c) = s;
        }
    return out;
}

}  // namespace

Raster laplacian_decode_denoised(const Raster& residual, const Raster& lowres, float sigma) {
    // Denoise: decode with extrapolation, re-encode the low band and blur it.
    Raster up = bilinear_resize_extrapolated(lowres, residual.h, residual.w);
    for (size_t i = 0; i < up.v.size(); i++) up.v[i] += residual.v[i];
    Raster new_low = gaussian_blur(bilinear_resize(up, lowres.h, lowres.w), sigma);
    // Decode with the denoised low band.
    Raster out = bilinear_resize(new_low, residual.h, residual.w);
    for (size_t i = 0; i < out.v.size(); i++) out.v[i] += residual.v[i];
    return out;
}

void local_baseline_temperature(const Raster& temp, const Raster& elev, int win, float fallback_threshold,
                                Raster& t_sea, Raster& beta) {
    const int oh = temp.h - win + 1, ow = temp.w - win + 1;
    t_sea = Raster(oh, ow);
    beta = Raster(oh, ow);
    const double fallback_beta = -0.0065, beta_min = -0.012, beta_max = 0.0, eps = 1e-6;
    const int n = win * win, pad = (win - 1) / 2;
    for (int r = 0; r < oh; r++) {
        for (int c = 0; c < ow; c++) {
            double mt = 0, me = 0, me2 = 0, met = 0, sw = 0;
            for (int dr = 0; dr < win; dr++)
                for (int dc = 0; dc < win; dc++) {
                    const double e = elev.at(r + dr, c + dc), t = temp.at(r + dr, c + dc);
                    if (e <= 0) continue;
                    mt += t;
                    me += e;
                    me2 += e * e;
                    met += e * t;
                    sw += 1;
                }
            const double den = sw + eps;
            mt /= den;
            me /= den;
            me2 /= den;
            met /= den;
            const double var_e = me2 - me * me, cov = met - me * mt;
            double b = (var_e < 1.0 || sw < fallback_threshold * n) ? fallback_beta : cov / (var_e + eps);
            b = std::clamp(b, beta_min, beta_max);
            t_sea.at(r, c) = (float)(temp.at(r + pad, c + pad) - b * elev.at(r + pad, c + pad));
            beta.at(r, c) = (float)b;
        }
    }
}

Biome classify_biome(const BiomeInputs& in) {
    const float alt = std::max(0.0f, in.band_elev);
    const float temp = in.temp, precip = in.precip, slope = in.slope;

    const float t_std = in.temp_std / 100.0f;
    const float t_eff = std::max(0.0f, temp + 0.5f * t_std);
    const float pet = std::max(250.0f, 250.0f + 25.0f * t_eff + 0.7f * t_eff * t_eff);
    const float aridity = precip / std::max(1.0f, pet);
    const float season_penalty = 1.0f - 0.35f * std::min(1.0f, in.precip_cv / 100.0f);
    const float tree_moisture = aridity * season_penalty;

    const float amplitude = t_std * 1.414f;
    float growing_season;
    if (amplitude < 0.1f) {
        growing_season = temp > 5.0f ? 365.0f : 0.0f;
    } else {
        const float x = (5.0f - temp) / amplitude;
        if (x <= -1.0f) growing_season = 365.0f;
        else if (x >= 1.0f) growing_season = 0.0f;
        else growing_season = 365.0f * (0.5f - std::asin(std::clamp(x, -1.0f, 1.0f)) / (float)M_PI);
    }
    const float gs_factor = std::clamp((growing_season - 60.0f) / 90.0f, 0.0f, 1.0f);
    const float eff_tree_moisture = tree_moisture * gs_factor;

    const float moisture_factor = std::clamp((tree_moisture - 0.35f) / 0.45f, 0.0f, 1.0f);
    const float bare_threshold = 0.7f + (1.19f - 0.7f) * moisture_factor;

    bool trees_none = eff_tree_moisture < 0.2f;
    const bool barren = tree_moisture < 0.05f || growing_season < 60.0f;
    bool trees_sparse = !trees_none && eff_tree_moisture < 0.5f;
    bool trees_forest = !trees_none && eff_tree_moisture >= 0.5f && eff_tree_moisture < 0.8f;
    bool trees_dense = !trees_none && eff_tree_moisture >= 0.8f && eff_tree_moisture < 1.3f;
    bool trees_rainforest = !trees_none && eff_tree_moisture >= 1.3f;

    const bool slope_medium = slope >= 0.62f && slope < bare_threshold;
    const bool slope_bare = slope >= bare_threshold;
    if (slope_medium) {
        if (trees_forest || trees_dense || trees_rainforest) trees_sparse = true;
        trees_forest = trees_dense = trees_rainforest = false;
    }
    if (slope_bare) {
        trees_none = true;
        trees_sparse = trees_forest = trees_dense = trees_rainforest = false;
    }

    const bool has_snow = temp + in.snow_noise < 0.0f && precip > 150.0f && slope <= 0.78f;
    const bool ocean = in.elev < 0.0f, mountains = alt > 2500.0f, lowland = alt < 200.0f;
    const bool frozen = temp < -5.0f, cold = temp >= -5.0f && temp < 5.0f, cool = temp >= 5.0f && temp < 12.0f;
    const bool temperate = temp >= 12.0f && temp < 20.0f, warm = temp >= 20.0f && temp < 26.0f, hot = temp >= 26.0f;

    Biome b = PLAINS;
    if (ocean) {
        b = frozen ? FROZEN_OCEAN : cold ? COLD_OCEAN : (warm || hot) ? WARM_OCEAN : OCEAN;
    } else if (mountains) {
        if (slope_bare) b = has_snow ? FROZEN_PEAKS : STONY_PEAKS;
        else if (has_snow) b = trees_none ? SNOWY_SLOPES : (trees_sparse || trees_forest) ? SNOWY_TAIGA_SPARSE : SNOWY_TAIGA;
        else if (trees_none) b = barren ? WINDSWEPT_HILLS : (tree_moisture < 0.35f || precip < 350.0f) ? GROVE : PLAINS;
        else b = (trees_sparse || trees_forest) ? TAIGA_SPARSE : TAIGA;
    } else {
        if (has_snow && trees_none) b = SNOWY_PLAINS;
        else if (has_snow) b = (trees_sparse || trees_forest) ? SNOWY_TAIGA_SPARSE : SNOWY_TAIGA;
        else if (trees_none) {
            if (warm || hot) b = DESERT;
            else if (barren && !lowland && (cold || cool || temperate)) b = GROVE;
            else if (tree_moisture < 0.35f || precip < 350.0f) b = GROVE;
            else b = PLAINS;
        } else if (trees_sparse || trees_forest) {
            if (hot) b = JUNGLE;
            else if (warm && trees_sparse && !slope_medium) b = SAVANNA;
            else if (warm && trees_forest) b = FOREST_SPARSE;
            else if (temperate) b = FOREST_SPARSE;
            else b = TAIGA_SPARSE;
        } else if (trees_dense) {
            b = hot ? JUNGLE : (warm && lowland) ? SWAMP : (cool || cold) ? TAIGA : FOREST;
        } else {
            if (hot || (warm && temp >= 18.0f && t_std < 5.0f)) b = JUNGLE;
            else b = lowland ? SWAMP : (cool || cold) ? TAIGA : FOREST;
        }
    }
    if (slope_bare && !ocean && !mountains) b = has_snow ? FROZEN_PEAKS : STONY_PEAKS;
    // Cool upland grassland: gentle, moist ground below the alpine band. Preserve dense
    // forests, dry groves, snow, and exposed peaks; the same rule serves detail and map grids.
    const bool open_ground = b == PLAINS || b == FOREST_SPARSE || b == TAIGA_SPARSE;
    if (open_ground && !ocean && !has_snow && !barren && alt >= 600.0f && alt < 2500.0f
            && temp >= 5.0f && temp < 18.0f && slope < 0.35f && tree_moisture >= 0.35f && tree_moisture < 0.8f)
        b = MEADOW;
    return b;
}

bool has_snow(const BiomeInputs& in) { return in.temp + in.snow_noise < 0.0f && in.precip > 150.0f && in.slope <= 0.78f; }

bool is_ocean(Biome b) {
    return b == OCEAN || b == WARM_OCEAN || b == COLD_OCEAN || b == FROZEN_OCEAN || b == DEEP_OCEAN || b == DEEP_COLD_OCEAN ||
           b == DEEP_FROZEN_OCEAN || b == DEEP_LUKEWARM_OCEAN;
}

Biome refine_biome(Biome b, const BiomeInputs& in, bool near_ocean, float beach_max_elev) {
    constexpr float kDeepOcean = -1000.0f;
    if (in.elev < kDeepOcean) {
        switch (b) {
            case OCEAN: return DEEP_OCEAN;
            case COLD_OCEAN: return DEEP_COLD_OCEAN;
            case FROZEN_OCEAN: return DEEP_FROZEN_OCEAN;
            case WARM_OCEAN: return DEEP_LUKEWARM_OCEAN;
            default: return b;
        }
    }
    if (in.elev >= 0.0f && in.elev < beach_max_elev && near_ocean && !is_ocean(b)) {
        if (b == STONY_PEAKS || b == FROZEN_PEAKS) return STONY_SHORE;
        return has_snow(in) ? SNOWY_BEACH : BEACH;
    }
    return b;
}

}  // namespace fd
