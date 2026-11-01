# Meson build for ggml (overlay applied by subprojects/ggml.wrap).
# Builds static ggml-base + ggml-cpu (+ ggml-vulkan) with backends registered statically.
project('ggml', 'c', 'cpp',
  version: '0.26.0',
  meson_version: '>=1.3.0',
  default_options: [
    'c_std=gnu11',
    'cpp_std=c++17',
    'buildtype=release',
    'b_ndebug=if-release',
    'warning_level=0',
    'default_library=static',
  ],
)

cc = meson.get_compiler('c')
threads = dependency('threads')
libm = cc.find_library('m', required: false)

inc = include_directories('include', 'src')
public_inc = include_directories('include')

common_args = [
  '-D_GNU_SOURCE',
  '-D_XOPEN_SOURCE=700',
  '-DGGML_SCHED_MAX_COPIES=4',
]

version_h = configure_file(
  input: 'src/ggml-version.h.in',
  output: 'ggml-version.h',
  configuration: {'GGML_VERSION': meson.project_version(), 'GGML_BUILD_COMMIT': 'meson'},
)

ggml_base = static_library('ggml-base',
  'src/ggml.c',
  'src/ggml.cpp',
  'src/ggml-alloc.c',
  'src/ggml-backend.cpp',
  'src/ggml-backend-meta.cpp',
  'src/ggml-opt.cpp',
  'src/ggml-threading.cpp',
  'src/ggml-quants.c',
  'src/gguf.cpp',
  version_h,
  include_directories: inc,
  c_args: common_args,
  cpp_args: common_args,
  dependencies: [threads, libm],
  pic: true,
)

# --- CPU backend -------------------------------------------------------------
# x86-64-v3 baseline (AVX2/FMA/F16C/BMI2): portable across post-2013 x86 CPUs.
cpu_arch_args = []
cpu_sources = files(
  'src/ggml-cpu/ggml-cpu.c',
  'src/ggml-cpu/ggml-cpu.cpp',
  'src/ggml-cpu/repack.cpp',
  'src/ggml-cpu/hbm.cpp',
  'src/ggml-cpu/quants.c',
  'src/ggml-cpu/traits.cpp',
  'src/ggml-cpu/amx/amx.cpp',
  'src/ggml-cpu/amx/mmq.cpp',
  'src/ggml-cpu/tiled/tiled.cpp',
  'src/ggml-cpu/tiled/tiled-kernel.cpp',
  'src/ggml-cpu/binary-ops.cpp',
  'src/ggml-cpu/unary-ops.cpp',
  'src/ggml-cpu/vec.cpp',
  'src/ggml-cpu/ops.cpp',
  'src/ggml-cpu/llamafile/sgemm.cpp',
)
if host_machine.cpu_family() == 'x86_64'
  cpu_sources += files('src/ggml-cpu/arch/x86/quants.c', 'src/ggml-cpu/arch/x86/repack.cpp')
  if get_option('cpu_native')
    cpu_arch_args += ['-march=native']
  else
    cpu_arch_args += [
      '-msse4.2', '-mf16c', '-mfma', '-mbmi2', '-mavx', '-mavx2',
      '-DGGML_SSE42', '-DGGML_F16C', '-DGGML_FMA', '-DGGML_BMI2', '-DGGML_AVX', '-DGGML_AVX2',
    ]
  endif
endif

ggml_cpu = static_library('ggml-cpu',
  cpu_sources,
  include_directories: [inc, include_directories('src/ggml-cpu')],
  c_args: common_args + cpu_arch_args + ['-DGGML_USE_LLAMAFILE'],
  cpp_args: common_args + cpu_arch_args + ['-DGGML_USE_LLAMAFILE'],
  link_with: ggml_base,
  dependencies: [threads, libm],
  pic: true,
)

backend_defs = ['-DGGML_USE_CPU']
backend_libs = [ggml_cpu]
backend_deps = []

# --- Vulkan backend ----------------------------------------------------------
vulkan = dependency('vulkan', required: get_option('vulkan'))
if vulkan.found()
  glslc = find_program('glslc')
  spirv_headers = dependency('SPIRV-Headers', required: false)
  if not spirv_headers.found()
    spirv_headers = subproject('spirv-headers').get_variable('spirv_headers_dep')
  endif

  vk_dir = 'src/ggml-vulkan'
  shader_dir = vk_dir / 'vulkan-shaders'

  # Probe glslc for optional shader extensions (mirrors ggml's CMake feature tests).
  vk_feature_args = []
  foreach t : [
    ['coopmat', 'GL_KHR_cooperative_matrix', 'GGML_VULKAN_COOPMAT_GLSLC_SUPPORT'],
    ['coopmat2', 'GL_NV_cooperative_matrix2', 'GGML_VULKAN_COOPMAT2_GLSLC_SUPPORT'],
    ['coopmat2_decode_vector', 'GL_NV_cooperative_matrix_decode_vector', 'GGML_VULKAN_COOPMAT2_DECODE_VECTOR_GLSLC_SUPPORT'],
    ['integer_dot', 'GL_EXT_integer_dot_product', 'GGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT'],
    ['bfloat16', 'GL_EXT_bfloat16', 'GGML_VULKAN_BFLOAT16_GLSLC_SUPPORT'],
    ['float_e2m1', 'GL_EXT_float_e2m1', 'GGML_VULKAN_FLOAT_E2M1_GLSLC_SUPPORT'],
    ['float_e4m3', 'GL_EXT_float_e4m3', 'GGML_VULKAN_FLOAT_E4M3_GLSLC_SUPPORT'],
  ]
    r = run_command(glslc, '-o', '/dev/null', '-fshader-stage=compute', '--target-env=vulkan1.3',
      meson.current_source_dir() / shader_dir / 'feature-tests' / (t[0] + '.comp'), check: false)
    if not r.stderr().contains('extension not supported: ' + t[1])
      vk_feature_args += '-D' + t[2]
    endif
  endforeach
  message('ggml-vulkan glslc features: ' + ' '.join(vk_feature_args))

  shaders_gen = executable('vulkan-shaders-gen',
    shader_dir / 'vulkan-shaders-gen.cpp',
    cpp_args: vk_feature_args,
    dependencies: threads,
    native: true,
  )

  spv_out = meson.current_build_dir() / 'vulkan-shaders.spv'
  shaders_hpp = custom_target('ggml-vulkan-shaders.hpp',
    output: 'ggml-vulkan-shaders.hpp',
    command: [shaders_gen, '--output-dir', spv_out, '--target-hpp', '@OUTPUT@'],
  )

  shader_cpps = []
  foreach comp : run_command('sh', '-c', 'cd "$1" && ls *.comp', '_',
      meson.current_source_dir() / shader_dir, check: true).stdout().strip().split('\n')
    shader_cpps += custom_target(comp + '.cpp',
      input: shader_dir / comp,
      output: comp + '.cpp',
      depfile: comp + '.cpp.d',
      depends: shaders_hpp,
      command: [shaders_gen,
        '--glslc', glslc,
        '--source', '@INPUT@',
        '--output-dir', spv_out,
        '--target-hpp', shaders_hpp.full_path(),
        '--target-cpp', '@OUTPUT@',
      ],
    )
  endforeach

  ggml_vulkan = static_library('ggml-vulkan',
    vk_dir / 'ggml-vulkan.cpp',
    vk_dir / 'ggml-vulkan-buffers.cpp',
    vk_dir / 'ggml-vulkan-debug.cpp',
    shaders_hpp,
    shader_cpps,
    include_directories: [inc, include_directories(vk_dir)],
    cpp_args: common_args + vk_feature_args,
    gnu_symbol_visibility: 'hidden',
    link_with: ggml_base,
    dependencies: [vulkan, spirv_headers, threads],
    pic: true,
  )
  backend_defs += '-DGGML_USE_VULKAN'
  backend_libs += ggml_vulkan
  backend_deps += vulkan
endif

ggml_reg = static_library('ggml',
  'src/ggml-backend-dl.cpp',
  'src/ggml-backend-reg.cpp',
  include_directories: inc,
  cpp_args: common_args + backend_defs,
  link_with: [ggml_base] + backend_libs,
  dependencies: [threads, libm] + backend_deps,
  pic: true,
)

ggml_dep = declare_dependency(
  include_directories: public_inc,
  link_with: [ggml_reg, ggml_base] + backend_libs,
  dependencies: [threads, libm, cc.find_library('dl', required: false)] + backend_deps,
  compile_args: backend_defs,
)
meson.override_dependency('ggml', ggml_dep)
