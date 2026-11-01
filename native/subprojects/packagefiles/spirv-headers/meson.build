# Header-only; only spirv/unified1/spirv.hpp is needed by ggml-vulkan.
project('spirv-headers', version: '1.4.357.0')
spirv_headers_dep = declare_dependency(include_directories: include_directories('include'))
meson.override_dependency('SPIRV-Headers', spirv_headers_dep)
