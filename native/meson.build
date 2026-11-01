project('fastdiffusion', 'c', 'cpp',
  version: '0.1.0',
  meson_version: '>=1.3.0',
  default_options: [
    'cpp_std=c++20',
    'buildtype=release',
    'b_ndebug=if-release',
    'warning_level=2',
    'default_library=static',
  ],
)

# ggml's 'vulkan' option yields to ours.
ggml_dep = dependency('ggml', fallback: ['ggml', 'ggml_dep'])
threads = dependency('threads')
add_languages('java', native: false)
jni = dependency('jni', version: '>=21')

core_sources = files(
  'src/convert.cpp',
  'src/engine.cpp',
  'src/json.cpp',
  'src/log.cpp',
  'src/unet.cpp',
  'src/pipeline.cpp',
  'src/synthetic_map.cpp',
  'src/postprocess.cpp',
  'src/service.cpp',
)

core = static_library('fdcore',
  core_sources,
  dependencies: [ggml_dep, threads],
  pic: true,
)
core_dep = declare_dependency(link_with: core, dependencies: [ggml_dep, threads],
  include_directories: include_directories('src'))

# The library loaded by the mod. Everything but libc/libvulkan is linked statically
# so the .so runs on any reasonably recent glibc distro.
shared_library('fastdiffusion',
  'src/jni_bridge.cpp',
  dependencies: [core_dep, jni],
  gnu_symbol_visibility: 'hidden',
  link_args: ['-static-libstdc++', '-static-libgcc', '-Wl,--exclude-libs,ALL', '-Wl,-z,defs'],
  install: true,
)

executable('fd-cli', 'tools/fd_cli.cpp', dependencies: core_dep)

# Classification boundaries run without loading a model or starting a GPU.
biome_tests = executable('biome-tests', ['tests/biomes.cpp', 'src/postprocess.cpp'],
  include_directories: include_directories('src'))
test('biome classification', biome_tests)
