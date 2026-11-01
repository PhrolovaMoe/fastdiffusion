#include "convert.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <memory>
#include <set>
#include <sstream>
#include <stdexcept>
#include <vector>

#include "ggml.h"
#include "gguf.h"
#include "json.h"
#include "log.h"

namespace fd {

namespace {

constexpr double kMpSiluScale = 1.0 / 0.596;
constexpr double kResBalance = 0.3, kAttnBalance = 0.3;
constexpr int kChannelsPerHead = 64;

std::string read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

struct Array {
    std::vector<int64_t> shape;  // numpy order
    std::vector<double> v;
    int64_t numel() const { return (int64_t)v.size(); }
};

class SafeTensors {
public:
    explicit SafeTensors(const std::string& path) {
        int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) throw std::runtime_error("cannot open " + path);
        struct stat st {};
        fstat(fd, &st);
        size_ = (size_t)st.st_size;
        map_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (map_ == MAP_FAILED) throw std::runtime_error("cannot mmap " + path);
        uint64_t hlen = 0;
        std::memcpy(&hlen, map_, 8);
        if (8 + hlen > size_) throw std::runtime_error("corrupt safetensors header: " + path);
        header_ = Json::parse(std::string(static_cast<const char*>(map_) + 8, hlen));
        data_ = static_cast<const uint8_t*>(map_) + 8 + hlen;
        for (const auto& [k, _] : header_.obj())
            if (k != "__metadata__") names_.insert(k);
    }
    ~SafeTensors() {
        if (map_ != MAP_FAILED) munmap(map_, size_);
    }
    SafeTensors(const SafeTensors&) = delete;

    bool has(const std::string& name) const { return names_.count(name) != 0; }
    std::vector<int64_t> shape(const std::string& name) const {
        std::vector<int64_t> s;
        for (const Json& d : header_[name]["shape"].arr()) s.push_back(d.i64());
        return s;
    }
    const std::set<std::string>& names() const { return names_; }

    Array get(const std::string& name) const {
        const Json& t = header_[name];
        Array a;
        for (const Json& d : t["shape"].arr()) a.shape.push_back(d.i64());
        int64_t n = 1;
        for (int64_t d : a.shape) n *= d;
        const int64_t b = t["data_offsets"][0].i64();
        const uint8_t* p = data_ + b;
        const std::string& dt = t["dtype"].str();
        a.v.resize(n);
        for (int64_t i = 0; i < n; i++) {
            if (dt == "F32") {
                float f;
                std::memcpy(&f, p + 4 * i, 4);
                a.v[i] = f;
            } else if (dt == "F16") {
                ggml_fp16_t h;
                std::memcpy(&h, p + 2 * i, 2);
                a.v[i] = ggml_fp16_to_fp32(h);
            } else if (dt == "BF16") {
                uint16_t h;
                std::memcpy(&h, p + 2 * i, 2);
                uint32_t u = (uint32_t)h << 16;
                float f;
                std::memcpy(&f, &u, 4);
                a.v[i] = f;
            } else {
                throw std::runtime_error("unsupported safetensors dtype " + dt);
            }
        }
        return a;
    }

private:
    void* map_ = MAP_FAILED;
    size_t size_ = 0;
    Json header_;
    const uint8_t* data_ = nullptr;
    std::set<std::string> names_;
};

Array mp_norm(Array w, double gain = 1.0) {
    double ss = 0;
    for (double x : w.v) ss += x * x;
    const double norm = 1e-4 + std::sqrt(ss) * std::sqrt(1.0 / (double)w.numel());
    const double fan_in = (double)w.numel() / (double)w.shape[0];
    const double s = gain / std::sqrt(fan_in) / norm;
    for (double& x : w.v) x *= s;
    return w;
}

Array scaled(Array a, double s) {
    for (double& x : a.v) x *= s;
    return a;
}

double mp_sum_b(double t) { return t / std::sqrt((1 - t) * (1 - t) + t * t); }

// A tensor scheduled for output; data is produced lazily while writing.
struct OutTensor {
    std::string name;
    std::vector<int64_t> shape;
    bool f16;
    std::function<Array()> make;
};

class Writer {
public:
    Writer() : gguf_(gguf_init_empty()) {}
    ~Writer() { gguf_free(gguf_); }

    gguf_context* g() { return gguf_; }
    void u32(const std::string& k, uint32_t v) { gguf_set_val_u32(gguf_, k.c_str(), v); }
    void f32(const std::string& k, float v) { gguf_set_val_f32(gguf_, k.c_str(), v); }
    void str(const std::string& k, const std::string& v) { gguf_set_val_str(gguf_, k.c_str(), v.c_str()); }
    void f32s(const std::string& k, const std::vector<float>& v) { gguf_set_arr_data(gguf_, k.c_str(), GGUF_TYPE_FLOAT32, v.data(), v.size()); }
    void i32s(const std::string& k, const std::vector<int32_t>& v) { gguf_set_arr_data(gguf_, k.c_str(), GGUF_TYPE_INT32, v.data(), v.size()); }
    void strs(const std::string& k, const std::vector<std::string>& v) {
        std::vector<const char*> p;
        for (const auto& s : v) p.push_back(s.c_str());
        gguf_set_arr_str(gguf_, k.c_str(), p.data(), p.size());
    }
    void tensor(OutTensor t) { tensors_.push_back(std::move(t)); }

    void write(const std::string& path) {
        ggml_init_params ip{};
        ip.mem_size = ggml_tensor_overhead() * (tensors_.size() + 8);
        ip.no_alloc = true;
        ggml_context* ctx = ggml_init(ip);
        for (const OutTensor& t : tensors_) {
            int64_t ne[GGML_MAX_DIMS] = {1, 1, 1, 1};
            const int nd = (int)t.shape.size();
            for (int i = 0; i < nd; i++) ne[i] = t.shape[nd - 1 - i];
            ggml_tensor* gt = ggml_new_tensor(ctx, t.f16 ? GGML_TYPE_F16 : GGML_TYPE_F32, std::max(nd, 1), ne);
            ggml_set_name(gt, t.name.c_str());
            gguf_add_tensor(gguf_, gt);
        }
        if (!gguf_write_to_file(gguf_, path.c_str(), true)) throw std::runtime_error("cannot write " + path);
        FILE* f = std::fopen(path.c_str(), "ab");
        if (!f) throw std::runtime_error("cannot append to " + path);
        const size_t align = GGUF_DEFAULT_ALIGNMENT;
        std::vector<uint8_t> buf;
        for (const OutTensor& t : tensors_) {
            const Array a = t.make();
            int64_t n = 1;
            for (int64_t d : t.shape) n *= d;
            if (a.numel() != n) throw std::runtime_error("shape mismatch for " + t.name);
            if (t.f16) {
                buf.resize(n * 2);
                auto* h = reinterpret_cast<ggml_fp16_t*>(buf.data());
                for (int64_t i = 0; i < n; i++) h[i] = ggml_fp32_to_fp16((float)a.v[i]);
            } else {
                buf.resize(n * 4);
                auto* fl = reinterpret_cast<float*>(buf.data());
                for (int64_t i = 0; i < n; i++) fl[i] = (float)a.v[i];
            }
            const size_t pad = (align - buf.size() % align) % align;
            buf.resize(buf.size() + pad, 0);
            if (std::fwrite(buf.data(), 1, buf.size(), f) != buf.size()) {
                std::fclose(f);
                throw std::runtime_error("write failed: " + path);
            }
        }
        std::fclose(f);
        ggml_free(ctx);
    }

private:
    gguf_context* gguf_;
    std::vector<OutTensor> tensors_;
};

class ModelConverter {
public:
    ModelConverter(const std::string& name, const Json& cfg, const SafeTensors& st, Writer& w)
        : name_(name), cfg_(cfg), st_(st), w_(w) {}

    void convert() {
        const auto& mults_j = cfg_["model_channel_mults"].arr();
        std::vector<int32_t> mults;
        for (const Json& m : mults_j) mults.push_back((int32_t)m.i64());
        const int mc = (int)cfg_["model_channels"].i64();
        const int lpb = (int)cfg_["layers_per_block"].i64();
        const int image_size = (int)cfg_["image_size"].i64();
        std::set<int> attn_res;
        if (!cfg_["attn_resolutions"].is_null())
            for (const Json& r : cfg_["attn_resolutions"].arr()) attn_res.insert((int)r.i64());
        int maxm = 0;
        for (int m : mults) maxm = std::max(maxm, m);
        const int emb = cfg_["emb_channels"].is_null() ? mc * maxm : (int)cfg_["emb_channels"].i64();
        const int noise_dims = cfg_["noise_emb_dims"].is_null() ? mc : (int)cfg_["noise_emb_dims"].i64();
        if (cfg_["fourier_scale"].type() != Json::String || cfg_["fourier_scale"].str() != "pos")
            throw std::runtime_error("only positional noise embeddings are supported");
        if (cfg_.has("encode_only") && cfg_["encode_only"].boolean()) throw std::runtime_error("encode_only models are not supported");

        const std::string p = "fd." + name_ + ".";
        w_.u32(p + "in_channels", (uint32_t)cfg_["in_channels"].i64());
        w_.u32(p + "out_channels", (uint32_t)cfg_["out_channels"].i64());
        w_.u32(p + "model_channels", (uint32_t)mc);
        w_.i32s(p + "channel_mults", mults);
        w_.u32(p + "layers_per_block", (uint32_t)lpb);
        w_.u32(p + "emb_channels", (uint32_t)emb);
        w_.u32(p + "noise_emb_dims", (uint32_t)noise_dims);
        w_.f32(p + "concat_balance", (float)cfg_["concat_balance"].num());

        add("noise_fourier.freqs", "noise_fourier.freqs", false, [](Array a) { return a; });
        add("noise_linear.weight", "noise_linear.weight", false, [](Array a) { return mp_norm(std::move(a)); });

        std::vector<float> cond_weights = {1.0f};
        const Json& conds = cfg_["conditional_inputs"];
        const size_t n_cond = conds.is_null() ? 0 : conds.arr().size();
        w_.u32(p + "n_cond", (uint32_t)n_cond);
        for (size_t i = 0; i < n_cond; i++) {
            const Json& c = conds[i];
            const std::string kind = c[0].str();
            const std::string pre = "cond." + std::to_string(i) + ".";
            w_.str(p + pre + "type", kind);
            w_.u32(p + pre + "dim", (uint32_t)c[1].i64());
            cond_weights.push_back((float)c[2].num());
            const std::string src = "conditional_layers." + std::to_string(i);
            if (kind == "float") {
                add(pre + "freqs", src + ".0.freqs", false, [](Array a) { return a; });
                add(pre + "phases", src + ".0.phases", false, [](Array a) { return a; });
                add(pre + "weight", src + ".1.weight", false, [](Array a) { return mp_norm(std::move(a)); });
            } else if (kind == "tensor") {
                add(pre + "weight", src + ".weight", false, [](Array a) { return mp_norm(std::move(a)); });
            } else {
                throw std::runtime_error("unsupported conditional input type " + kind);
            }
        }
        w_.f32s(p + "cond_weights", cond_weights);

        std::vector<std::string> layers;
        std::vector<int> skips;
        int cout = (int)cfg_["in_channels"].i64() + 1;
        for (size_t level = 0; level < mults.size(); level++) {
            const int ch = mc * mults[level];
            const int res = image_size >> level;
            const std::string r = std::to_string(res) + "x" + std::to_string(res);
            const bool attn = attn_res.count(res) != 0;
            if (level == 0) {
                const std::string n = "enc." + r + "_conv";
                add(n + ".weight", n + ".weight", true, [](Array a) { return mp_norm(std::move(a)); });
                layers.push_back(spec("conv", n, cout, ch, false));
                cout = ch;
            } else {
                const std::string n = "enc." + r + "_down";
                block(n, cout, cout, attn);
                layers.push_back(spec("down", n, cout, cout, attn));
            }
            skips.push_back(cout);
            for (int idx = 0; idx < lpb; idx++) {
                const int cin = cout;
                cout = ch;
                const std::string n = "enc." + r + "_block" + std::to_string(idx);
                block(n, cin, cout, attn);
                layers.push_back(spec("block", n, cin, cout, attn));
                skips.push_back(cout);
            }
        }
        for (int level = (int)mults.size() - 1; level >= 0; level--) {
            const int ch = mc * mults[level];
            const int res = image_size >> level;
            const std::string r = std::to_string(res) + "x" + std::to_string(res);
            if (level == (int)mults.size() - 1) {
                const bool mid_attn = cfg_["midblock_attention"].boolean();
                block("dec." + r + "_in0", cout, cout, mid_attn);
                layers.push_back(spec("in", "dec." + r + "_in0", cout, cout, mid_attn));
                block("dec." + r + "_in1", cout, cout, false);
                layers.push_back(spec("in", "dec." + r + "_in1", cout, cout, false));
            } else {
                block("dec." + r + "_up", cout, cout, false);
                layers.push_back(spec("up", "dec." + r + "_up", cout, cout, false));
            }
            const bool attn = attn_res.count(res) != 0;
            for (int idx = 0; idx <= lpb; idx++) {
                const int skip = skips.back();
                skips.pop_back();
                const int cin = cout + skip;
                cout = ch;
                const std::string n = "dec." + r + "_block" + std::to_string(idx);
                block(n, cin, cout, attn);
                layers.push_back(spec("block", n, cin, cout, attn));
            }
        }
        const double out_gain = st_.has("out_gain") ? scalar("out_gain") : 1.0;
        add("out_conv.weight", "out_conv.weight", true, [out_gain](Array a) { return mp_norm(std::move(a), out_gain); });
        w_.strs(p + "layers", layers);

        for (const std::string& n : st_.names())
            if (!used_.count(n) && n.rfind("logvar_", 0) != 0) throw std::runtime_error("unconverted tensor in " + name_ + ": " + n);
    }

private:
    static std::string spec(const char* kind, const std::string& n, int cin, int cout, bool attn) {
        return std::string(kind) + ":" + n + ":" + std::to_string(cin) + ":" + std::to_string(cout) + ":" + (attn ? "1" : "0");
    }

    double scalar(const std::string& key) {
        used_.insert(key);
        return st_.get(key).v.at(0);
    }

    // Registers output tensor `key` computed from source tensor `src`.
    void add(const std::string& key, const std::string& src, bool f16, std::function<Array(Array)> fn) {
        if (!st_.has(src)) throw std::runtime_error("missing tensor " + src + " in " + name_);
        used_.insert(src);
        const SafeTensors* st = &st_;
        w_.tensor({name_ + "." + key, st_.shape(src), f16, [st, src, fn] { return fn(st->get(src)); }});
    }

    void block(const std::string& n, int cin, int cout, bool attention) {
        const double res_b = mp_sum_b(kResBalance);
        const double emb_gain = scalar(n + ".emb_gain");
        add(n + ".conv_res0.weight", n + ".conv_res0.weight", true, [](Array a) { return scaled(mp_norm(std::move(a)), kMpSiluScale); });
        add(n + ".conv_res1.weight", n + ".conv_res1.weight", true,
            [res_b](Array a) { return scaled(mp_norm(std::move(a)), kMpSiluScale * res_b); });
        add(n + ".emb_linear.weight", n + ".emb_linear.weight", true,
            [emb_gain](Array a) { return scaled(mp_norm(std::move(a), emb_gain), kMpSiluScale); });
        if (cin != cout) add(n + ".conv_skip.weight", n + ".conv_skip.weight", true, [](Array a) { return mp_norm(std::move(a)); });
        if (attention) {
            const int heads = cout / kChannelsPerHead, ch = cout / heads;
            add(n + ".attn_qkv.weight", n + ".attn_qkv.weight", true, [heads, ch](Array a) {
                a = mp_norm(std::move(a));
                const int64_t row = a.numel() / a.shape[0];
                Array out = a;
                int64_t o = 0;
                for (int k = 0; k < 3; k++)
                    for (int h = 0; h < heads; h++)
                        for (int c = 0; c < ch; c++, o++) {
                            const int64_t src = (int64_t)h * ch * 3 + c * 3 + k;
                            std::copy_n(&a.v[src * row], row, &out.v[o * row]);
                        }
                return out;
            });
            const double attn_b = mp_sum_b(kAttnBalance);
            add(n + ".attn_proj.weight", n + ".attn_proj.weight", true, [attn_b](Array a) { return scaled(mp_norm(std::move(a)), attn_b); });
        }
    }

    std::string name_;
    const Json& cfg_;
    const SafeTensors& st_;
    Writer& w_;
    std::set<std::string> used_;
};

}  // namespace

void convert_model(const std::string& weights_dir, const std::string& pipeline_data, const std::string& out_path) {
    Writer w;
    w.str("general.architecture", "terrain-diffusion");
    w.str("general.name", "terrain-diffusion-30m");
    w.u32("fd.fold_version", 1);

    const Json pipe = Json::parse(read_file(weights_dir + "/config.json"));
    for (const char* key : {"coarse_means", "coarse_stds", "cond_snr", "frequency_mult"}) {
        std::vector<float> v;
        for (const Json& x : pipe[key].arr()) v.push_back((float)x.num());
        w.f32s(std::string("fd.pipeline.") + key, v);
    }
    for (const char* key : {"residual_mean", "residual_std", "native_resolution"})
        w.f32(std::string("fd.pipeline.") + key, (float)pipe[key].num());
    w.u32("fd.pipeline.latent_compression", (uint32_t)pipe["latent_compression"].i64());
    if (pipe["coarse_pooling"].i64() != 1) throw std::runtime_error("coarse_pooling != 1 is not supported");

    const Json pdata = Json::parse(read_file(pipeline_data));
    w.u32("fd.synth.n_quantiles", (uint32_t)pdata["n_quantiles"].i64());
    for (const char* key : {"a_temp_std", "b_temp_std", "temp_std_p1", "temp_std_p99"})
        w.f32(std::string("fd.synth.") + key, (float)pdata[key].num());
    Array dq;
    for (const Json& row : pdata["data_quantile_tables"].arr())
        for (const Json& x : row.arr()) dq.v.push_back(x.num());
    dq.shape = {(int64_t)pdata["data_quantile_tables"].arr().size(), (int64_t)pdata["n_quantiles"].i64()};
    w.tensor({"synth.data_quantiles", dq.shape, false, [dq] { return dq; }});

    std::vector<std::unique_ptr<SafeTensors>> files;
    std::vector<Json> cfgs;
    cfgs.reserve(3);
    for (const char* name : {"coarse", "base", "decoder"}) {
        const std::string folder = weights_dir + "/" + name + "_model";
        cfgs.push_back(Json::parse(read_file(folder + "/config.json")));
        files.push_back(std::make_unique<SafeTensors>(folder + "/diffusion_pytorch_model.safetensors"));
        ModelConverter(name, cfgs.back(), *files.back(), w).convert();
    }
    const std::string tmp = out_path + ".part";
    w.write(tmp);
    if (std::rename(tmp.c_str(), out_path.c_str()) != 0) throw std::runtime_error("cannot move " + tmp + " to " + out_path);
    log_info("converted model written to %s", out_path.c_str());
}

}  // namespace fd
