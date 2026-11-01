import type { Selection } from './shapes'
import { createStore } from './store'
import type { View } from '../map/view'

// Page state for the map screen.

export type Panel = 'layers' | 'find' | 'players' | 'waypoints' | 'generate'
export type Tool = 'inspect' | 'measure' | 'teleport' | 'select'

export interface Sample {
  y: number
  biome: number
  temp: number
  precip: number
  z: number
  quality: number
}

export interface Toast {
  id: number
  text: string
  tone: 'info' | 'error'
}

export interface Preview3D {
  title: string
  x0: number
  z0: number
  x1: number
  z1: number
  mark?: { x: number; z: number }
}

export interface UiState {
  dim: string | null
  view: View
  panel: Panel | null
  tool: Tool
  teleportPlayer: string | null
  target: { x: number; z: number } | null
  followed: string | null
  selection: Selection | null
  measure: { ax: number; az: number; bx: number; bz: number } | null
  activeSearch: string | null
  hoveredResult: number | null
  hover: { x: number; z: number; sample: Sample | null } | null
  menu: { sx: number; sy: number; x: number; z: number } | null
  palette: boolean
  preview: Preview3D | null
  toasts: Toast[]
  embed: boolean
}

export const ui = createStore<UiState>({
  dim: null,
  view: { cx: 0, cz: 0, zoom: 2 },
  panel: null,
  tool: 'inspect',
  teleportPlayer: null,
  target: null,
  followed: null,
  selection: null,
  measure: null,
  activeSearch: null,
  hoveredResult: null,
  hover: null,
  menu: null,
  palette: false,
  preview: null,
  toasts: [],
  embed: false,
})

let toastId = 0

export function toast(text: string, tone: Toast['tone'] = 'info') {
  const id = ++toastId
  ui.set((s) => ({ toasts: [...s.toasts.slice(-3), { id, text, tone }] }))
  setTimeout(
    () => ui.set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
    tone === 'error' ? 7000 : 4000,
  )
}

/** Imperative camera control, provided by the mounted map. */
export const camera = {
  flyTo: (_x: number, _z: number, _zoom?: number) => {},
}

export function flyTo(x: number, z: number, zoom?: number) {
  ui.set({ followed: null })
  camera.flyTo(x, z, zoom)
}
