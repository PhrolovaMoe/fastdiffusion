import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { BIOMES, hexToRgb } from '../lib/biomes'
import type { Dimension } from '../lib/api'
import type { Preview3D } from '../lib/ui'
import { sampleArea, type TileStore } from './tiles'

// A small orbitable 3D model of an area, built from the same tiles the map shows. Blocks are cubes, so
// a vertical scale of 1 is the true shape of the land.

const N = 192

const VS = `#version 300 es
in vec3 aPos;
in vec3 aNormal;
in vec3 aColor;
uniform mat4 uMvp;
uniform float uExag;
out vec3 vNormal;
out vec3 vColor;
out float vDepth;
void main() {
  vec3 p = vec3(aPos.x, aPos.y * uExag, aPos.z);
  gl_Position = uMvp * vec4(p, 1.0);
  vNormal = normalize(vec3(aNormal.x * uExag, aNormal.y, aNormal.z * uExag));
  vColor = aColor;
  vDepth = gl_Position.w;
}`

const FS = `#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vColor;
in float vDepth;
uniform vec3 uSun;
uniform float uFog;
uniform vec4 uTint;
out vec4 o;
void main() {
  float k = max(dot(normalize(vNormal), uSun), 0.0);
  vec3 c = vColor * mix(vec3(0.42, 0.48, 0.66), vec3(1.08, 1.02, 0.94), k);
  c = mix(c, vec3(0.74, 0.80, 0.88), clamp(vDepth / uFog, 0.0, 1.0) * 0.45);
  o = vec4(mix(c, uTint.rgb, uTint.a), 1.0);
}`

function mat4Perspective(
  fovy: number,
  aspect: number,
  near: number,
  far: number,
) {
  const f = 1 / Math.tan(fovy / 2)
  const nf = 1 / (near - far)
  return [
    f / aspect,
    0,
    0,
    0,
    0,
    f,
    0,
    0,
    0,
    0,
    (far + near) * nf,
    -1,
    0,
    0,
    2 * far * near * nf,
    0,
  ]
}

function mat4LookAt(eye: number[], target: number[]) {
  const z = norm([eye[0] - target[0], eye[1] - target[1], eye[2] - target[2]])
  const x = norm(cross([0, 1, 0], z))
  const y = cross(z, x)
  return [
    x[0],
    y[0],
    z[0],
    0,
    x[1],
    y[1],
    z[1],
    0,
    x[2],
    y[2],
    z[2],
    0,
    -dot(x, eye),
    -dot(y, eye),
    -dot(z, eye),
    1,
  ]
}

function mul(a: number[], b: number[]) {
  const o = new Array(16).fill(0)
  for (let c = 0; c < 4; c++)
    for (let r = 0; r < 4; r++)
      for (let k = 0; k < 4; k++) o[c * 4 + r] += a[k * 4 + r] * b[c * 4 + k]
  return o
}

const dot = (a: number[], b: number[]) =>
  a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
const cross = (a: number[], b: number[]) => [
  a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2],
  a[0] * b[1] - a[1] * b[0],
]
const norm = (a: number[]) => {
  const l = Math.hypot(a[0], a[1], a[2]) || 1
  return [a[0] / l, a[1] / l, a[2] / l]
}

export function Terrain3D({
  dim,
  store,
  area,
  onClose,
}: {
  dim: Dimension
  store: TileStore
  area: Preview3D
  onClose: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [status, setStatus] = useState('Loading terrain…')
  const [exag, setExag] = useState(1)
  const exagRef = useRef(exag)
  exagRef.current = exag

  useEffect(() => {
    const canvas = document.createElement('canvas')
    ref.current!.append(canvas)
    const gl = canvas.getContext('webgl2', { antialias: true })
    if (!gl) {
      setStatus('This browser does not support WebGL 2.')
      return () => canvas.remove()
    }
    let disposed = false
    let raf = 0
    const cam = { yaw: -0.6, pitch: 0.5, dist: 1.55 }
    const draw = { fn: () => {} }

    void sampleArea(store, area.x0, area.z0, area.x1, area.z1, N)
      .then(({ y, biome }) => {
        if (disposed) return
        setStatus('')
        const span = Math.max(area.x1 - area.x0, area.z1 - area.z0)
        const sea = dim.seaLevel - 1
        let ymin = Infinity
        for (const v of y) ymin = Math.min(ymin, v)
        const base = Math.min(ymin, sea) - span * 0.02
        // Vertices in units of the area's span, centred; water is drawn flat at sea level.
        const pos = new Float32Array(N * N * 3)
        const col = new Float32Array(N * N * 3)
        const nor = new Float32Array(N * N * 3)
        const cell = 1 / (N - 1)
        const at = (r: number, c: number) =>
          Math.max(
            sea,
            y[
              Math.min(N - 1, Math.max(0, r)) * N +
                Math.min(N - 1, Math.max(0, c))
            ],
          )
        for (let r = 0; r < N; r++)
          for (let c = 0; c < N; c++) {
            const i = r * N + c
            pos.set(
              [
                ((c * cell - 0.5) * (area.x1 - area.x0)) / span,
                (Math.max(y[i], sea) - base) / span,
                ((r * cell - 0.5) * (area.z1 - area.z0)) / span,
              ],
              i * 3,
            )
            const gx =
              (at(r, c + 1) - at(r, c - 1)) / (2 * cell * (area.x1 - area.x0))
            const gz =
              (at(r + 1, c) - at(r - 1, c)) / (2 * cell * (area.z1 - area.z0))
            nor.set(norm([-gx, 1, -gz]), i * 3)
            const wet = y[i] < sea - 0.5
            const [cr, cg, cb] = wet
              ? [52, 104, 150]
              : hexToRgb(BIOMES[biome[i]]?.color ?? '#888888')
            col.set([cr / 255, cg / 255, cb / 255], i * 3)
          }
        build(pos, nor, col)
      })
      .catch(() => {
        if (!disposed)
          setStatus(
            'Could not load this area. Try again once the map has loaded it.',
          )
      })

    const build = (pos: Float32Array, nor: Float32Array, col: Float32Array) => {
      const prog = gl.createProgram()!
      for (const [t, src] of [
        [gl.VERTEX_SHADER, VS],
        [gl.FRAGMENT_SHADER, FS],
      ] as const) {
        const s = gl.createShader(t)!
        gl.shaderSource(s, src)
        gl.compileShader(s)
        gl.attachShader(prog, s)
      }
      gl.linkProgram(prog)
      const vao = gl.createVertexArray()
      gl.bindVertexArray(vao)
      for (const [name, data] of [
        ['aPos', pos],
        ['aNormal', nor],
        ['aColor', col],
      ] as const) {
        const b = gl.createBuffer()
        gl.bindBuffer(gl.ARRAY_BUFFER, b)
        gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
        const loc = gl.getAttribLocation(prog, name)
        gl.enableVertexAttribArray(loc)
        gl.vertexAttribPointer(loc, 3, gl.FLOAT, false, 0, 0)
      }
      const idx = new Uint32Array((N - 1) * (N - 1) * 6)
      let k = 0
      for (let r = 0; r < N - 1; r++)
        for (let c = 0; c < N - 1; c++) {
          const i = r * N + c
          idx.set([i, i + N, i + 1, i + 1, i + N, i + N + 1], k)
          k += 6
        }
      const ib = gl.createBuffer()
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib)
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW)
      gl.enable(gl.DEPTH_TEST)
      const u = (n: string) => gl.getUniformLocation(prog, n)

      draw.fn = () => {
        const r = canvas.getBoundingClientRect()
        const dpr = window.devicePixelRatio || 1
        canvas.width = Math.round(r.width * dpr)
        canvas.height = Math.round(r.height * dpr)
        gl.viewport(0, 0, canvas.width, canvas.height)
        gl.clearColor(0.082, 0.078, 0.094, 1)
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
        gl.useProgram(prog)
        const eye = [
          Math.cos(cam.pitch) * Math.sin(cam.yaw) * cam.dist,
          Math.sin(cam.pitch) * cam.dist,
          Math.cos(cam.pitch) * Math.cos(cam.yaw) * cam.dist,
        ]
        const mvp = mul(
          mat4Perspective(0.8, canvas.width / canvas.height, 0.01, 20),
          mat4LookAt(eye, [0, 0.05, 0]),
        )
        gl.uniformMatrix4fv(u('uMvp'), false, mvp)
        gl.uniform1f(u('uExag'), exagRef.current)
        gl.uniform3fv(u('uSun'), norm([-0.6, 0.7, -0.5]))
        gl.uniform1f(u('uFog'), cam.dist * 2.2)
        gl.uniform4f(u('uTint'), 0, 0, 0, 0)
        gl.bindVertexArray(vao)
        gl.drawElements(gl.TRIANGLES, idx.length, gl.UNSIGNED_INT, 0)
      }
      const loop = () => {
        draw.fn()
        raf = requestAnimationFrame(loop)
      }
      loop()
    }

    // Orbit controls.
    let dragging: { x: number; y: number; yaw: number; pitch: number } | null =
      null
    const down = (e: PointerEvent) => {
      canvas.setPointerCapture(e.pointerId)
      dragging = { x: e.clientX, y: e.clientY, yaw: cam.yaw, pitch: cam.pitch }
    }
    const move = (e: PointerEvent) => {
      if (!dragging) return
      cam.yaw = dragging.yaw - (e.clientX - dragging.x) * 0.008
      cam.pitch = Math.min(
        1.45,
        Math.max(0.08, dragging.pitch + (e.clientY - dragging.y) * 0.006),
      )
    }
    const up = () => {
      dragging = null
    }
    const wheel = (e: WheelEvent) => {
      e.preventDefault()
      cam.dist = Math.min(
        4,
        Math.max(0.35, cam.dist * Math.exp(e.deltaY * 0.001)),
      )
    }
    canvas.addEventListener('pointerdown', down)
    canvas.addEventListener('pointermove', move)
    canvas.addEventListener('pointerup', up)
    canvas.addEventListener('wheel', wheel, { passive: false })
    return () => {
      disposed = true
      cancelAnimationFrame(raf)
      canvas.removeEventListener('pointerdown', down)
      canvas.removeEventListener('pointermove', move)
      canvas.removeEventListener('pointerup', up)
      canvas.removeEventListener('wheel', wheel)
      gl.getExtension('WEBGL_lose_context')?.loseContext()
      canvas.remove()
    }
  }, [area, dim, store])

  const span = Math.round(Math.max(area.x1 - area.x0, area.z1 - area.z0))
  return (
    <div
      className="preview3d"
      role="dialog"
      aria-label={`3D view of ${area.title}`}
    >
      <header>
        <div>
          <h2>{area.title}</h2>
          <p>
            {span.toLocaleString()} blocks across. Drag to orbit, scroll to
            zoom.
          </p>
        </div>
        <label className="preview3d-exag">
          Vertical scale
          <input
            type="range"
            min={1}
            max={3}
            step={0.1}
            value={exag}
            style={{ '--fill': `${((exag - 1) / 2) * 100}%` } as CSSProperties}
            onChange={(e) => setExag(+e.target.value)}
          />
          <span>{exag.toFixed(1)}×</span>
        </label>
        <button className="btn" onClick={onClose}>
          Close
        </button>
      </header>
      <div className="preview3d-stage" ref={ref} />
      {status && <p className="preview3d-status">{status}</p>}
    </div>
  )
}
