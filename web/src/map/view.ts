// The map camera: a centre in blocks and a zoom expressed as log2(blocks per CSS pixel).

export interface View {
  cx: number
  cz: number
  zoom: number
}

export const MIN_ZOOM = -4 // 16 CSS pixels per block

export function bpp(v: View) {
  return 2 ** v.zoom
}

export function toWorld(v: View, w: number, h: number, sx: number, sy: number) {
  const s = bpp(v)
  return { x: v.cx + (sx - w / 2) * s, z: v.cz + (sy - h / 2) * s }
}

export function toScreen(
  v: View,
  w: number,
  h: number,
  bx: number,
  bz: number,
) {
  const s = bpp(v)
  return { x: (bx - v.cx) / s + w / 2, y: (bz - v.cz) / s + h / 2 }
}

/** Zoom by `dz` keeping the world point under (sx, sy) fixed. */
export function zoomAt(
  v: View,
  w: number,
  h: number,
  sx: number,
  sy: number,
  dz: number,
  maxZoom: number,
): View {
  const zoom = Math.min(maxZoom, Math.max(MIN_ZOOM, v.zoom + dz))
  const p = toWorld(v, w, h, sx, sy)
  const s = 2 ** zoom
  return { zoom, cx: p.x - (sx - w / 2) * s, cz: p.z - (sy - h / 2) * s }
}

export function formatDistance(blocks: number, metersPerBlock: number) {
  const m = blocks * metersPerBlock
  return m >= 1000
    ? `${(m / 1000).toFixed(m >= 10_000 ? 0 : 1)} km`
    : `${Math.round(m)} m`
}
