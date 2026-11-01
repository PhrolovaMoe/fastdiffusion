#include "unet.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>
#include <stdexcept>

#include "log.h"

namespace fd {

namespace {

constexpr float kRmsEps = 1e-8f;
constexpr float kClipAct = 256.0f;
constexpr int kChannelsPerHead = 64;
constexpr float kResBalance = 0.3f;
constexpr float kAttnBalance = 0.3f;
constexpr size_t kGraphSize = 8192;

// mp_sum([a, b], w=t) == a * ca + b * cb; cb is folded into the weights producing b.
float mp_sum_a(float t) { return (1.0f - t) / std::sqrt((1.0f - t) * (1.0f - t) + t * t); }

float silu(float x) { return x / (1.0f + std::exp(-x)); }

std::vector<std::string> split(const std::string& s, char sep) {
    std::vector<std::string> out;
    std::stringstream ss(s);
    std::string item;
    while (std::getline(ss, item, sep)) out.push_back(item);
    return out;
}

// Pixel norm over channels of a [W, H, C, N] tensor: x / rms_c(x).
ggml_tensor* pixel_norm(ggml_context* ctx, ggml_tensor* x) {
    ggml_tensor* t = ggml_cont(ctx, ggml_permute(ctx, x, 1, 2, 0, 3));  // [C, W, H, N]
    t = ggml_rms_norm(ctx, t, kRmsEps);
    return ggml_cont(ctx, ggml_permute(ctx, t, 2, 0, 1, 3));
}


}  // namespace

UNet::UNet(Engine& engine, const std::string& name, bool direct_conv)
    : engine_(engine), name_(name), direct_conv_(direct_conv) {
    const std::string p = "fd." + name + ".";
    in_ch_ = (int)engine.u32(p + "in_channels");
    out_ch_ = (int)engine.u32(p + "out_channels");
    emb_ch_ = (int)engine.u32(p + "emb_channels");
    concat_balance_ = engine.f32(p + "concat_balance");
    const int n_cond = (int)engine.u32(p + "n_cond");
    for (int i = 0; i < n_cond; i++) {
        const std::string pre = p + "cond." + std::to_string(i) + ".";
        conds_.push_back({engine.str(pre + "type") == "float", (int)engine.u32(pre + "dim")});
    }
    cond_weights_ = engine.f32_array(p + "cond_weights");

    for (const std::string& spec : engine.str_array(p + "layers")) {
        auto f = split(spec, ':');
        if (f.size() != 5) throw std::runtime_error("bad layer spec: " + spec);
        UNetLayer l{};
        l.name = f[1];
        l.encoder = l.name.rfind("enc.", 0) == 0;
        if (f[0] == "conv") l.kind = UNetLayer::Conv;
        else if (f[0] == "down") l.kind = UNetLayer::Down;
        else if (f[0] == "block") l.kind = UNetLayer::Block;
        else if (f[0] == "in") l.kind = UNetLayer::In;
        else if (f[0] == "up") l.kind = UNetLayer::Up;
        else throw std::runtime_error("bad layer kind: " + spec);
        l.cin = std::stoi(f[2]);
        l.cout = std::stoi(f[3]);
        l.attn = f[4] == "1";
        layers_.push_back(l);
    }

    galloc_ = ggml_gallocr_new(ggml_backend_get_default_buffer_type(engine.backend()));
}

UNet::~UNet() {
    if (galloc_) ggml_gallocr_free(galloc_);
}

void UNet::embed(int n, const float* noise, const std::vector<const float*>& conds, float* out) const {
    if (conds.size() != conds_.size()) throw std::runtime_error(name_ + ": wrong number of conditional inputs");
    const int E = emb_ch_;
    const auto& freqs = engine_.host(name_ + ".noise_fourier.freqs");
    const auto& wn = engine_.host(name_ + ".noise_linear.weight");
    const int half = (int)freqs.size();
    double wnorm = 0;
    for (float cw : cond_weights_) wnorm += (double)cw * cw;
    wnorm = std::sqrt(wnorm);

    std::vector<double> acc(E), feat, tmp(E);
    for (int b = 0; b < n; b++) {
        std::fill(acc.begin(), acc.end(), 0.0);
        auto linear_acc = [&](const std::vector<float>& W, const std::vector<double>& in, double scale, bool mp_silu) {
            const int din = (int)in.size();
            for (int o = 0; o < E; o++) {
                double s = 0;
                const float* row = &W[(size_t)o * din];
                for (int i = 0; i < din; i++) s += row[i] * in[i];
                if (mp_silu) s = silu((float)s) / 0.596;
                acc[o] += s * scale;
            }
        };

        // Noise: positional embedding [sin, cos] * sqrt(2) -> linear.
        feat.assign(2 * half, 0.0);
        for (int i = 0; i < half; i++) {
            const double y = (double)noise[b] * freqs[i];
            feat[i] = std::sin(y) * M_SQRT2;
            feat[half + i] = std::cos(y) * M_SQRT2;
        }
        linear_acc(wn, feat, cond_weights_[0] / wnorm, false);

        for (size_t c = 0; c < conds_.size(); c++) {
            const std::string pre = name_ + ".cond." + std::to_string(c) + ".";
            const auto& W = engine_.host(pre + "weight");
            const double scale = cond_weights_[c + 1] / wnorm;
            if (conds_[c].is_float) {
                const auto& fr = engine_.host(pre + "freqs");
                const auto& ph = engine_.host(pre + "phases");
                feat.assign(fr.size(), 0.0);
                for (size_t i = 0; i < fr.size(); i++)
                    feat[i] = std::cos((double)conds[c][b] * fr[i] + ph[i]) * M_SQRT2;
                linear_acc(W, feat, scale, false);
            } else {
                const int d = conds_[c].dim;
                feat.assign(conds[c] + (size_t)b * d, conds[c] + (size_t)(b + 1) * d);
                linear_acc(W, feat, scale, true);
            }
        }
        // mp_silu(emb); the 1/0.596 is folded into every emb_linear weight.
        for (int o = 0; o < E; o++) out[(size_t)b * E + o] = silu((float)acc[o]);
    }
}

ggml_tensor* UNet::conv(ggml_context* ctx, ggml_tensor* weight, ggml_tensor* x) const {
    const int pad = (int)weight->ne[0] / 2;
    // Implicit-GEMM conv wins on the small latent tiles, im2col + coopmat GEMM on large ones.
    return direct_conv_ ? ggml_conv_2d_direct(ctx, weight, x, 1, 1, pad, pad, 1, 1)
                        : ggml_conv_2d(ctx, weight, x, 1, 1, pad, pad, 1, 1);
}

ggml_tensor* UNet::attention(ggml_context* ctx, const std::string& name, ggml_tensor* x) const {
    const int64_t W = x->ne[0], H = x->ne[1], C = x->ne[2], N = x->ne[3];
    const int64_t L = W * H, heads = C / kChannelsPerHead, ch = kChannelsPerHead;

    // qkv channels were reordered by the converter into [q | k | v], each [heads, ch].
    ggml_tensor* qkv = conv(ctx, w(name + ".attn_qkv.weight"), x);  // [W, H, 3C, N]
    const size_t es = ggml_element_size(qkv);
    auto part = [&](int k) {
        // view [L, ch, heads, N] of block k, then -> [ch, L, heads*N], pixel-normed over ch
        ggml_tensor* v = ggml_view_4d(ctx, qkv, L, ch, heads, N, L * es, L * ch * es, 3 * C * L * es, (size_t)k * C * L * es);
        v = ggml_cont(ctx, ggml_permute(ctx, v, 1, 0, 2, 3));
        v = ggml_reshape_3d(ctx, v, ch, L, heads * N);
        return ggml_rms_norm(ctx, v, kRmsEps);
    };
    ggml_tensor* q = part(0);
    ggml_tensor* k = part(1);
    ggml_tensor* v = part(2);

    ggml_tensor* scores = ggml_mul_mat(ctx, k, q);  // [Lk, Lq, B]
    scores = ggml_soft_max_ext(ctx, scores, nullptr, 1.0f / std::sqrt((float)ch), 0.0f);
    ggml_tensor* vt = ggml_cont(ctx, ggml_permute(ctx, v, 1, 0, 2, 3));  // [Lk, ch, B]
    ggml_tensor* o = ggml_mul_mat(ctx, vt, scores);                     // [ch, Lq, B]
    o = ggml_reshape_4d(ctx, o, ch, L, heads, N);
    o = ggml_cont(ctx, ggml_permute(ctx, o, 1, 0, 2, 3));  // [L, ch, heads, N]
    o = ggml_reshape_4d(ctx, o, W, H, C, N);
    return conv(ctx, w(name + ".attn_proj.weight"), o);
}

ggml_tensor* UNet::block(ggml_context* ctx, const UNetLayer& l, ggml_tensor* x, ggml_tensor* emb) const {
    const std::string& n = l.name;
    if (l.encoder) {
        if (l.kind == UNetLayer::Down) x = ggml_pool_2d(ctx, x, GGML_OP_POOL_AVG, 1, 1, 2, 2, 0, 0);
        if (l.cin != l.cout) x = conv(ctx, w(n + ".conv_skip.weight"), x);
        x = pixel_norm(ctx, x);
    } else if (l.kind == UNetLayer::Up) {
        x = ggml_upscale(ctx, x, 2, GGML_SCALE_MODE_NEAREST);
    }

    ggml_tensor* y = conv(ctx, w(n + ".conv_res0.weight"), ggml_silu(ctx, x));
    ggml_tensor* c = ggml_mul_mat(ctx, w(n + ".emb_linear.weight"), emb);  // [C, N]
    c = ggml_rms_norm(ctx, ggml_scale_bias(ctx, c, 1.0f, 1.0f), kRmsEps);
    c = ggml_reshape_4d(ctx, c, 1, 1, c->ne[0], c->ne[1]);
    y = ggml_mul(ctx, y, c);
    y = conv(ctx, w(n + ".conv_res1.weight"), ggml_silu(ctx, y));

    if (!l.encoder && l.cin != l.cout) x = conv(ctx, w(n + ".conv_skip.weight"), x);
    x = ggml_add(ctx, ggml_scale(ctx, x, mp_sum_a(kResBalance)), y);
    if (l.attn) x = ggml_add(ctx, ggml_scale(ctx, x, mp_sum_a(kAttnBalance)), attention(ctx, n, x));
    return ggml_clamp(ctx, x, -kClipAct, kClipAct);
}

ggml_tensor* UNet::build(ggml_context* ctx, ggml_tensor* x, ggml_tensor* emb) const {
    std::vector<ggml_tensor*> skips;
    for (const UNetLayer& l : layers_) {
        if (!l.encoder) break;
        x = l.kind == UNetLayer::Conv ? conv(ctx, w(l.name + ".weight"), x) : block(ctx, l, x, emb);
        skips.push_back(x);
    }
    const float wa = 1.0f - concat_balance_, wb = concat_balance_;
    for (const UNetLayer& l : layers_) {
        if (l.encoder) continue;
        if (l.kind == UNetLayer::Block) {
            ggml_tensor* s = skips.back();
            skips.pop_back();
            // mp_concat([x, skip], w=[wa, wb])
            const float na = (float)x->ne[2], nb = (float)s->ne[2];
            const float C = std::sqrt((na + nb) / (wa * wa + wb * wb));
            x = ggml_concat(ctx, ggml_scale(ctx, x, C / std::sqrt(na) * wa), ggml_scale(ctx, s, C / std::sqrt(nb) * wb), 2);
        }
        x = block(ctx, l, x, emb);
    }
    return conv(ctx, w("out_conv.weight"), x);
}

void UNet::forward(int n, int h, int w_, const float* x, const float* emb, float* out) {
    ggml_init_params params{};
    params.mem_size = ggml_tensor_overhead() * kGraphSize + ggml_graph_overhead_custom(kGraphSize, false);
    params.no_alloc = true;
    ggml_context* ctx = ggml_init(params);
    if (!ctx) throw std::runtime_error("ggml_init failed");

    struct CtxGuard {
        ggml_context* c;
        ~CtxGuard() { ggml_free(c); }
    } guard{ctx};

    // The model takes an extra all-ones channel (stand-in for a bias).
    const int cin = in_ch_ + 1;
    ggml_tensor* in = ggml_new_tensor_4d(ctx, GGML_TYPE_F32, w_, h, cin, n);
    ggml_set_input(in);
    ggml_tensor* e = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, emb_ch_, n);
    ggml_set_input(e);
    ggml_tensor* y = build(ctx, in, e);
    ggml_set_output(y);

    ggml_cgraph* gf = ggml_new_graph_custom(ctx, kGraphSize, false);
    ggml_build_forward_expand(gf, y);

    // Every op must run on the device; there is deliberately no CPU fallback inside a graph.
    const std::string shape = std::to_string(n) + "x" + std::to_string(h) + "x" + std::to_string(w_);
    if (std::find(verified_shapes_.begin(), verified_shapes_.end(), shape) == verified_shapes_.end()) {
        for (int i = 0; i < ggml_graph_n_nodes(gf); i++) {
            ggml_tensor* node = ggml_graph_node(gf, i);
            if (!ggml_backend_supports_op(engine_.backend(), node))
                throw std::runtime_error(std::string("op not supported by ") + engine_.device_name() + ": " +
                                         ggml_op_desc(node) + " (" + node->name + ")");
        }
        verified_shapes_.push_back(shape);
    }

    if (!ggml_gallocr_alloc_graph(galloc_, gf)) throw std::runtime_error("failed to allocate compute buffer");

    const size_t plane = (size_t)h * w_;
    staging_.resize((size_t)n * cin * plane);
    for (int b = 0; b < n; b++) {
        std::memcpy(&staging_[(size_t)b * cin * plane], x + (size_t)b * in_ch_ * plane, in_ch_ * plane * sizeof(float));
        std::fill_n(&staging_[((size_t)b * cin + in_ch_) * plane], plane, 1.0f);
    }
    ggml_backend_tensor_set(in, staging_.data(), 0, staging_.size() * sizeof(float));
    ggml_backend_tensor_set(e, emb, 0, (size_t)n * emb_ch_ * sizeof(float));
    engine_.compute(gf);
    ggml_backend_tensor_get(y, out, 0, (size_t)n * out_ch_ * plane * sizeof(float));
}

}  // namespace fd
