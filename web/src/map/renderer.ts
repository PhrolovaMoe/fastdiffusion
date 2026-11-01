import { biomePalette } from '../lib/biomes'
import type { Dimension } from '../lib/api'
import type { Filters, MapMode, Prefs } from '../lib/prefs'
import { filtersActive } from '../lib/prefs'
import { TILE, type Tile, type TileStore } from './tiles'
import { bpp, type View } from './view'

// Two-pass WebGL2 renderer. Pass 1 draws every visible tile's data (height, biome, climate) into a screen-sized
// G-buffer, so pass 2 can shade the whole screen at once: relief from screen-space neighbours (seamless across
// tiles), colour modes, highlight filters, contours and grids are all uniforms, recomputed every frame for free.

const TILE_VS = `#version 300 es
in vec2 aPos;
uniform vec4 uRect;   // tile rectangle in device pixels: x0, y0, x1, y1
uniform vec4 uUV;     // texture sub-rectangle: u0, v0, u1, v1
uniform vec2 uSize;
out vec2 vUV;
void main() {
  vec2 s = mix(uRect.xy, uRect.zw, aPos);
  vUV = mix(uUV.xy, uUV.zw, aPos);
  gl_Position = vec4(s.x / uSize.x * 2.0 - 1.0, 1.0 - s.y / uSize.y * 2.0, 0.0, 1.0);
}`

const TILE_FS = `#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uH;
uniform highp sampler2D uA;
uniform float uMinY;
in vec2 vUV;
layout(location = 0) out vec4 oH;
layout(location = 1) out vec4 oA;
float hAt(ivec2 p) { return texelFetch(uH, clamp(p, ivec2(0), ivec2(255)), 0).r; }
void main() {
  vec2 t = vUV * 256.0 - 0.5;
  vec2 f = floor(t);
  vec2 w = t - f;
  ivec2 i = ivec2(f);
  float h = mix(mix(hAt(i), hAt(i + ivec2(1, 0)), w.x), mix(hAt(i + ivec2(0, 1)), hAt(i + ivec2(1, 1)), w.x), w.y);
  // 24-bit fixed point with 6 fractional bits.
  float v = clamp((h - uMinY + 64.0) * 64.0, 0.0, 16777215.0);
  float r = floor(v / 65536.0);
  float g = floor((v - r * 65536.0) / 256.0);
  float b = floor(v - r * 65536.0 - g * 256.0);
  oH = vec4(r, g, b, 255.0) / 255.0;
  oA = texelFetch(uA, clamp(ivec2(floor(vUV * 256.0)), ivec2(0), ivec2(255)), 0);
}`

const COMP_VS = `#version 300 es
const vec2 P[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() { gl_Position = vec4(P[gl_VertexID], 0.0, 1.0); }`

const COMP_FS = `#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uGH;
uniform highp sampler2D uGA;
uniform highp sampler2D uPal;
uniform vec2 uSize;
uniform float uBpp;        // blocks per device pixel
uniform vec2 uOrigin;      // view centre modulo 2^20 blocks (grid phase)
uniform float uMinY, uMaxY, uSea, uMpb;
uniform int uMode;
uniform float uRelief;
uniform vec3 uSun;         // towards the sun: x (east), z (south), up
uniform float uContour;
uniform float uChunkGrid, uRegionGrid;
uniform float uGrat;       // background graticule spacing in blocks
uniform int uFilter;
uniform vec2 uFH, uFT, uFR;
uniform float uFS;
uniform uint uFB;
out vec4 o;

const vec3 SLATE = vec3(0.086, 0.129, 0.173);
const vec3 GRAT = vec3(0.137, 0.192, 0.259);
const vec3 ALPENGLOW = vec3(0.957, 0.533, 0.604);

float heightAt(ivec2 p, out float cov) {
  vec4 c = texelFetch(uGH, clamp(p, ivec2(0), ivec2(uSize) - 1), 0);
  cov = c.a;
  vec3 b = floor(c.rgb * 255.0 + 0.5);
  return (b.r * 65536.0 + b.g * 256.0 + b.b) / 64.0 - 64.0 + uMinY;
}
vec4 attrAt(ivec2 p) { return floor(texelFetch(uGA, clamp(p, ivec2(0), ivec2(uSize) - 1), 0) * 255.0 + 0.5); }

vec3 ramp4(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
  t = clamp(t, 0.0, 1.0) * 3.0;
  return t < 1.0 ? mix(a, b, t) : t < 2.0 ? mix(b, c, t - 1.0) : mix(c, d, t - 2.0);
}

// Imhof-like hypsometric tints, by metres above sea level.
vec3 hyps(float e) {
  if (e < 400.0) return mix(vec3(0.58, 0.67, 0.49), vec3(0.73, 0.76, 0.56), e / 400.0);
  if (e < 1200.0) return mix(vec3(0.73, 0.76, 0.56), vec3(0.81, 0.75, 0.57), (e - 400.0) / 800.0);
  if (e < 2200.0) return mix(vec3(0.81, 0.75, 0.57), vec3(0.71, 0.63, 0.53), (e - 1200.0) / 1000.0);
  if (e < 3200.0) return mix(vec3(0.71, 0.63, 0.53), vec3(0.79, 0.77, 0.76), (e - 2200.0) / 1000.0);
  return mix(vec3(0.79, 0.77, 0.76), vec3(0.96, 0.97, 0.99), clamp((e - 3200.0) / 1000.0, 0.0, 1.0));
}

vec3 water(float depth) {
  return mix(vec3(0.56, 0.80, 0.84), vec3(0.10, 0.26, 0.47), smoothstep(0.0, 26.0, depth));
}

bool passes(float h, vec4 a, float slope) {
  if (h < uFH.x || h > uFH.y) return false;
  float t = a.g * 0.5 - 50.0;
  if (t < uFT.x || t > uFT.y) return false;
  float r = a.b * 20.0;
  if (r < uFR.x || r > uFR.y) return false;
  if (slope > uFS) return false;
  if (uFB != 0u) {
    uint id = uint(a.r);
    if (id >= 32u || (uFB & (1u << id)) == 0u) return false;
  }
  return true;
}

void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  float cov, cl, cr, cu, cd;
  float h = heightAt(p, cov);
  // Background: an unsurveyed sheet with a faint graticule.
  float wx = uOrigin.x + (gl_FragCoord.x - uSize.x * 0.5) * uBpp;
  float wz = uOrigin.y + ((uSize.y - gl_FragCoord.y) - uSize.y * 0.5) * uBpp;
  if (cov < 0.5) {
    vec2 g = abs(fract(vec2(wx, wz) / uGrat + 0.5) - 0.5) * uGrat / uBpp;
    float line = 1.0 - smoothstep(0.4, 1.2, min(g.x, g.y));
    o = vec4(mix(SLATE, GRAT, line), 1.0);
    return;
  }
  float hl = heightAt(p + ivec2(-1, 0), cl), hr = heightAt(p + ivec2(1, 0), cr);
  float hn = heightAt(p + ivec2(0, 1), cu), hs = heightAt(p + ivec2(0, -1), cd);
  if (cl < 0.5) hl = h;
  if (cr < 0.5) hr = h;
  if (cu < 0.5) hn = h;
  if (cd < 0.5) hs = h;
  float gx = (hr - hl) / (2.0 * uBpp), gz = (hs - hn) / (2.0 * uBpp);
  float slope = length(vec2(gx, gz));
  vec4 a = attrAt(p);

  float waterTop = uSea - 1.0;
  bool wet = h < waterTop - 0.5;
  float e = (h - waterTop) * uMpb;
  vec3 biomeCol = texelFetch(uPal, ivec2(int(a.r), 0), 0).rgb;
  vec3 base;
  float lightAmount = 1.0;
  if (uMode == 0) {
    base = wet ? water(waterTop - h) : mix(hyps(e), biomeCol, 0.5);
  } else if (uMode == 1) {
    base = biomeCol;
  } else if (uMode == 2) {
    float t = (h - waterTop) / max(1.0, uMaxY - waterTop);
    base = wet ? water(waterTop - h)
               : ramp4(pow(t, 0.6), vec3(0.24, 0.47, 0.32), vec3(0.83, 0.79, 0.45), vec3(0.58, 0.38, 0.25), vec3(0.97, 0.97, 0.98));
  } else if (uMode == 3) {
    float t = a.g * 0.5 - 50.0;
    base = t < 10.0 ? mix(vec3(0.17, 0.33, 0.66), vec3(0.91, 0.93, 0.95), clamp((t + 30.0) / 40.0, 0.0, 1.0))
                    : mix(vec3(0.91, 0.93, 0.95), vec3(0.78, 0.25, 0.22), clamp((t - 10.0) / 30.0, 0.0, 1.0));
    lightAmount = 0.55;
  } else if (uMode == 4) {
    base = ramp4(a.b * 20.0 / 4000.0, vec3(0.85, 0.77, 0.60), vec3(0.61, 0.77, 0.56), vec3(0.25, 0.60, 0.60), vec3(0.11, 0.31, 0.53));
    lightAmount = 0.55;
  } else {
    base = wet ? vec3(0.60, 0.66, 0.72)
               : ramp4(slope / 1.5, vec3(0.95, 0.94, 0.91), vec3(0.89, 0.69, 0.29), vec3(0.78, 0.27, 0.24), vec3(0.35, 0.12, 0.17));
    lightAmount = 0.4;
  }

  // Relief shading: warm light, cool blue shadows.
  vec3 n = normalize(vec3(-gx * uRelief, -gz * uRelief, wet ? 3.0 : 1.0));
  float k = clamp(dot(n, uSun) / max(uSun.z, 0.05), 0.0, 2.0);
  vec3 lit = base * mix(vec3(0.56, 0.63, 0.82), vec3(1.05, 1.0, 0.94), smoothstep(0.15, 1.15, k)) * (0.58 + 0.42 * min(k, 1.4));
  vec3 col = mix(base, lit, lightAmount);
  // Aerial perspective: high ground fades towards the sky.
  if (!wet && uMode == 0) col = mix(col, vec3(0.90, 0.93, 0.97), clamp((e - 1800.0) / 8000.0, 0.0, 0.22));

  // Coastline.
  bool wl = hl < waterTop - 0.5, wr = hr < waterTop - 0.5, wn = hn < waterTop - 0.5, ws = hs < waterTop - 0.5;
  if (wet != wl || wet != wr || wet != wn || wet != ws) col = mix(col, vec3(0.12, 0.24, 0.35), 0.55);

  // Contours.
  if (uContour > 0.0 && !wet) {
    float gpx = max(slope * uBpp, 1e-3);
    float idx = h / uContour;
    float dist = abs(fract(idx + 0.5) - 0.5) * uContour / gpx;
    bool major = mod(floor(idx + 0.5), 5.0) < 0.5;
    float width = major ? 1.15 : 0.55;
    float alpha = (1.0 - smoothstep(width - 0.5, width + 0.6, dist)) * clamp((uContour / gpx - 2.5) / 4.0, 0.0, 1.0);
    col = mix(col, vec3(0.33, 0.25, 0.18), alpha * (major ? 0.6 : 0.38));
  }

  // Highlight filter: matching ground stays in colour with an alpenglow edge, the rest recedes.
  if (uFilter == 1) {
    bool ok = passes(h, a, slope);
    if (!ok) {
      float lum = dot(col, vec3(0.3, 0.59, 0.11));
      col = mix(vec3(lum) * 0.42, SLATE, 0.35);
    } else {
      bool edge = !passes(hl, attrAt(p + ivec2(-1, 0)), slope) || !passes(hr, attrAt(p + ivec2(1, 0)), slope) ||
                  !passes(hn, attrAt(p + ivec2(0, 1)), slope) || !passes(hs, attrAt(p + ivec2(0, -1)), slope);
      if (edge) col = mix(col, ALPENGLOW, 0.9);
    }
  }

  // Chunk and region grids.
  if (uChunkGrid > 0.0 && 16.0 / uBpp >= 10.0) {
    vec2 g = abs(fract(vec2(wx, wz) / 16.0 + 0.5) - 0.5) * 16.0 / uBpp;
    float line = 1.0 - smoothstep(0.3, 1.0, min(g.x, g.y));
    col = mix(col, vec3(0.08, 0.1, 0.13), 0.16 * line * clamp((16.0 / uBpp - 10.0) / 10.0, 0.0, 1.0));
  }
  if (uRegionGrid > 0.0 && 512.0 / uBpp >= 12.0) {
    vec2 g = abs(fract(vec2(wx, wz) / 512.0 + 0.5) - 0.5) * 512.0 / uBpp;
    float line = 1.0 - smoothstep(0.4, 1.3, min(g.x, g.y));
    col = mix(col, vec3(0.08, 0.1, 0.13), 0.38 * line);
  }
  o = vec4(col, 1.0);
}`

const MODES: Record<MapMode, number> = {
  atlas: 0,
  biomes: 1,
  height: 2,
  temperature: 3,
  rainfall: 4,
  slope: 5,
}
const UPLOADS_PER_FRAME = 6

interface Gpu {
  h: WebGLTexture
  a: WebGLTexture
}

function compile(gl: WebGL2RenderingContext, vs: string, fs: string) {
  const prog = gl.createProgram()!
  for (const [type, src] of [
    [gl.VERTEX_SHADER, vs],
    [gl.FRAGMENT_SHADER, fs],
  ] as const) {
    const s = gl.createShader(type)!
    gl.shaderSource(s, src)
    gl.compileShader(s)
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS))
      throw new Error(gl.getShaderInfoLog(s) ?? 'shader error')
    gl.attachShader(prog, s)
  }
  gl.linkProgram(prog)
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS))
    throw new Error(gl.getProgramInfoLog(prog) ?? 'link error')
  const uniforms = new Map<string, WebGLUniformLocation | null>()
  return {
    prog,
    u(name: string) {
      if (!uniforms.has(name))
        uniforms.set(name, gl.getUniformLocation(prog, name))
      return uniforms.get(name)!
    },
  }
}

function texture(gl: WebGL2RenderingContext) {
  const t = gl.createTexture()!
  gl.bindTexture(gl.TEXTURE_2D, t)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
  return t
}

export interface Frame {
  /** Tile level drawn at this zoom. */
  level: number
  /** Tiles to load, most important first. */
  wanted: [number, number, number][]
  /** True while some visible tile is still missing or awaiting upload. */
  pending: boolean
}

export class MapRenderer {
  private gl: WebGL2RenderingContext
  private tileProg
  private compProg
  private quad: WebGLVertexArrayObject
  private empty: WebGLVertexArrayObject
  private fbo: WebGLFramebuffer
  private gH: WebGLTexture
  private gA: WebGLTexture
  private pal: WebGLTexture
  private w = 0
  private h = 0

  constructor(readonly canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl2', {
      antialias: false,
      alpha: false,
      depth: false,
      stencil: false,
    })
    if (!gl) throw new Error('This browser does not support WebGL 2.')
    this.gl = gl
    this.tileProg = compile(gl, TILE_VS, TILE_FS)
    this.compProg = compile(gl, COMP_VS, COMP_FS)

    this.quad = gl.createVertexArray()!
    gl.bindVertexArray(this.quad)
    const buf = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buf)
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]),
      gl.STATIC_DRAW,
    )
    const loc = gl.getAttribLocation(this.tileProg.prog, 'aPos')
    gl.enableVertexAttribArray(loc)
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)
    this.empty = gl.createVertexArray()!
    gl.bindVertexArray(null)

    this.fbo = gl.createFramebuffer()!
    this.gH = texture(gl)
    this.gA = texture(gl)
    this.pal = texture(gl)
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.RGBA8,
      64,
      1,
      0,
      gl.RGBA,
      gl.UNSIGNED_BYTE,
      biomePalette(),
    )
  }

  resize(w: number, h: number) {
    if (w === this.w && h === this.h) return
    const gl = this.gl
    this.w = this.canvas.width = Math.max(1, w)
    this.h = this.canvas.height = Math.max(1, h)
    for (const t of [this.gH, this.gA]) {
      gl.bindTexture(gl.TEXTURE_2D, t)
      gl.texImage2D(
        gl.TEXTURE_2D,
        0,
        gl.RGBA8,
        this.w,
        this.h,
        0,
        gl.RGBA,
        gl.UNSIGNED_BYTE,
        null,
      )
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo)
    gl.framebufferTexture2D(
      gl.FRAMEBUFFER,
      gl.COLOR_ATTACHMENT0,
      gl.TEXTURE_2D,
      this.gH,
      0,
    )
    gl.framebufferTexture2D(
      gl.FRAMEBUFFER,
      gl.COLOR_ATTACHMENT1,
      gl.TEXTURE_2D,
      this.gA,
      0,
    )
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
  }

  release(t: Tile) {
    const g = t.gpu as Gpu | undefined
    if (!g) return
    this.gl.deleteTexture(g.h)
    this.gl.deleteTexture(g.a)
    t.gpu = undefined
  }

  private upload(t: Tile) {
    const gl = this.gl
    const d = t.data!
    const h = texture(gl)
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.R32F,
      TILE,
      TILE,
      0,
      gl.RED,
      gl.FLOAT,
      d.y,
    )
    const a = texture(gl)
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.RGBA8,
      TILE,
      TILE,
      0,
      gl.RGBA,
      gl.UNSIGNED_BYTE,
      d.attrs,
    )
    t.gpu = { h, a } satisfies Gpu
  }

  /** Draws one frame. `dpr` converts CSS pixels to device pixels. */
  render(
    view: View,
    dim: Dimension,
    store: TileStore,
    p: Prefs,
    dpr: number,
  ): Frame {
    const gl = this.gl
    const W = this.w
    const H = this.h
    const bppDev = bpp(view) / dpr
    let level = Math.min(
      dim.maxZoom,
      Math.max(0, Math.round(Math.log2(bppDev) + 0.25)),
    )
    // Never more than ~150 tiles on screen.
    while (
      level < dim.maxZoom &&
      ((W * bppDev) / (TILE << level)) * ((H * bppDev) / (TILE << level)) > 150
    )
      level++

    const x0 = view.cx - (W / 2) * bppDev
    const z0 = view.cz - (H / 2) * bppDev
    const x1 = view.cx + (W / 2) * bppDev
    const z1 = view.cz + (H / 2) * bppDev
    const span = TILE * 2 ** level
    const tx0 = Math.floor(x0 / span)
    const tx1 = Math.floor(x1 / span)
    const tz0 = Math.floor(z0 / span)
    const tz1 = Math.floor(z1 / span)

    const visible: [number, number, number][] = []
    for (let ty = tz0; ty <= tz1; ty++)
      for (let tx = tx0; tx <= tx1; tx++) visible.push([level, tx, ty])
    const ccx = view.cx / span - 0.5
    const ccz = view.cz / span - 0.5
    visible.sort(
      (a, b) =>
        Math.hypot(a[1] - ccx, a[2] - ccz) - Math.hypot(b[1] - ccx, b[2] - ccz),
    )

    // Cheap coarse placeholders first while expensive detail generates.
    const wanted: [number, number, number][] = []
    const placeholder = Math.min(dim.maxZoom, dim.overviewMax + 1)
    if (level < placeholder - 1) {
      const ps = TILE * 2 ** placeholder
      for (let ty = Math.floor(z0 / ps); ty <= Math.floor(z1 / ps); ty++)
        for (let tx = Math.floor(x0 / ps); tx <= Math.floor(x1 / ps); tx++)
          wanted.push([placeholder, tx, ty])
    }
    wanted.push(...visible)

    // Draw list: for each visible tile the best available stand-in.
    let uploads = 0
    let pending = false
    const ready = (t: Tile | undefined): t is Tile => {
      if (!t?.data) return false
      if (!t.gpu) {
        if (uploads >= UPLOADS_PER_FRAME) {
          pending = true
          return false
        }
        this.upload(t)
        uploads++
      }
      return true
    }
    const ancestors = new Map<string, Tile>()
    const draws: Tile[] = []
    for (const [z, x, y] of visible) {
      const t = store.get(z, x, y)
      if (ready(t)) {
        draws.push(t)
        continue
      }
      pending = true
      // Finer children (after zooming out), then the nearest coarser ancestor.
      let children = 0
      if (z > 0)
        for (let c = 0; c < 4; c++) {
          const ch = store.get(z - 1, x * 2 + (c & 1), y * 2 + (c >> 1))
          if (ready(ch)) {
            ancestors.set(ch.key, ch)
            children++
          }
        }
      if (children === 4) continue
      for (let az = z + 1; az <= dim.maxZoom; az++) {
        const s = 2 ** (az - z)
        const a = store.get(az, Math.floor(x / s), Math.floor(y / s))
        if (ready(a)) {
          ancestors.set(a.key, a)
          break
        }
      }
    }
    const now = performance.now()
    for (const [z, x, y] of visible) {
      const t = store.get(z, x, y)
      if (t) t.used = now
    }

    // Pass 1: the G-buffer.
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo)
    gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1])
    gl.viewport(0, 0, W, H)
    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT)
    gl.useProgram(this.tileProg.prog)
    gl.bindVertexArray(this.quad)
    gl.uniform2f(this.tileProg.u('uSize'), W, H)
    gl.uniform1f(this.tileProg.u('uMinY'), dim.minY)
    gl.uniform1i(this.tileProg.u('uH'), 0)
    gl.uniform1i(this.tileProg.u('uA'), 1)
    const order = [...ancestors.values()]
      .sort((a, b) => b.z - a.z)
      .concat(draws)
    for (const t of order) {
      const s = TILE * 2 ** t.z
      // Device-pixel rectangle computed in double precision; float32 would wobble far from the origin.
      const rx0 = (t.x * s - view.cx) / bppDev + W / 2
      const ry0 = (t.y * s - view.cz) / bppDev + H / 2
      const rs = s / bppDev
      gl.uniform4f(this.tileProg.u('uRect'), rx0, ry0, rx0 + rs, ry0 + rs)
      gl.uniform4f(this.tileProg.u('uUV'), 0, 0, 1, 1)
      const g = t.gpu as Gpu
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, g.h)
      gl.activeTexture(gl.TEXTURE1)
      gl.bindTexture(gl.TEXTURE_2D, g.a)
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
    }

    // Pass 2: shading.
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
    gl.viewport(0, 0, W, H)
    gl.useProgram(this.compProg.prog)
    gl.bindVertexArray(this.empty)
    const u = (n: string) => this.compProg.u(n)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.gH)
    gl.activeTexture(gl.TEXTURE1)
    gl.bindTexture(gl.TEXTURE_2D, this.gA)
    gl.activeTexture(gl.TEXTURE2)
    gl.bindTexture(gl.TEXTURE_2D, this.pal)
    gl.uniform1i(u('uGH'), 0)
    gl.uniform1i(u('uGA'), 1)
    gl.uniform1i(u('uPal'), 2)
    gl.uniform2f(u('uSize'), W, H)
    gl.uniform1f(u('uBpp'), bppDev)
    const MOD = 2 ** 20
    gl.uniform2f(
      u('uOrigin'),
      ((view.cx % MOD) + MOD) % MOD,
      ((view.cz % MOD) + MOD) % MOD,
    )
    gl.uniform1f(u('uMinY'), dim.minY)
    gl.uniform1f(u('uMaxY'), dim.maxY)
    gl.uniform1f(u('uSea'), dim.seaLevel)
    gl.uniform1f(u('uMpb'), dim.metersPerBlock)
    gl.uniform1i(u('uMode'), MODES[p.mode])
    // Exaggerate relief gently as the view zooms out, the way printed maps generalise.
    gl.uniform1f(
      u('uRelief'),
      p.relief * (1 + 0.22 * Math.max(0, Math.log2(bppDev))),
    )
    const az = (p.sunAzimuth * Math.PI) / 180
    const alt = (p.sunAltitude * Math.PI) / 180
    gl.uniform3f(
      u('uSun'),
      Math.cos(alt) * Math.sin(az),
      -Math.cos(alt) * Math.cos(az),
      Math.sin(alt),
    )
    gl.uniform1f(u('uContour'), p.contours ? p.contourInterval : 0)
    gl.uniform1f(u('uChunkGrid'), p.chunkGrid ? 1 : 0)
    gl.uniform1f(u('uRegionGrid'), p.regionGrid ? 1 : 0)
    gl.uniform1f(u('uGrat'), 2 ** Math.ceil(Math.log2(110 * bppDev)))
    this.filterUniforms(p.filters)
    gl.drawArrays(gl.TRIANGLES, 0, 3)

    return { level, wanted, pending }
  }

  private filterUniforms(f: Filters) {
    const gl = this.gl
    const u = (n: string) => this.compProg.u(n)
    const on = filtersActive(f)
    gl.uniform1i(u('uFilter'), on ? 1 : 0)
    gl.uniform2f(u('uFH'), f.height?.min ?? -1e9, f.height?.max ?? 1e9)
    gl.uniform2f(
      u('uFT'),
      f.temperature?.min ?? -1e9,
      f.temperature?.max ?? 1e9,
    )
    gl.uniform2f(u('uFR'), f.rainfall?.min ?? -1e9, f.rainfall?.max ?? 1e9)
    gl.uniform1f(u('uFS'), f.slopeMax ?? 1e9)
    let mask = 0
    for (const b of f.biomes) if (b < 32) mask |= 1 << b
    gl.uniform1ui(u('uFB'), mask >>> 0)
  }

  dispose() {
    this.gl.getExtension('WEBGL_lose_context')?.loseContext()
  }
}
