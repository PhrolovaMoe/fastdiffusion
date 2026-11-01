import { authHeaders } from '../lib/api'
import type { Dimension } from '../lib/api'

// Map tiles: 256x256 pixels at 2^z blocks per pixel with top block Y, biome, temperature and precipitation.
// Fetched with a small concurrency limit, nearest first; requests the view no longer needs are aborted.

export const TILE = 256

export interface TileData {
  quality: number
  /** Top block Y per pixel. */
  y: Float32Array
  /** Interleaved biome, temperature code, precipitation code, 255. */
  attrs: Uint8Array
}

export interface Tile {
  key: string
  z: number
  x: number
  y: number
  state: 'idle' | 'loading' | 'ready' | 'error'
  data?: TileData
  /** GPU textures, owned by the renderer. */
  gpu?: unknown
  used: number
  abort?: AbortController
  retryAt: number
  /** Set when the server announced a sharper version. */
  stale?: boolean
}

export function tileKey(z: number, x: number, y: number) {
  return `${z}/${x}/${y}`
}

const MAX_TILES = 240
const CONCURRENCY = 6

export function decodeTile(buf: ArrayBuffer): TileData {
  const v = new DataView(buf)
  if (
    buf.byteLength !== 16 + TILE * TILE * 5 ||
    v.getUint32(0, true) !== 0x544d4446 ||
    v.getUint8(4) !== 2 ||
    v.getUint16(6, true) !== TILE
  )
    throw new Error('bad tile')
  const quality = v.getUint8(5)
  const n = TILE * TILE
  const y = new Float32Array(n)
  for (let r = 0; r < TILE; r++) {
    let prev = 0
    for (let c = 0; c < TILE; c++) {
      prev = ((prev + v.getInt16(16 + (r * TILE + c) * 2, true)) << 16) >> 16
      y[r * TILE + c] = prev
    }
  }
  const bytes = new Uint8Array(buf, 16 + n * 2)
  const attrs = new Uint8Array(n * 4)
  for (let i = 0; i < n; i++) {
    attrs[i * 4] = bytes[i]
    attrs[i * 4 + 1] = bytes[n + i]
    attrs[i * 4 + 2] = bytes[2 * n + i]
    attrs[i * 4 + 3] = 255
  }
  return { quality, y, attrs }
}

export class TileStore {
  private tiles = new Map<string, Tile>()
  private wanted: Tile[] = []
  private active = 0
  private disposed = false
  onEvict: (t: Tile) => void = () => {}

  constructor(
    readonly dim: Dimension,
    private readonly onChange: () => void,
  ) {}

  get(z: number, x: number, y: number): Tile | undefined {
    return this.tiles.get(tileKey(z, x, y))
  }

  private entry(z: number, x: number, y: number): Tile {
    const key = tileKey(z, x, y)
    let t = this.tiles.get(key)
    if (!t) {
      t = { key, z, x, y, state: 'idle', used: 0, retryAt: 0 }
      this.tiles.set(key, t)
    }
    return t
  }

  /** The tiles the view needs, most important first. Loads them and aborts everything else in flight. */
  want(keys: [number, number, number][]) {
    const now = performance.now()
    const list: Tile[] = []
    const set = new Set<string>()
    for (const [z, x, y] of keys) {
      const t = this.entry(z, x, y)
      t.used = now
      list.push(t)
      set.add(t.key)
    }
    for (const t of this.wanted)
      if (!set.has(t.key) && t.state === 'loading' && t.abort) {
        t.abort.abort()
        t.abort = undefined
        t.state = t.data ? 'ready' : 'idle'
        this.active--
      }
    this.wanted = list
    this.pump()
    this.evict()
  }

  /** Re-fetch a tile the server has refined (the old data stays visible meanwhile). */
  refresh(z: number, x: number, y: number) {
    const t = this.tiles.get(tileKey(z, x, y))
    if (!t) return
    t.stale = true
    if (t.state === 'ready') t.state = 'idle'
    this.pump()
  }

  /** Loads one tile regardless of the view (for previews). */
  async load(z: number, x: number, y: number): Promise<TileData> {
    const t = this.entry(z, x, y)
    t.used = performance.now()
    if (t.data && !t.stale) return t.data
    const data = await this.fetchTile(z, x, y, undefined)
    this.accept(t, data)
    return data
  }

  private pump() {
    if (this.disposed) return
    const now = performance.now()
    for (const t of this.wanted) {
      if (this.active >= CONCURRENCY) return
      if (t.state !== 'idle' && !(t.state === 'error' && now >= t.retryAt))
        continue
      if (t.data && !t.stale) {
        t.state = 'ready'
        continue
      }
      const abort = new AbortController()
      t.abort = abort
      t.state = 'loading'
      this.active++
      this.fetchTile(t.z, t.x, t.y, abort.signal)
        .then((data) => {
          if (t.abort !== abort) return
          this.active--
          t.abort = undefined
          this.accept(t, data)
        })
        .catch((e: unknown) => {
          if (t.abort !== abort) return
          this.active--
          t.abort = undefined
          const cancelled = e instanceof TileError && e.status === 409
          t.state = t.data ? 'ready' : 'error'
          t.retryAt = performance.now() + (cancelled ? 500 : 5000)
          setTimeout(() => this.pump(), cancelled ? 600 : 5100)
        })
        .finally(() => this.pump())
    }
  }

  private accept(t: Tile, data: TileData) {
    if (this.disposed) return
    t.data = data
    t.state = 'ready'
    t.stale = false
    if (t.gpu) {
      this.onEvict(t)
      t.gpu = undefined
    }
    this.onChange()
  }

  private async fetchTile(
    z: number,
    x: number,
    y: number,
    signal: AbortSignal | undefined,
  ): Promise<TileData> {
    const d = this.dim
    const res = await fetch(
      `/api/tiles/${encodeURIComponent(d.id)}/${d.fingerprint}/${z}/${x}/${y}`,
      {
        headers: authHeaders(),
        signal,
        cache: 'default',
      },
    )
    if (res.status === 401)
      window.dispatchEvent(new Event('fastdiffusion:unauthorized'))
    if (!res.ok) throw new TileError(res.status)
    return decodeTile(await res.arrayBuffer())
  }

  private evict() {
    if (this.tiles.size <= MAX_TILES) return
    const wanted = new Set(this.wanted.map((t) => t.key))
    const victims = [...this.tiles.values()]
      .filter((t) => !wanted.has(t.key) && t.state !== 'loading')
      .sort((a, b) => a.used - b.used)
    for (const t of victims.slice(0, this.tiles.size - MAX_TILES)) {
      if (t.gpu) this.onEvict(t)
      this.tiles.delete(t.key)
    }
  }

  /** The finest loaded sample at a block position, from level `zMin` upward. */
  sample(bx: number, bz: number, zMin: number) {
    for (let z = Math.max(0, zMin); z <= this.dim.maxZoom; z++) {
      const span = TILE * 2 ** z
      const t = this.tiles.get(
        tileKey(z, Math.floor(bx / span), Math.floor(bz / span)),
      )
      if (!t?.data) continue
      const px = Math.floor((bx - t.x * span) / 2 ** z)
      const pz = Math.floor((bz - t.y * span) / 2 ** z)
      const i = pz * TILE + px
      const a = t.data.attrs
      return {
        y: t.data.y[i],
        biome: a[i * 4],
        temp: a[i * 4 + 1] * 0.5 - 50,
        precip: a[i * 4 + 2] * 20,
        z,
        quality: t.data.quality,
      }
    }
    return null
  }

  forEach(fn: (t: Tile) => void) {
    this.tiles.forEach(fn)
  }

  dispose() {
    this.disposed = true
    for (const t of this.tiles.values()) {
      t.abort?.abort()
      if (t.gpu) this.onEvict(t)
    }
    this.tiles.clear()
    this.wanted = []
  }
}

export class TileError extends Error {
  constructor(readonly status: number) {
    super(`tile ${status}`)
  }
}

/** Heights/biomes on an n x n grid over a block rectangle, loading tiles of a suitable level. */
export async function sampleArea(
  store: TileStore,
  x0: number,
  z0: number,
  x1: number,
  z1: number,
  n: number,
): Promise<{ y: Float32Array; biome: Uint8Array; level: number }> {
  const span = Math.max(x1 - x0, z1 - z0)
  const level = Math.min(
    store.dim.maxZoom,
    Math.max(0, Math.ceil(Math.log2(span / n))),
  )
  const tileSpan = TILE * 2 ** level
  const loads: Promise<TileData>[] = []
  const keys: string[] = []
  for (
    let ty = Math.floor(z0 / tileSpan);
    ty <= Math.floor(z1 / tileSpan);
    ty++
  )
    for (
      let tx = Math.floor(x0 / tileSpan);
      tx <= Math.floor(x1 / tileSpan);
      tx++
    ) {
      keys.push(tileKey(level, tx, ty))
      loads.push(store.load(level, tx, ty))
    }
  const datas = await Promise.all(loads)
  const byKey = new Map(keys.map((k, i) => [k, datas[i]]))
  const y = new Float32Array(n * n)
  const biome = new Uint8Array(n * n)
  const step = 2 ** level
  for (let r = 0; r < n; r++)
    for (let c = 0; c < n; c++) {
      const bx = x0 + ((c + 0.5) / n) * (x1 - x0)
      const bz = z0 + ((r + 0.5) / n) * (z1 - z0)
      const tx = Math.floor(bx / tileSpan)
      const ty = Math.floor(bz / tileSpan)
      const d = byKey.get(tileKey(level, tx, ty))!
      const px = Math.min(TILE - 1, Math.floor((bx - tx * tileSpan) / step))
      const pz = Math.min(TILE - 1, Math.floor((bz - ty * tileSpan) / step))
      y[r * n + c] = d.y[pz * TILE + px]
      biome[r * n + c] = d.attrs[(pz * TILE + px) * 4]
    }
  return { y, biome, level }
}
