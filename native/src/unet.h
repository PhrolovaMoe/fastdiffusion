// EDM2-style magnitude-preserving UNet (terrain-diffusion EDMUnet2D) as a ggml graph.
// Weights come pre-folded from tools/convert_to_gguf.py, see that file for the math.
#pragma once

#include <string>
#include <vector>

#include "engine.h"
#include "ggml-alloc.h"

namespace fd {

struct UNetLayer {
    enum Kind { Conv, Down, Block, In, Up } kind;
    std::string name;
    int cin, cout;
    bool attn;
    bool encoder;
};

struct UNetCond {
    bool is_float;  // "float" (Fourier features of a scalar) or "tensor" (dense vector)
    int dim;
};

class UNet {
public:
    UNet(Engine& engine, const std::string& name, bool direct_conv);
    ~UNet();
    UNet(const UNet&) = delete;
    UNet& operator=(const UNet&) = delete;

    int in_channels() const { return in_ch_; }
    int out_channels() const { return out_ch_; }
    int emb_channels() const { return emb_ch_; }
    const std::vector<UNetCond>& conds() const { return conds_; }

    // Conditioning embedding for n samples, computed on the CPU.
    // noise: [n]; conds[i]: [n] for float inputs, [n * dim] for tensor inputs; out: [n * emb_channels].
    void embed(int n, const float* noise, const std::vector<const float*>& conds, float* out) const;

    // x: [n, in_channels, h, w], emb: [n, emb_channels] -> out: [n, out_channels, h, w]. NCHW float32.
    void forward(int n, int h, int w, const float* x, const float* emb, float* out);

private:
    ggml_tensor* build(ggml_context* ctx, ggml_tensor* x, ggml_tensor* emb) const;
    ggml_tensor* block(ggml_context* ctx, const UNetLayer& l, ggml_tensor* x, ggml_tensor* emb) const;
    ggml_tensor* conv(ggml_context* ctx, ggml_tensor* weight, ggml_tensor* x) const;
    ggml_tensor* attention(ggml_context* ctx, const std::string& name, ggml_tensor* x) const;
    ggml_tensor* w(const std::string& key) const { return engine_.tensor(name_ + "." + key); }

    Engine& engine_;
    std::string name_;
    bool direct_conv_;
    int in_ch_, out_ch_, emb_ch_;
    float concat_balance_;
    std::vector<UNetCond> conds_;
    std::vector<float> cond_weights_;
    std::vector<UNetLayer> layers_;
    ggml_gallocr_t galloc_ = nullptr;
    std::vector<float> staging_;
    std::vector<std::string> verified_shapes_;
};

}  // namespace fd
