// GGUF model file + ggml backend. Owns all weights on the compute device.
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"
#include "gguf.h"

namespace fd {

struct EngineOptions {
    // -1: first GPU (falls back to CPU), -2: force CPU, >= 0: GPU index among GPU devices.
    int device = -1;
    int cpu_threads = 4;
};

class Engine {
public:
    Engine(const std::string& gguf_path, const EngineOptions& opts);
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    ggml_backend_t backend() const { return backend_; }
    const std::string& device_name() const { return device_name_; }
    bool is_gpu() const { return is_gpu_; }

    // Device tensor by name; throws if missing.
    ggml_tensor* tensor(const std::string& name) const;
    bool has_tensor(const std::string& name) const;
    // f32 host copy of a tensor (only kept for small tensors used on the CPU).
    const std::vector<float>& host(const std::string& name) const;

    uint32_t u32(const std::string& key) const;
    float f32(const std::string& key) const;
    std::string str(const std::string& key) const;
    std::vector<float> f32_array(const std::string& key) const;
    std::vector<int> int_array(const std::string& key) const;
    std::vector<std::string> str_array(const std::string& key) const;
    bool has_key(const std::string& key) const;

    void compute(ggml_cgraph* graph);

private:
    int64_t key_id(const std::string& key) const;

    ggml_backend_t backend_ = nullptr;
    ggml_backend_buffer_t weights_ = nullptr;
    ggml_context* meta_ctx_ = nullptr;
    gguf_context* gguf_ = nullptr;
    std::string device_name_;
    bool is_gpu_ = false;
    std::unordered_map<std::string, ggml_tensor*> tensors_;
    std::unordered_map<std::string, std::vector<float>> host_;
};

}  // namespace fd
