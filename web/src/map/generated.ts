import { api } from '../lib/api'
import type { SavedChunks } from '../lib/api'

// Chunks that exist on disk, per 32x32-chunk region file, plus chunks generated since (streamed live).
// Each region is a tiny 32x32 canvas, scaled up when drawn.

interface Region {
  mask: Uint8Array
  canvas: HTMLCanvasElement | null
  dirty: boolean
}

export class GeneratedChunks {
  private regions = new Map<string, Region>()
  private fetched = new Set<string>()
  private inflight = false
  bytesPerChunk = 0

  constructor(
    readonly dim: string,
    private readonly onChange: () => void,
  ) {}

  private region(rx: number, rz: number): Region {
    const key = `${rx},${rz}`
    let r = this.regions.get(key)
    if (!r)
      this.regions.set(
        key,
        (r = { mask: new Uint8Array(128), canvas: null, dirty: true }),
      )
    return r
  }

  has(cx: number, cz: number) {
    const r = this.regions.get(`${cx >> 5},${cz >> 5}`)
    if (!r) return false
    const i = ((cz & 31) << 5) | (cx & 31)
    return (r.mask[i >> 3] & (1 << (i & 7))) !== 0
  }

  add(cx: number, cz: number) {
    const r = this.region(cx >> 5, cz >> 5)
    const i = ((cz & 31) << 5) | (cx & 31)
    r.mask[i >> 3] |= 1 << (i & 7)
    r.dirty = true
  }

  addMany(flat: number[]) {
    for (let i = 0; i + 1 < flat.length; i += 2) this.add(flat[i], flat[i + 1])
    this.onChange()
  }

  /** Loads region headers for a block rectangle (skips regions already loaded). */
  async ensure(x0: number, z0: number, x1: number, z1: number) {
    const rx0 = Math.floor(x0 / 512)
    const rx1 = Math.floor(x1 / 512)
    const rz0 = Math.floor(z0 / 512)
    const rz1 = Math.floor(z1 / 512)
    if ((rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 64 * 64 || this.inflight) return
    let missing = false
    for (let rz = rz0; rz <= rz1 && !missing; rz++)
      for (let rx = rx0; rx <= rx1; rx++)
        if (!this.fetched.has(`${rx},${rz}`)) {
          missing = true
          break
        }
    if (!missing) return
    this.inflight = true
    try {
      const res = await api<SavedChunks>(
        `chunks/${encodeURIComponent(this.dim)}?x0=${rx0 * 512}&z0=${rz0 * 512}&x1=${rx1 * 512 + 511}&z1=${rz1 * 512 + 511}`,
      )
      for (let rz = rz0; rz <= rz1; rz++)
        for (let rx = rx0; rx <= rx1; rx++) this.fetched.add(`${rx},${rz}`)
      for (const r of res.regions) {
        const bits = Uint8Array.from(atob(r.mask), (c) => c.charCodeAt(0))
        const reg = this.region(r.rx, r.rz)
        for (let i = 0; i < 128; i++) reg.mask[i] |= bits[i]
        reg.dirty = true
      }
      if (res.bytesPerChunk > 0) this.bytesPerChunk = res.bytesPerChunk
      this.onChange()
    } catch {
      // The layer simply stays empty; it is advisory.
    } finally {
      this.inflight = false
    }
  }

  draw(
    ctx: CanvasRenderingContext2D,
    toScreen: (bx: number, bz: number) => { x: number; y: number },
    scale: number,
  ) {
    ctx.imageSmoothingEnabled = false
    for (const [key, r] of this.regions) {
      const [rx, rz] = key.split(',').map(Number)
      const p = toScreen(rx * 512, rz * 512)
      const size = 512 * scale
      if (
        p.x > ctx.canvas.width ||
        p.y > ctx.canvas.height ||
        p.x + size < 0 ||
        p.y + size < 0
      )
        continue
      if (!r.canvas || r.dirty) {
        r.canvas ??= Object.assign(document.createElement('canvas'), {
          width: 32,
          height: 32,
        })
        const c = r.canvas.getContext('2d')!
        const img = c.createImageData(32, 32)
        for (let i = 0; i < 1024; i++)
          if (r.mask[i >> 3] & (1 << (i & 7)))
            img.data.set([143, 211, 230, 120], i * 4)
        c.putImageData(img, 0, 0)
        r.dirty = false
      }
      ctx.drawImage(r.canvas, p.x, p.y, size, size)
    }
  }
}
