import { createStore } from './store'

// Map appearance and highlight filters, remembered per browser.

export type MapMode =
  | 'atlas'
  | 'biomes'
  | 'height'
  | 'temperature'
  | 'rainfall'
  | 'slope'

export interface Range {
  min: number
  max: number
}

export interface Filters {
  enabled: boolean
  height: Range | null
  temperature: Range | null
  rainfall: Range | null
  slopeMax: number | null
  biomes: number[]
}

export interface Prefs {
  mode: MapMode
  relief: number
  sunAzimuth: number
  sunAltitude: number
  contours: boolean
  contourInterval: number
  chunkGrid: boolean
  regionGrid: boolean
  generated: boolean
  labels: boolean
  filters: Filters
}

const KEY = 'fastdiffusion.prefs.v1'

export const defaultFilters: Filters = {
  enabled: false,
  height: null,
  temperature: null,
  rainfall: null,
  slopeMax: null,
  biomes: [],
}

const defaults: Prefs = {
  mode: 'atlas',
  relief: 1.6,
  sunAzimuth: 315,
  sunAltitude: 38,
  contours: false,
  contourInterval: 50,
  chunkGrid: true,
  regionGrid: false,
  generated: false,
  labels: true,
  filters: defaultFilters,
}

function load(): Prefs {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const p = JSON.parse(raw)
      return {
        ...defaults,
        ...p,
        filters: { ...defaultFilters, ...p.filters },
      }
    }
  } catch {
    // Defaults.
  }
  return defaults
}

export const prefs = createStore<Prefs>(
  typeof window === 'undefined' ? defaults : load(),
)

if (typeof window !== 'undefined') {
  let timer = 0
  prefs.subscribe(() => {
    clearTimeout(timer)
    timer = window.setTimeout(() => {
      try {
        localStorage.setItem(KEY, JSON.stringify(prefs.get()))
      } catch {
        // Not persisted.
      }
    }, 300)
  })
}

export function setFilters(patch: Partial<Filters>) {
  prefs.set((s) => ({ filters: { ...s.filters, ...patch } }))
}

export function filtersActive(f: Filters) {
  return (
    f.enabled &&
    (f.height !== null ||
      f.temperature !== null ||
      f.rainfall !== null ||
      f.slopeMax !== null ||
      f.biomes.length > 0)
  )
}
