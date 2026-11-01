// HTTP access to the mod's web API. The bearer token lives in localStorage; every call carries it.

const TOKEN_KEY = 'fastdiffusion.token'
let memoryToken: string | null = null

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? memoryToken
  } catch {
    return memoryToken
  }
}

export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    // Storage blocked (some embedded contexts); the session lasts for this page only.
  }
  memoryToken = token
}

function token() {
  return getToken() ?? memoryToken
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

export function authHeaders(): Record<string, string> {
  const t = token()
  return t ? { Authorization: `Bearer ${t}` } : {}
}

export async function api<T>(
  path: string,
  init: RequestInit & { json?: unknown } = {},
): Promise<T> {
  const { json, ...rest } = init
  const headers: Record<string, string> = {
    ...authHeaders(),
    ...(rest.headers as Record<string, string>),
  }
  if (json !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(`/api/${path}`, {
    ...rest,
    headers,
    body: json !== undefined ? JSON.stringify(json) : rest.body,
  })
  if (res.status === 204) return undefined as T
  const body = await res.json().catch(() => ({}))
  if (!res.ok) {
    if (res.status === 401)
      window.dispatchEvent(new Event('fastdiffusion:unauthorized'))
    throw new ApiError(
      res.status,
      body.error ?? `request failed (${res.status})`,
    )
  }
  return body as T
}

// ---- Shapes of the API -------------------------------------------------------------------------

export interface Dimension {
  id: string
  fingerprint: string
  scale: number
  metersPerBlock: number
  minY: number
  maxY: number
  seaLevel: number
  detailMax: number
  overviewMax: number
  refineMax: number
  maxZoom: number
  spawnX: number
  spawnZ: number
}

export interface BiomeInfo {
  id: number
  key: string
  generated: boolean
}

export interface ChunkyInfo {
  installed: boolean
  ready: boolean
  shapes: string[]
  patterns: string[]
}

export interface Info {
  motd: string
  version: string
  device?: string
  dimensions: Dimension[]
  biomes: BiomeInfo[]
  chunky: ChunkyInfo
}

export interface Player {
  uuid: string
  name: string
  dim: string
  x: number
  y: number
  z: number
  yaw: number
  mode: string
  health: number
  food: number
  level: number
  sleeping: boolean
}

export interface Waypoint {
  id: string
  name: string
  dim: string
  x: number
  z: number
  y?: number
  color: string
  icon: string
  note: string
  author: string
  created: number
}

export type ChunkyState =
  | 'idle'
  | 'running'
  | 'paused'
  | 'pausing'
  | 'complete'
  | 'unavailable'

export interface ChunkyStatus {
  world: string
  state: ChunkyState
  shape?: string
  pattern?: string
  x?: number
  z?: number
  radiusX?: number
  radiusZ?: number
  done?: number
  total?: number
  percent?: number
  rate?: number
  etaSeconds?: number
  chunkX?: number
  chunkZ?: number
}

export interface SearchQuery {
  biomes: number[]
  minFraction: number
  windowRadius: number
  reliefRadius: number
  minRelief: number
  minPeakY?: number | null
  floorMinY?: number | null
  floorMaxY?: number | null
  tempMin?: number | null
  tempMax?: number | null
  precipMin?: number | null
  precipMax?: number | null
  maxResults: number
}

export interface SearchResult {
  x: number
  z: number
  y: number
  score: number
  fraction: number
  relief: number
  peakX: number
  peakZ: number
  peakY: number
  biome: number
  temp: number
  precip: number
  verified: boolean
  distance: number
}

export type SearchPhase =
  | 'screening'
  | 'scanning'
  | 'verifying'
  | 'done'
  | 'cancelled'
  | 'failed'

export interface SearchState {
  id: string
  dim: string
  x: number
  z: number
  radius: number
  phase: SearchPhase
  cellsDone: number
  cellsTotal: number
  cellsSkipped: number
  step: number
  elapsedMs: number
  error?: string
  results?: SearchResult[]
}

export interface SavedChunks {
  regions: { rx: number; rz: number; count: number; mask: string }[]
  bytesPerChunk: number
}

export interface Stats {
  mspt: number
  device?: string
  regions?: number
  failed?: number
  gpuSeconds?: number
  cpuSeconds?: number
  queued?: number
}
