// Multi-scale terrain pipeline: coarse map -> latents -> decoder residual -> elevation.
//
// Each stage is an infinite plane of overlapping tiles blended with a linear window.
// Tiles are computed lazily on the GPU thread and kept in byte-bounded LRU caches.
// Coordinates: i = row = block Z, j = column = block X.
#pragma once

#include <cstdint>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "engine.h"
#include "postprocess.h"
#include "synthetic_map.h"
#include "unet.h"

namespace fd {

// ---- Region output layout (shared with the Java side) ----------------------------------------
constexpr int kRegionSize = 256;                   // blocks per region side
constexpr int kRegionQuarts = kRegionSize / 4;     // biome cells per side
constexpr size_t kRegionHeightOffset = 0;          // int16 [256][256]: Y of the top solid block
constexpr size_t kRegionSlopeOffset = kRegionSize * kRegionSize * 2;  // uint8 [256][256]: slope * 32
constexpr size_t kRegionBiomeOffset = kRegionSlopeOffset + kRegionSize * kRegionSize;  // uint8 [64][64]
constexpr size_t kRegionTempOffset = kRegionBiomeOffset + kRegionQuarts * kRegionQuarts;    // uint8 [64][64], encode_temp
constexpr size_t kRegionPrecipOffset = kRegionTempOffset + kRegionQuarts * kRegionQuarts;   // uint8 [64][64], encode_precip
constexpr size_t kRegionBytes = kRegionPrecipOffset + kRegionQuarts * kRegionQuarts;

// Climate bytes shared with the Java side and the web map: 0.5 degC steps from -50 degC, 20 mm/year steps.
inline uint8_t encode_temp(float c) {
    const float v = (c + 50.0f) * 2.0f + 0.5f;
    return (uint8_t)(v <= 0.0f ? 0.0f : v >= 255.0f ? 255.0f : v);
}
inline uint8_t encode_precip(float mm) {
    const float v = mm / 20.0f + 0.5f;
    return (uint8_t)(v <= 0.0f ? 0.0f : v >= 255.0f ? 255.0f : v);
}

// ---- Map grids (shared with the Java side) ---------------------------------------------------
// A w x h grid sampled at block-space pixel centers x0 + (j + 0.5) * step, z0 + (i + 0.5) * step.
// Planar output: int16 [h][w] top block Y, then uint8 [h][w] biome, temperature and precipitation.
enum class GridSource : int { Coarse = 0, Latent = 1 };
struct GridRequest {
    double x0 = 0, z0 = 0, step = 1;
    int w = 0, h = 0;
    GridSource source = GridSource::Latent;
};
inline size_t grid_bytes(int w, int h) { return (size_t)w * h * 5; }

struct PipelineConfig {
    std::vector<float> coarse_means, coarse_stds, cond_snr;
    float residual_mean, residual_std, native_resolution;
    int latent_compression;
};

// Model weights and constants shared by all worlds.
struct Models {
    Models(const std::string& path, const EngineOptions& opts);
    Engine engine;
    UNet coarse, base, decoder;
    PipelineConfig cfg;
    SyntheticMapTables synth;
};

struct WorldParams {
    uint64_t seed = 0;
    int scale = 1;       // blocks per native (30 m) pixel
    int min_y = -64;     // dimension bottom
    int height = 384;    // dimension height
    int sea_level = 63;
};

struct TileKey {
    int64_t i, j;
    bool operator==(const TileKey& o) const { return i == o.i && j == o.j; }
};
struct TileKeyHash {
    size_t operator()(const TileKey& k) const { return std::hash<int64_t>()(k.i * 0x9E3779B97F4A7C15LL ^ k.j); }
};

using Tile = std::vector<float>;
using TilePtr = std::shared_ptr<const Tile>;
using TileMap = std::unordered_map<TileKey, TilePtr, TileKeyHash>;
using KeySet = std::unordered_set<TileKey, TileKeyHash>;

class TileCache {
public:
    explicit TileCache(size_t max_bytes) : max_bytes_(max_bytes) {}
    TilePtr get(const TileKey& k);
    void put(const TileKey& k, TilePtr t);
    void clear();

private:
    struct Entry {
        TilePtr tile;
        std::list<TileKey>::iterator lru;
    };
    size_t max_bytes_, bytes_ = 0;
    std::list<TileKey> lru_;
    std::unordered_map<TileKey, Entry, TileKeyHash> map_;
};

// Native-pixel windows a region depends on.
struct NativeWindow {
    int64_t ni0, ni1, nj0, nj1;  // elevation window (native px)
    int64_t pi0, pi1, pj0, pj1;  // padded residual window, multiple of the latent compression
    int64_t ci0, ci1, cj0, cj1;  // coarse window incl. climate regression padding
};

// Everything one region's CPU finalisation needs, extracted on the GPU thread.
struct RegionInputs {
    int64_t rx = 0, rz = 0;
    NativeWindow win{};
    Raster residual;   // blended decoder residual on [pi0, pi1) x [pj0, pj1)
    Raster lowfreq;    // blended latent low-frequency band on the same window / latent compression
    Raster coarse[6];  // blended coarse channels on [ci0, ci1) x [cj0, cj1)
};

// Everything a map grid's CPU finalisation needs.
struct GridInputs {
    GridRequest req;
    int64_t li0 = 0, lj0 = 0;  // latent px of lowfreq(0, 0); unused for coarse grids
    int64_t ci0 = 0, cj0 = 0;  // coarse px of coarse[c](0, 0)
    Raster lowfreq;            // blended latent low-frequency band (signed-sqrt meters)
    Raster coarse[6];
};

// Climate and biome inputs anywhere inside a blended coarse window: the lapse-rate regression,
// climate noise and ecotone jitter shared by regions and map grids.
class ClimateField {
public:
    // coarse: blended coarse channels whose (0, 0) is coarse px (ci0, cj0).
    ClimateField(const Raster* coarse, int64_t ci0, int64_t cj0, const WorldParams& params, float block_m);
    // b: integer-aligned block index (the block's center is b + 0.5); elev in meters; slope rise/run.
    // ecotones: fine threshold jitter; disable when a sample spans far more than its 800 m wavelength.
    BiomeInputs inputs(double bx, double bz, float elev, float slope, bool ecotones = true) const;

private:
    const Raster* coarse_;
    int64_t ci0_, cj0_;
    int scale_;
    float block_m_;
    Raster t_sea_, beta_;
    FastNoiseLite temp_n_, temp_fine_n_, precip_n_, eco_temp_n_, eco_moist_n_, eco_elev_n_;
};

// Tiles a batch of work needs. Built by World::plan, filled by World::execute.
struct Plan {
    TileMap coarse, init, step, decoder;
    KeySet coarse_missing, init_missing, step_missing, decoder_missing;
    size_t missing_gpu_work() const {
        return coarse_missing.size() * 8 + init_missing.size() + step_missing.size() + decoder_missing.size() * 2;
    }
};

class World {
public:
    World(Models& models, const WorldParams& params, size_t cache_bytes);
    const WorldParams& params() const { return params_; }

    // --- GPU thread only --------------------------------------------------------------------
    void plan_region(int64_t rx, int64_t rz, Plan& plan);
    void execute(Plan& plan);
    RegionInputs gather_region(int64_t rx, int64_t rz, const Plan& plan) const;
    // Nearest land to the origin on the coarse map, in block coordinates.
    void find_land(int64_t& bx, int64_t& bz);
    void plan_grid(const GridRequest& req, Plan& plan);
    GridInputs gather_grid(const GridRequest& req, const Plan& plan) const;

    // --- any thread -------------------------------------------------------------------------
    void finalize_region(const RegionInputs& in, uint8_t* out) const;
    void finalize_grid(const GridInputs& in, uint8_t* out) const;

private:
    NativeWindow region_window(int64_t rx, int64_t rz) const;
    // Latent and coarse windows a grid samples from (latent unused for coarse grids).
    void grid_windows(const GridRequest& req, int64_t& li0, int64_t& li1, int64_t& lj0, int64_t& lj1, int64_t& ci0,
                      int64_t& ci1, int64_t& cj0, int64_t& cj1) const;
    // Meters to top block Y; oceans are compressed with a square root.
    int to_y(float m) const;

    void need_coarse_px(int64_t ci0, int64_t ci1, int64_t cj0, int64_t cj1, Plan& plan);
    void need_latents(int64_t li0, int64_t li1, int64_t lj0, int64_t lj1, Plan& plan);

    void run_coarse(const std::vector<TileKey>& keys, Plan& plan);
    void run_latent(const std::vector<TileKey>& keys, bool init, Plan& plan);
    void run_decoder(const std::vector<TileKey>& keys, Plan& plan);

    void latent_conditioning(const TileKey& k, const Plan& plan, float* out58) const;

    Models& m_;
    WorldParams params_;
    SyntheticMap synth_;
    TileCache coarse_cache_, init_cache_, step_cache_, decoder_cache_;
    std::vector<float> win64_, win256_;
    uint64_t detail_seed_;
};

}  // namespace fd
