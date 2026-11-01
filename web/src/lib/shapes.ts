// Chunky rounds the centre/radii to chunk coordinates before testing chunk centres.

export type ShapeKind =
  | 'square'
  | 'rectangle'
  | 'circle'
  | 'ellipse'
  | 'diamond'

export interface Selection {
  shape: ShapeKind
  x: number
  z: number
  radiusX: number
  radiusZ: number
  pattern: string
}

export function isRound(s: ShapeKind) {
  return s === 'circle' || s === 'ellipse'
}

export function isUniform(s: ShapeKind) {
  return s === 'square' || s === 'circle' || s === 'diamond'
}

export function contains(sel: Selection, bx: number, bz: number) {
  const rx = sel.radiusX
  const rz = isUniform(sel.shape) ? sel.radiusX : sel.radiusZ
  const dx = bx - sel.x
  const dz = bz - sel.z
  switch (sel.shape) {
    case 'square':
    case 'rectangle':
      return Math.abs(dx) <= rx && Math.abs(dz) <= rz
    case 'circle':
    case 'ellipse':
      return (dx * dx) / (rx * rx) + (dz * dz) / (rz * rz) <= 1
    case 'diamond':
      return Math.abs(dx) + Math.abs(dz) <= rx
  }
}

/** Calls `fn` for each chunk of the selection while there are at most `limit` candidates; else returns false. */
export function forEachChunk(
  sel: Selection,
  limit: number,
  fn: (cx: number, cz: number) => void,
) {
  sel = chunkAligned(sel)
  const rz = isUniform(sel.shape) ? sel.radiusX : sel.radiusZ
  const cx0 = Math.floor((sel.x - sel.radiusX) / 16)
  const cx1 = Math.floor((sel.x + sel.radiusX) / 16)
  const cz0 = Math.floor((sel.z - rz) / 16)
  const cz1 = Math.floor((sel.z + rz) / 16)
  if ((cx1 - cx0 + 1) * (cz1 - cz0 + 1) > limit) return false
  for (let cz = cz0; cz <= cz1; cz++)
    for (let cx = cx0; cx <= cx1; cx++)
      if (contains(sel, cx * 16 + 8, cz * 16 + 8)) fn(cx, cz)
  return true
}

/** Chunks in the selection: exact for normal sizes, from the area for huge ones. */
export function chunkAligned(sel: Selection): Selection {
  return {
    ...sel,
    x: Math.floor(sel.x / 16) * 16 + 8,
    z: Math.floor(sel.z / 16) * 16 + 8,
    radiusX: Math.ceil(sel.radiusX / 16) * 16 + 8,
    radiusZ: Math.ceil(sel.radiusZ / 16) * 16 + 8,
  }
}

export function chunkCount(sel: Selection) {
  if (sel.shape === 'square' || sel.shape === 'rectangle') {
    return (
      (2 * Math.ceil(sel.radiusX / 16) + 1) *
      (2 *
        Math.ceil((sel.shape === 'square' ? sel.radiusX : sel.radiusZ) / 16) +
        1)
    )
  }
  let n = 0
  if (forEachChunk(sel, 200_000, () => n++)) return n
  sel = chunkAligned(sel)
  const rx = sel.radiusX
  const rz = isUniform(sel.shape) ? sel.radiusX : sel.radiusZ
  const area =
    sel.shape === 'square' || sel.shape === 'rectangle'
      ? 4 * rx * rz
      : sel.shape === 'diamond'
        ? 2 * rx * rx
        : Math.PI * rx * rz
  return Math.round(area / 256)
}

/** Outline of the shape as a polygon in block coordinates. */
export function outline(sel: Selection): [number, number][] {
  const rx = sel.radiusX
  const rz = isUniform(sel.shape) ? sel.radiusX : sel.radiusZ
  const { x, z } = sel
  if (sel.shape === 'square' || sel.shape === 'rectangle')
    return [
      [x - rx, z - rz],
      [x + rx, z - rz],
      [x + rx, z + rz],
      [x - rx, z + rz],
    ]
  if (sel.shape === 'diamond')
    return [
      [x, z - rx],
      [x + rx, z],
      [x, z + rx],
      [x - rx, z],
    ]
  const pts: [number, number][] = []
  for (let i = 0; i < 96; i++) {
    const a = (i / 96) * Math.PI * 2
    pts.push([x + Math.cos(a) * rx, z + Math.sin(a) * rz])
  }
  return pts
}
