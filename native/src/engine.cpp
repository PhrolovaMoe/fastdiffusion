#include "engine.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <stdexcept>

#include "ggml-cpu.h"
#include "log.h"

namespace fd {

namespace {

// Small tensors evaluated on the CPU (embeddings, synthetic-map tables).
bool wants_host_copy(const std::string& name) {
    return name.find("noise_fourier") != std::string::npos || name.find("noise_linear") != std::string::npos ||
           name.find(".cond.") != std::string::npos || name.rfind("synth.", 0) == 0;
}

struct MappedFile {
    void* data = MAP_FAILED;
    size_t size = 0;
    explicit MappedFile(const std::string& path) {
        int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) throw std::runtime_error("cannot open model file: " + path);
        struct stat st {};
        fstat(fd, &st);
        size = static_cast<size_t>(st.st_size);
        data = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (data == MAP_FAILED) throw std::runtime_error("cannot mmap model file: " + path);
        madvise(data, size, MADV_SEQUENTIAL);
    }
    ~MappedFile() {
        if (data != MAP_FAILED) munmap(data, size);
    }
};

}  // namespace

Engine::Engine(const std::string& gguf_path, const EngineOptions& opts) {
    gguf_init_params params{};
    params.no_alloc = true;
    params.ctx = &meta_ctx_;
    gguf_ = gguf_init_from_file(gguf_path.c_str(), params);
    if (!gguf_) throw std::runtime_error("failed to parse GGUF: " + gguf_path);
    if (!has_key("fd.fold_version") || u32("fd.fold_version") != 1)
        throw std::runtime_error("unsupported model file (fold_version mismatch): " + gguf_path);

    // Pick the backend device.
    if (opts.device != -2) {
        int gpu_idx = 0;
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (ggml_backend_dev_type(dev) != GGML_BACKEND_DEVICE_TYPE_GPU) continue;
            if (opts.device < 0 || opts.device == gpu_idx) {
                backend_ = ggml_backend_dev_init(dev, nullptr);
                if (backend_) {
                    device_name_ = ggml_backend_dev_description(dev);
                    is_gpu_ = true;
                }
                break;
            }
            gpu_idx++;
        }
        if (!backend_) log_warn("no usable GPU device found, falling back to CPU inference (slow)");
    }
    if (!backend_) {
        backend_ = ggml_backend_cpu_init();
        if (!backend_) throw std::runtime_error("failed to initialise CPU backend");
        ggml_backend_cpu_set_n_threads(backend_, opts.cpu_threads);
        device_name_ = "CPU";
    }
    log_info("inference device: %s", device_name_.c_str());

    weights_ = ggml_backend_alloc_ctx_tensors(meta_ctx_, backend_);
    if (!weights_) throw std::runtime_error("failed to allocate model weights on " + device_name_);
    ggml_backend_buffer_set_usage(weights_, GGML_BACKEND_BUFFER_USAGE_WEIGHTS);

    MappedFile file(gguf_path);
    const size_t data_offset = gguf_get_data_offset(gguf_);
    const int64_t n = gguf_get_n_tensors(gguf_);
    for (int64_t i = 0; i < n; i++) {
        const std::string name = gguf_get_tensor_name(gguf_, i);
        ggml_tensor* t = ggml_get_tensor(meta_ctx_, name.c_str());
        const size_t off = data_offset + gguf_get_tensor_offset(gguf_, i);
        const size_t bytes = ggml_nbytes(t);
        if (off + bytes > file.size) throw std::runtime_error("truncated model file: " + gguf_path);
        const auto* src = static_cast<const uint8_t*>(file.data) + off;
        ggml_backend_tensor_set(t, src, 0, bytes);
        tensors_[name] = t;

        if (wants_host_copy(name)) {
            std::vector<float> v(ggml_nelements(t));
            if (t->type == GGML_TYPE_F32) {
                std::memcpy(v.data(), src, bytes);
            } else if (t->type == GGML_TYPE_F16) {
                ggml_fp16_to_fp32_row(reinterpret_cast<const ggml_fp16_t*>(src), v.data(), (int64_t)v.size());
            } else {
                throw std::runtime_error("unexpected host tensor type for " + name);
            }
            host_[name] = std::move(v);
        }
    }
    log_info("loaded %lld tensors (%.1f MiB) from %s", (long long)n,
             ggml_backend_buffer_get_size(weights_) / (1024.0 * 1024.0), gguf_path.c_str());
}

Engine::~Engine() {
    if (weights_) ggml_backend_buffer_free(weights_);
    if (backend_) ggml_backend_free(backend_);
    if (meta_ctx_) ggml_free(meta_ctx_);
    if (gguf_) gguf_free(gguf_);
}

ggml_tensor* Engine::tensor(const std::string& name) const {
    auto it = tensors_.find(name);
    if (it == tensors_.end()) throw std::runtime_error("missing tensor: " + name);
    return it->second;
}

bool Engine::has_tensor(const std::string& name) const { return tensors_.count(name) != 0; }

const std::vector<float>& Engine::host(const std::string& name) const {
    auto it = host_.find(name);
    if (it == host_.end()) throw std::runtime_error("missing host tensor: " + name);
    return it->second;
}

int64_t Engine::key_id(const std::string& key) const {
    int64_t id = gguf_find_key(gguf_, key.c_str());
    if (id < 0) throw std::runtime_error("missing model metadata: " + key);
    return id;
}

bool Engine::has_key(const std::string& key) const { return gguf_find_key(gguf_, key.c_str()) >= 0; }
uint32_t Engine::u32(const std::string& key) const { return gguf_get_val_u32(gguf_, key_id(key)); }
float Engine::f32(const std::string& key) const { return gguf_get_val_f32(gguf_, key_id(key)); }
std::string Engine::str(const std::string& key) const { return gguf_get_val_str(gguf_, key_id(key)); }

std::vector<float> Engine::f32_array(const std::string& key) const {
    const int64_t id = key_id(key);
    const size_t n = gguf_get_arr_n(gguf_, id);
    std::vector<float> out(n);
    const gguf_type t = gguf_get_arr_type(gguf_, id);
    const void* data = gguf_get_arr_data(gguf_, id);
    for (size_t i = 0; i < n; i++) {
        if (t == GGUF_TYPE_FLOAT32) out[i] = static_cast<const float*>(data)[i];
        else if (t == GGUF_TYPE_FLOAT64) out[i] = (float)static_cast<const double*>(data)[i];
        else if (t == GGUF_TYPE_INT32) out[i] = (float)static_cast<const int32_t*>(data)[i];
        else throw std::runtime_error("unexpected array type for " + key);
    }
    return out;
}

std::vector<int> Engine::int_array(const std::string& key) const {
    const int64_t id = key_id(key);
    const size_t n = gguf_get_arr_n(gguf_, id);
    std::vector<int> out(n);
    const gguf_type t = gguf_get_arr_type(gguf_, id);
    const void* data = gguf_get_arr_data(gguf_, id);
    for (size_t i = 0; i < n; i++) {
        if (t == GGUF_TYPE_INT32) out[i] = static_cast<const int32_t*>(data)[i];
        else if (t == GGUF_TYPE_INT64) out[i] = (int)static_cast<const int64_t*>(data)[i];
        else if (t == GGUF_TYPE_UINT32) out[i] = (int)static_cast<const uint32_t*>(data)[i];
        else throw std::runtime_error("unexpected array type for " + key);
    }
    return out;
}

std::vector<std::string> Engine::str_array(const std::string& key) const {
    const int64_t id = key_id(key);
    const size_t n = gguf_get_arr_n(gguf_, id);
    std::vector<std::string> out(n);
    for (size_t i = 0; i < n; i++) out[i] = gguf_get_arr_str(gguf_, id, i);
    return out;
}

void Engine::compute(ggml_cgraph* graph) {
    if (ggml_backend_graph_compute(backend_, graph) != GGML_STATUS_SUCCESS)
        throw std::runtime_error("graph compute failed on " + device_name_);
}

}  // namespace fd
