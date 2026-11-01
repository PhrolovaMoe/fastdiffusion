import { createFileRoute } from '@tanstack/react-router'
import { useCallback } from 'react'
import { Atlas, type MapSearch } from '../Atlas'
import type { MapMode } from '../lib/prefs'

const modes = ['atlas', 'biomes', 'height', 'temperature', 'rainfall', 'slope']
export const Route = createFileRoute('/')({
  validateSearch: (s: Record<string, unknown>): MapSearch => ({
    x: finite(s.x),
    z: finite(s.z),
    zoom: finite(s.zoom),
    dim: typeof s.dim === 'string' ? s.dim : undefined,
    layer: modes.includes(String(s.layer)) ? (s.layer as MapMode) : undefined,
    embed: s.embed === 1 || s.embed === '1' ? 1 : undefined,
  }),
  component: Page,
})
function finite(n: unknown) {
  return n !== undefined && Number.isFinite(Number(n)) ? Number(n) : undefined
}
function Page() {
  const navigate = Route.useNavigate()
  const update = useCallback(
    (search: MapSearch) => {
      void navigate({
        search: {
          x: search.x,
          z: search.z,
          zoom: search.zoom,
          dim: search.dim,
          layer: search.layer,
          embed: search.embed,
        },
        replace: true,
      })
    },
    [navigate],
  )
  return <Atlas search={Route.useSearch()} navigate={update} />
}
