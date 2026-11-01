// Converts the upstream PyTorch checkpoints into the folded GGUF the engine loads.
// C++ port of tools/convert_to_gguf.py (which documents the folding math).
#pragma once

#include <string>

namespace fd {

// weights_dir: Hugging Face layout of xandergos/terrain-diffusion-30m.
// pipeline_data: pipeline_data.json from xandergos/terrain-diffusion-30m-onnx.
void convert_model(const std::string& weights_dir, const std::string& pipeline_data, const std::string& out_path);

}  // namespace fd
