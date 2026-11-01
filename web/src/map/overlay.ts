import type { ChunkyStatus, SearchState, Waypoint } from '../lib/api'
import { playerPosition, type LivePlayer } from '../lib/live'
import { outline, type Selection } from '../lib/shapes'
import type { GeneratedChunks } from './generated'
import { bpp, formatDistance, toScreen, type View } from './view'

// Everything drawn above the terrain, on a 2D canvas in CSS pixels.

export const COLORS = {
  alpenglow: '#f4889a',
  glacier: '#8fd3e6',
  snow: '#e9eef2',
  slate: '#1d1b21',
  ridge: '#2b2830',
}

export interface OverlayInput {
  view: View
  w: number
  h: number
  dim: string
  metersPerBlock: number
  now: number
  players: LivePlayer[]
  followed: string | null
  waypoints: Waypoint[]
  search: SearchState | null
  hoveredResult: number | null
  selection: Selection | null
  chunky: ChunkyStatus | null
  generated: GeneratedChunks | null
  measure: { ax: number; az: number; bx: number; bz: number } | null
  target: { x: number; z: number } | null
  labels: boolean
}

const FONT = '500 12px "IBM Plex Sans", system-ui, sans-serif'
const NUM_FONT = '600 11px "IBM Plex Sans", system-ui, sans-serif'

function pill(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  fg: string,
  bg: string,
) {
  ctx.font = FONT
  const w = ctx.measureText(text).width + 12
  ctx.fillStyle = bg
  ctx.beginPath()
  ctx.roundRect(x - w / 2, y - 9, w, 18, 9)
  ctx.fill()
  ctx.fillStyle = fg
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, x, y + 0.5)
}

export function drawOverlay(ctx: CanvasRenderingContext2D, o: OverlayInput) {
  const { view, w, h } = o
  ctx.clearRect(0, 0, w, h)
  const s = (bx: number, bz: number) => toScreen(view, w, h, bx, bz)
  const scale = 1 / bpp(view)

  if (o.generated && 16 * scale >= 1) {
    ctx.globalAlpha = Math.min(1, (16 * scale - 1) / 3)
    o.generated.draw(ctx, s, scale)
    ctx.globalAlpha = 1
  }

  // Running Chunky task: its shape and where it is working now.
  const c = o.chunky
  if (c && c.state === 'running' && c.x !== undefined) {
    const sel: Selection = {
      shape: (c.shape as Selection['shape']) ?? 'square',
      x: c.x,
      z: c.z!,
      radiusX: c.radiusX!,
      radiusZ: c.radiusZ ?? c.radiusX!,
      pattern: c.pattern ?? 'concentric',
    }
    polygon(
      ctx,
      outline(sel).map(([x, z]) => s(x, z)),
      COLORS.glacier,
      [6, 4],
      1.5,
    )
    if (c.chunkX !== undefined) {
      const p = s(c.chunkX * 16 + 8, c.chunkZ! * 16 + 8)
      const pulse = (Math.sin(o.now / 180) + 1) / 2
      ctx.strokeStyle = COLORS.glacier
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.arc(p.x, p.y, 5 + pulse * 6, 0, Math.PI * 2)
      ctx.stroke()
    }
  }

  // Draft selection for Chunky.
  if (o.selection) {
    const pts = outline(o.selection).map(([x, z]) => s(x, z))
    ctx.fillStyle = 'rgba(244, 136, 154, 0.10)'
    ctx.beginPath()
    pts.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)))
    ctx.closePath()
    ctx.fill()
    polygon(ctx, pts, COLORS.alpenglow, [], 2)
    const cpt = s(o.selection.x, o.selection.z)
    handle(ctx, cpt.x, cpt.y)
    const rz =
      o.selection.shape === 'rectangle' || o.selection.shape === 'ellipse'
        ? o.selection.radiusZ
        : o.selection.radiusX
    handle(ctx, s(o.selection.x + o.selection.radiusX, o.selection.z).x, cpt.y)
    if (o.selection.shape === 'rectangle' || o.selection.shape === 'ellipse')
      handle(ctx, cpt.x, s(o.selection.x, o.selection.z + rz).y)
  }

  // Search: the area, then results with sight lines towards their peaks.
  const se = o.search
  if (se && se.dim === o.dim) {
    const c0 = s(se.x, se.z)
    ctx.strokeStyle = 'rgba(233, 238, 242, 0.45)'
    ctx.setLineDash([3, 5])
    ctx.lineWidth = 1.25
    ctx.beginPath()
    ctx.arc(c0.x, c0.y, se.radius * scale, 0, Math.PI * 2)
    ctx.stroke()
    ctx.setLineDash([])
    const results = se.results ?? []
    results.forEach((r, i) => {
      const p = s(r.x, r.z)
      const q = s(r.peakX, r.peakZ)
      const hot = o.hoveredResult === i
      ctx.strokeStyle = hot ? COLORS.alpenglow : 'rgba(244, 136, 154, 0.55)'
      ctx.lineWidth = hot ? 2 : 1.25
      ctx.setLineDash([2, 3])
      ctx.beginPath()
      ctx.moveTo(p.x, p.y)
      ctx.lineTo(q.x, q.y)
      ctx.stroke()
      ctx.setLineDash([])
      ctx.fillStyle = hot ? COLORS.alpenglow : 'rgba(244, 136, 154, 0.8)'
      ctx.beginPath()
      ctx.moveTo(q.x, q.y - 6)
      ctx.lineTo(q.x + 5, q.y + 3)
      ctx.lineTo(q.x - 5, q.y + 3)
      ctx.closePath()
      ctx.fill()
    })
    results.forEach((r, i) => {
      const p = s(r.x, r.z)
      const hot = o.hoveredResult === i
      ctx.fillStyle = hot ? COLORS.alpenglow : COLORS.slate
      ctx.strokeStyle = COLORS.alpenglow
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.arc(p.x, p.y, hot ? 12 : 10, 0, Math.PI * 2)
      ctx.fill()
      ctx.stroke()
      ctx.fillStyle = hot ? COLORS.slate : COLORS.snow
      ctx.font = NUM_FONT
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText(String(i + 1), p.x, p.y + 0.5)
    })
  }

  // Waypoints.
  for (const wp of o.waypoints) {
    if (wp.dim !== o.dim) continue
    const p = s(wp.x + 0.5, wp.z + 0.5)
    if (p.x < -50 || p.y < -50 || p.x > w + 50 || p.y > h + 50) continue
    ctx.fillStyle = wp.color
    ctx.strokeStyle = COLORS.slate
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.moveTo(p.x, p.y)
    ctx.lineTo(p.x - 7, p.y - 11)
    ctx.arc(p.x, p.y - 15, 7.5, Math.PI * 0.8, Math.PI * 0.2)
    ctx.closePath()
    ctx.stroke()
    ctx.fill()
    if (o.labels)
      pill(ctx, wp.name, p.x, p.y - 32, COLORS.snow, 'rgba(29, 27, 33, 0.86)')
  }

  // Measure line.
  if (o.measure) {
    const a = s(o.measure.ax, o.measure.az)
    const b = s(o.measure.bx, o.measure.bz)
    ctx.strokeStyle = COLORS.snow
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.moveTo(a.x, a.y)
    ctx.lineTo(b.x, b.y)
    ctx.stroke()
    for (const p of [a, b]) {
      ctx.fillStyle = COLORS.slate
      ctx.beginPath()
      ctx.arc(p.x, p.y, 4, 0, Math.PI * 2)
      ctx.fill()
      ctx.stroke()
    }
    const d = Math.hypot(
      o.measure.bx - o.measure.ax,
      o.measure.bz - o.measure.az,
    )
    pill(
      ctx,
      `${Math.round(d).toLocaleString()} blocks, ${formatDistance(d, o.metersPerBlock)}`,
      (a.x + b.x) / 2,
      (a.y + b.y) / 2 - 14,
      COLORS.slate,
      COLORS.snow,
    )
  }

  // Teleport target.
  if (o.target) {
    const p = s(o.target.x + 0.5, o.target.z + 0.5)
    ctx.strokeStyle = COLORS.alpenglow
    ctx.lineWidth = 2
    for (const r of [6, 13]) {
      ctx.beginPath()
      ctx.arc(p.x, p.y, r, 0, Math.PI * 2)
      ctx.stroke()
    }
  }

  // Players last, always on top.
  for (const pl of o.players) {
    if (pl.dim !== o.dim) continue
    const at = playerPosition(pl, o.now)
    const p = s(at.x, at.z)
    if (p.x < -60 || p.y < -60 || p.x > w + 60 || p.y > h + 60) continue
    const followed = o.followed === pl.uuid
    // Minecraft yaw: 0 faces south (+Z), 90 west (-X).
    const a = (at.yaw * Math.PI) / 180
    const dx = -Math.sin(a)
    const dz = Math.cos(a)
    ctx.save()
    ctx.translate(p.x, p.y)
    ctx.rotate(Math.atan2(dz, dx))
    ctx.fillStyle = followed ? COLORS.alpenglow : COLORS.snow
    ctx.strokeStyle = COLORS.slate
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.moveTo(11, 0)
    ctx.lineTo(-7, 7)
    ctx.lineTo(-3, 0)
    ctx.lineTo(-7, -7)
    ctx.closePath()
    ctx.stroke()
    ctx.fill()
    ctx.restore()
    if (o.labels || followed)
      pill(
        ctx,
        pl.name,
        p.x,
        p.y + 20,
        COLORS.slate,
        followed ? COLORS.alpenglow : COLORS.snow,
      )
  }
}

function polygon(
  ctx: CanvasRenderingContext2D,
  pts: { x: number; y: number }[],
  color: string,
  dash: number[],
  width: number,
) {
  ctx.strokeStyle = color
  ctx.lineWidth = width
  ctx.setLineDash(dash)
  ctx.beginPath()
  pts.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)))
  ctx.closePath()
  ctx.stroke()
  ctx.setLineDash([])
}

function handle(ctx: CanvasRenderingContext2D, x: number, y: number) {
  ctx.fillStyle = COLORS.snow
  ctx.strokeStyle = COLORS.alpenglow
  ctx.lineWidth = 2
  ctx.beginPath()
  ctx.arc(x, y, 6, 0, Math.PI * 2)
  ctx.fill()
  ctx.stroke()
}
