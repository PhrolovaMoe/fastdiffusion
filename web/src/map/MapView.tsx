import { useEffect, useRef } from 'react'
import { api, type Dimension } from '../lib/api'
import { live, liveEvents, playerPosition } from '../lib/live'
import { prefs } from '../lib/prefs'
import { isUniform } from '../lib/shapes'
import { camera, ui } from '../lib/ui'
import { GeneratedChunks } from './generated'
import { drawOverlay } from './overlay'
import { MapRenderer } from './renderer'
import { TileStore } from './tiles'
import { MIN_ZOOM, toScreen, toWorld, zoomAt, type View } from './view'

// The map surface: WebGL terrain underneath, a 2D overlay on top, and all pointer interaction.

const CLIENT_ID = Math.random().toString(36).slice(2)
const reducedMotion = () =>
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

interface Drag {
  kind:
    | 'pan'
    | 'measure'
    | 'select-new'
    | 'select-move'
    | 'select-rx'
    | 'select-rz'
  sx: number
  sy: number
  view: View
  x: number
  z: number
}

export function MapView({
  dim,
  onView,
}: {
  dim: Dimension
  onView: (v: View) => void
}) {
  const ovRef = useRef<HTMLCanvasElement>(null)
  const errorRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const ov = ovRef.current!
    const glCanvas = document.createElement('canvas')
    glCanvas.className = 'map-gl'
    ov.before(glCanvas)
    let renderer: MapRenderer
    try {
      renderer = new MapRenderer(glCanvas)
    } catch (e) {
      errorRef.current!.textContent = e instanceof Error ? e.message : String(e)
      errorRef.current!.hidden = false
      return () => glCanvas.remove()
    }
    const ctx = ov.getContext('2d')!
    let dirty = true
    const markDirty = () => {
      dirty = true
    }
    const store = new TileStore(dim, markDirty)
    store.onEvict = (t) => renderer.release(t)
    const generated = new GeneratedChunks(dim.id, markDirty)
    mapContext.store = store
    mapContext.generated = generated

    let w = 1
    let h = 1
    let dpr = window.devicePixelRatio || 1
    const resize = () => {
      const r = ov.getBoundingClientRect()
      w = Math.max(1, r.width)
      h = Math.max(1, r.height)
      dpr = window.devicePixelRatio || 1
      ov.width = Math.round(w * dpr)
      ov.height = Math.round(h * dpr)
      renderer.resize(Math.round(w * dpr), Math.round(h * dpr))
      dirty = true
    }
    const ro = new ResizeObserver(resize)
    ro.observe(ov)
    resize()

    const view = () => ui.get().view
    const setView = (v: View) => {
      const zoom = Math.min(dim.maxZoom + 2, Math.max(MIN_ZOOM, v.zoom))
      ui.set({ view: { ...v, zoom } })
    }

    // Camera flights.
    let flight: { from: View; to: View; t0: number; ms: number } | null = null
    camera.flyTo = (x, z, zoom) => {
      const to = { cx: x, cz: z, zoom: zoom ?? Math.min(view().zoom, 1) }
      if (reducedMotion()) {
        setView(to)
        return
      }
      flight = { from: view(), to, t0: performance.now(), ms: 700 }
    }

    // Viewport reports let the server drop tile work nobody is looking at.
    let viewportTimer = 0
    let lastWanted = ''
    let lastLevel = -1
    const reportViewport = () => {
      clearTimeout(viewportTimer)
      viewportTimer = window.setTimeout(() => {
        const a = toWorld(view(), w, h, 0, 0)
        const b = toWorld(view(), w, h, w, h)
        void api('viewport', {
          method: 'POST',
          json: {
            client: CLIENT_ID,
            dim: dim.id,
            x0: a.x,
            z0: a.z,
            x1: b.x,
            z1: b.z,
          },
        }).catch(() => {})
        const p = prefs.get()
        if ((p.generated || ui.get().selection) && view().zoom <= 3)
          void generated.ensure(a.x, a.z, b.x, b.z)
      }, 200)
    }

    let viewTimer = 0
    const unsubUi = ui.subscribe(() => {
      dirty = true
      reportViewport()
      clearTimeout(viewTimer)
      viewTimer = window.setTimeout(() => onView(view()), 300)
    })
    const unsubPrefs = prefs.subscribe(() => {
      dirty = true
      reportViewport()
    })
    const unsubLive = live.subscribe(markDirty)
    const unsubTile = liveEvents.on('tile', (t) => {
      if (t.dim === dim.id) store.refresh(t.z, t.x, t.y)
    })
    const unsubChunks = liveEvents.on('chunks', (c) => {
      if (c.dim === dim.id) generated.addMany(c.chunks)
    })

    // Frame loop.
    let raf = 0
    const frame = (now: number) => {
      raf = requestAnimationFrame(frame)
      const s = ui.get()
      if (flight) {
        const k = Math.min(1, (now - flight.t0) / flight.ms)
        const e = k < 0.5 ? 4 * k * k * k : 1 - (-2 * k + 2) ** 3 / 2
        const { from, to } = flight
        // Zoom out a little mid-flight when travelling far, so the journey stays legible.
        const far = Math.log2(
          Math.max(
            1,
            Math.hypot(to.cx - from.cx, to.cz - from.cz) / (w * 2 ** from.zoom),
          ),
        )
        const bump = Math.sin(Math.PI * e) * Math.min(3, far)
        setView({
          cx: from.cx + (to.cx - from.cx) * e,
          cz: from.cz + (to.cz - from.cz) * e,
          zoom: from.zoom + (to.zoom - from.zoom) * e + bump,
        })
        if (k >= 1) flight = null
      }
      const followed = s.followed ? live.get().players[s.followed] : null
      if (followed && followed.dim === dim.id && !flight) {
        const at = playerPosition(followed, now)
        if (
          Math.abs(at.x - s.view.cx) > 0.01 ||
          Math.abs(at.z - s.view.cz) > 0.01
        )
          ui.set({ view: { ...s.view, cx: at.x, cz: at.z } })
      }
      const players = Object.values(live.get().players)
      const moving = players.some((p) => now - p.t < 200)
      const chunky = live.get().chunky[dim.id] ?? null
      const animating = moving || chunky?.state === 'running'
      if (!dirty && !animating) return
      const v = ui.get().view
      if (dirty) {
        const f = renderer.render(v, dim, store, prefs.get(), dpr)
        const key = f.wanted.map((k) => k.join('/')).join(',')
        if (key !== lastWanted) {
          lastWanted = key
          store.want(f.wanted)
        }
        lastLevel = f.level
        dirty = f.pending
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      drawOverlay(ctx, {
        view: v,
        w,
        h,
        dim: dim.id,
        metersPerBlock: dim.metersPerBlock,
        now,
        players,
        followed: s.followed,
        waypoints: live.get().waypoints,
        search: s.activeSearch
          ? (live.get().searches[s.activeSearch] ?? null)
          : null,
        hoveredResult: s.hoveredResult,
        selection: s.selection,
        chunky,
        generated: prefs.get().generated || s.selection ? generated : null,
        measure: s.measure,
        target: s.target,
        labels: prefs.get().labels,
      })
    }
    raf = requestAnimationFrame(frame)

    // ---- Pointer interaction ----------------------------------------------------------------
    const pointers = new Map<number, { x: number; y: number }>()
    let drag: Drag | null = null
    let pinch: { d: number; view: View; mx: number; my: number } | null = null
    let moved = false

    const local = (e: PointerEvent | MouseEvent | WheelEvent) => {
      const r = ov.getBoundingClientRect()
      return { x: e.clientX - r.left, y: e.clientY - r.top }
    }

    const hover = (sx: number, sy: number) => {
      const p = toWorld(view(), w, h, sx, sy)
      const x = Math.floor(p.x)
      const z = Math.floor(p.z)
      ui.set({
        hover: { x, z, sample: store.sample(x + 0.5, z + 0.5, lastLevel) },
      })
    }

    const selectionHit = (sx: number, sy: number): Drag['kind'] | null => {
      const sel = ui.get().selection
      if (!sel) return null
      const c = toScreen(view(), w, h, sel.x, sel.z)
      const rx = toScreen(view(), w, h, sel.x + sel.radiusX, sel.z)
      const rz = toScreen(view(), w, h, sel.x, sel.z + sel.radiusZ)
      if (Math.hypot(sx - rx.x, sy - rx.y) < 10) return 'select-rx'
      if (!isUniform(sel.shape) && Math.hypot(sx - rz.x, sy - rz.y) < 10)
        return 'select-rz'
      if (Math.hypot(sx - c.x, sy - c.y) < 12) return 'select-move'
      return null
    }

    const onDown = (e: PointerEvent) => {
      if (e.button === 2) return
      ov.setPointerCapture(e.pointerId)
      const p = local(e)
      pointers.set(e.pointerId, p)
      ui.set({ menu: null })
      if (pointers.size === 2) {
        const [a, b] = [...pointers.values()]
        pinch = {
          d: Math.hypot(a.x - b.x, a.y - b.y),
          view: view(),
          mx: (a.x + b.x) / 2,
          my: (a.y + b.y) / 2,
        }
        drag = null
        return
      }
      moved = false
      const wp = toWorld(view(), w, h, p.x, p.y)
      const tool = ui.get().tool
      const hit = selectionHit(p.x, p.y)
      let kind: Drag['kind'] = 'pan'
      if (hit) kind = hit
      else if (tool === 'measure') {
        kind = 'measure'
        ui.set({ measure: { ax: wp.x, az: wp.z, bx: wp.x, bz: wp.z } })
      } else if (tool === 'select' && e.button === 0) {
        kind = 'select-new'
        const sel = ui.get().selection
        ui.set({
          selection: {
            shape: sel?.shape ?? 'square',
            pattern: sel?.pattern ?? 'concentric',
            x: Math.round(wp.x),
            z: Math.round(wp.z),
            radiusX: 16,
            radiusZ: 16,
          },
        })
      }
      drag = { kind, sx: p.x, sy: p.y, view: view(), x: wp.x, z: wp.z }
      if (kind === 'pan') ui.set({ followed: null })
    }

    const onMove = (e: PointerEvent) => {
      const p = local(e)
      if (pointers.has(e.pointerId)) pointers.set(e.pointerId, p)
      if (pinch && pointers.size === 2) {
        const [a, b] = [...pointers.values()]
        const d = Math.hypot(a.x - b.x, a.y - b.y)
        const mx = (a.x + b.x) / 2
        const my = (a.y + b.y) / 2
        let v = zoomAt(
          pinch.view,
          w,
          h,
          pinch.mx,
          pinch.my,
          -Math.log2(d / pinch.d),
          dim.maxZoom + 2,
        )
        const s = 2 ** v.zoom
        v = {
          ...v,
          cx: v.cx - (mx - pinch.mx) * s,
          cz: v.cz - (my - pinch.my) * s,
        }
        setView(v)
        return
      }
      if (!drag) {
        hover(p.x, p.y)
        return
      }
      if (Math.hypot(p.x - drag.sx, p.y - drag.sy) > 3) moved = true
      const wp = toWorld(drag.view, w, h, p.x, p.y)
      const sel = ui.get().selection
      switch (drag.kind) {
        case 'pan': {
          const s = 2 ** drag.view.zoom
          setView({
            ...drag.view,
            cx: drag.view.cx - (p.x - drag.sx) * s,
            cz: drag.view.cz - (p.y - drag.sy) * s,
          })
          break
        }
        case 'measure':
          ui.set((u) => ({
            measure: u.measure && { ...u.measure, bx: wp.x, bz: wp.z },
          }))
          break
        case 'select-new':
          if (sel) {
            const rx = Math.max(16, Math.round(Math.abs(wp.x - sel.x)))
            const rz = Math.max(16, Math.round(Math.abs(wp.z - sel.z)))
            ui.set({
              selection: {
                ...sel,
                radiusX: isUniform(sel.shape) ? Math.max(rx, rz) : rx,
                radiusZ: rz,
              },
            })
          }
          break
        case 'select-move':
          if (sel)
            ui.set({
              selection: { ...sel, x: Math.round(wp.x), z: Math.round(wp.z) },
            })
          break
        case 'select-rx':
          if (sel)
            ui.set({
              selection: {
                ...sel,
                radiusX: Math.max(16, Math.round(Math.abs(wp.x - sel.x))),
              },
            })
          break
        case 'select-rz':
          if (sel)
            ui.set({
              selection: {
                ...sel,
                radiusZ: Math.max(16, Math.round(Math.abs(wp.z - sel.z))),
              },
            })
          break
      }
      hover(p.x, p.y)
    }

    const onUp = (e: PointerEvent) => {
      pointers.delete(e.pointerId)
      if (pointers.size < 2) pinch = null
      const d = drag
      drag = null
      if (!d || moved) return
      const p = local(e)
      const wp = toWorld(view(), w, h, p.x, p.y)
      if (ui.get().tool === 'teleport')
        ui.set({ target: { x: Math.floor(wp.x), z: Math.floor(wp.z) } })
    }

    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const p = local(e)
      const unit = e.deltaMode === 1 ? 33 : e.deltaMode === 2 ? 400 : 1
      const dz = Math.max(-1, Math.min(1, (e.deltaY * unit) / 300))
      setView(zoomAt(view(), w, h, p.x, p.y, dz, dim.maxZoom + 2))
      flight = null
    }

    const onDbl = (e: MouseEvent) => {
      const p = local(e)
      const v = zoomAt(
        view(),
        w,
        h,
        p.x,
        p.y,
        e.shiftKey ? 1 : -1,
        dim.maxZoom + 2,
      )
      if (reducedMotion()) setView(v)
      else flight = { from: view(), to: v, t0: performance.now(), ms: 250 }
    }

    const onMenu = (e: MouseEvent) => {
      e.preventDefault()
      const p = local(e)
      const wp = toWorld(view(), w, h, p.x, p.y)
      ui.set({
        menu: { sx: p.x, sy: p.y, x: Math.floor(wp.x), z: Math.floor(wp.z) },
      })
    }

    const onLeave = () => ui.set({ hover: null })

    ov.addEventListener('pointerdown', onDown)
    ov.addEventListener('pointermove', onMove)
    ov.addEventListener('pointerup', onUp)
    ov.addEventListener('pointercancel', onUp)
    ov.addEventListener('wheel', onWheel, { passive: false })
    ov.addEventListener('dblclick', onDbl)
    ov.addEventListener('contextmenu', onMenu)
    ov.addEventListener('pointerleave', onLeave)

    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
      unsubUi()
      unsubPrefs()
      unsubLive()
      unsubTile()
      unsubChunks()
      clearTimeout(viewportTimer)
      clearTimeout(viewTimer)
      ov.removeEventListener('pointerdown', onDown)
      ov.removeEventListener('pointermove', onMove)
      ov.removeEventListener('pointerup', onUp)
      ov.removeEventListener('pointercancel', onUp)
      ov.removeEventListener('wheel', onWheel)
      ov.removeEventListener('dblclick', onDbl)
      ov.removeEventListener('contextmenu', onMenu)
      ov.removeEventListener('pointerleave', onLeave)
      camera.flyTo = () => {}
      store.dispose()
      renderer.dispose()
      glCanvas.remove()
      mapContext.store = null
      mapContext.generated = null
    }
  }, [dim.id, dim.fingerprint])

  return (
    <div className="map">
      <canvas
        ref={ovRef}
        className={`map-overlay tool-${ui.use((s) => s.tool)}`}
        tabIndex={-1}
        aria-label="Map"
      />
      <div ref={errorRef} className="map-error" hidden />
    </div>
  )
}

/** The mounted map's tile store, for previews that sample terrain. */
export const mapContext: {
  store: TileStore | null
  generated: GeneratedChunks | null
} = { store: null, generated: null }
