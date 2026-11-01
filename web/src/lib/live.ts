import { authHeaders } from './api'
import type { ChunkyStatus, Info, Player, SearchState, Waypoint } from './api'
import { Emitter, createStore } from './store'

// Live server state, kept in sync over a server-sent event stream.

export interface LivePlayer extends Player {
  /** Arrival time of this sample and the previous position, for smooth marker motion. */
  t: number
  px: number
  pz: number
  pyaw: number
}

export type Connection = 'connecting' | 'live' | 'offline' | 'signed-out'

export interface LiveState {
  connection: Connection
  info: Info | null
  players: Record<string, LivePlayer>
  waypoints: Waypoint[]
  chunky: Record<string, ChunkyStatus>
  searches: Record<string, SearchState>
}

export const live = createStore<LiveState>({
  connection: 'connecting',
  info: null,
  players: {},
  waypoints: [],
  chunky: {},
  searches: {},
})

export interface LiveEvents extends Record<string, unknown> {
  /** Chunks generated since the last flush: flat [cx, cz, ...] pairs. */
  chunks: { dim: string; chunks: number[] }
  /** A sharper version of a map tile is available. */
  tile: { dim: string; z: number; x: number; y: number; quality: number }
}

export const liveEvents = new Emitter<LiveEvents>()

function applyPlayers(changed: Player[], removed: string[], replace: boolean) {
  const now = performance.now()
  live.set((s) => {
    const next: Record<string, LivePlayer> = replace ? {} : { ...s.players }
    for (const p of changed) {
      const prev = s.players[p.uuid]
      // Interpolate from where the marker is drawn right now, so updates never jump.
      const at = prev ? playerPosition(prev, now) : null
      next[p.uuid] = {
        ...p,
        t: now,
        px: at && prev.dim === p.dim ? at.x : p.x,
        pz: at && prev.dim === p.dim ? at.z : p.z,
        pyaw: at && prev.dim === p.dim ? at.yaw : p.yaw,
      }
    }
    for (const id of removed) delete next[id]
    return { players: next }
  })
}

const LERP_MS = 120

/** Where a player's marker should be drawn at time `now` (performance.now()). */
export function playerPosition(p: LivePlayer, now: number) {
  const k = Math.min(1, Math.max(0, (now - p.t) / LERP_MS))
  let dyaw = p.yaw - p.pyaw
  dyaw = ((((dyaw + 180) % 360) + 360) % 360) - 180
  return {
    x: p.px + (p.x - p.px) * k,
    z: p.pz + (p.z - p.pz) * k,
    yaw: p.pyaw + dyaw * k,
  }
}

function dispatch(type: string, data: unknown) {
  switch (type) {
    case 'hello':
      live.set({ info: data as Info })
      break
    case 'players':
      if (Array.isArray(data)) applyPlayers(data as Player[], [], true)
      else {
        const d = data as { changed: Player[]; removed: string[] }
        applyPlayers(d.changed, d.removed, false)
      }
      break
    case 'waypoints':
      live.set({ waypoints: data as Waypoint[] })
      break
    case 'chunky': {
      const c = data as ChunkyStatus
      live.set((s) => ({ chunky: { ...s.chunky, [c.world]: c } }))
      break
    }
    case 'searches': {
      const list = data as SearchState[]
      live.set((s) => {
        const next = { ...s.searches }
        for (const x of list) next[x.id] = { ...next[x.id], ...x }
        return { searches: next }
      })
      break
    }
    case 'search': {
      const x = data as SearchState
      live.set((s) => ({ searches: { ...s.searches, [x.id]: x } }))
      break
    }
    case 'chunks':
      liveEvents.emit('chunks', data as LiveEvents['chunks'])
      break
    case 'tile':
      liveEvents.emit('tile', data as LiveEvents['tile'])
      break
  }
}

let controller: AbortController | null = null

export function disconnectLive() {
  controller?.abort()
  controller = null
  live.set({
    connection: 'signed-out',
    info: null,
    players: {},
    waypoints: [],
    chunky: {},
    searches: {},
  })
}

/** One event stream per page, with cancellable reconnects and backoff. */
export function connectLive() {
  if (controller) return
  const own = new AbortController()
  controller = own
  const { signal } = own
  let delay = 1000
  const run = async () => {
    while (!signal.aborted) {
      live.set({ connection: 'connecting' })
      try {
        const res = await fetch('/api/events', {
          signal,
          headers: { ...authHeaders(), Accept: 'text/event-stream' },
        })
        if (res.status === 401) {
          window.dispatchEvent(new Event('fastdiffusion:unauthorized'))
          return
        }
        if (!res.ok || !res.body) throw new Error(`events: ${res.status}`)
        live.set({ connection: 'live' })
        delay = 1000
        await readStream(res.body)
      } catch {
        if (signal.aborted) return
      }
      if (signal.aborted) return
      live.set({ connection: 'offline' })
      await new Promise<void>((resolve) => {
        const done = () => {
          clearTimeout(timer)
          signal.removeEventListener('abort', done)
          resolve()
        }
        const timer = setTimeout(done, delay)
        signal.addEventListener('abort', done, { once: true })
      })
      delay = Math.min(10_000, delay * 2)
    }
  }
  void run().finally(() => {
    if (controller === own) controller = null
  })
}

async function readStream(body: ReadableStream<Uint8Array>) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    buf += decoder.decode(value, { stream: true })
    let sep: number
    while ((sep = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, sep)
      buf = buf.slice(sep + 2)
      let type = 'message'
      let data = ''
      for (const line of block.split('\n')) {
        if (line.startsWith('event: ')) type = line.slice(7)
        else if (line.startsWith('data: ')) data += line.slice(6)
      }
      if (data) {
        try {
          dispatch(type, JSON.parse(data))
        } catch {
          // A malformed event never takes the stream down.
        }
      }
    }
  }
}
