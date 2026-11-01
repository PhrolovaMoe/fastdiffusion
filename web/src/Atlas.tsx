import { useCallback, useEffect, useState } from 'react'
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from '@tanstack/react-query'
import { Icon } from './components/Icon'
import { TeleportCard } from './components/TeleportCard'
import {
  api,
  getToken,
  setToken,
  type Dimension,
  type Info,
  type Stats,
} from './lib/api'
import { biome } from './lib/biomes'
import { connectLive, disconnectLive, live } from './lib/live'
import { prefs, type MapMode } from './lib/prefs'
import { flyTo, toast, ui, type Panel } from './lib/ui'
import { MapView, mapContext } from './map/MapView'
import { Terrain3D } from './map/Terrain3D'
import { type View } from './map/view'
import { LayersPanel } from './panels/LayersPanel'
import { FindPanel, findDraft, PRESETS } from './panels/FindPanel'
import { PlayersPanel, dimName } from './panels/PlayersPanel'
import { GeneratePanel } from './panels/GeneratePanel'
import { WaypointsPanel, saveWaypoint } from './panels/WaypointsPanel'
import { ElevationProfile } from './components/ElevationProfile'

export interface MapSearch {
  x?: number
  z?: number
  zoom?: number
  dim?: string
  layer?: MapMode
  embed?: number
}
const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})
export function Atlas(props: {
  search: MapSearch
  navigate: (s: MapSearch) => void
}) {
  return (
    <QueryClientProvider client={client}>
      <Screen {...props} />
    </QueryClientProvider>
  )
}
const panels: { id: Panel; icon: string; title: string }[] = [
  { id: 'layers', icon: 'layers', title: 'Layers' },
  { id: 'find', icon: 'find', title: 'Find places' },
  { id: 'players', icon: 'players', title: 'Players' },
  { id: 'waypoints', icon: 'pin', title: 'Waypoints' },
  { id: 'generate', icon: 'generate', title: 'Generate chunks' },
]
function Screen({
  search,
  navigate,
}: {
  search: MapSearch
  navigate: (s: MapSearch) => void
}) {
  const [signedIn, setSignedIn] = useState(() => !!getToken())
  const [initialized, setInitialized] = useState(false)
  const state = ui.use((s) => s)
  const data = live.use((s) => s)
  const mode = prefs.use((s) => s.mode)
  const infoQuery = useQuery({
    queryKey: ['info'],
    queryFn: () => api<Info>('info'),
    enabled: signedIn,
  })
  const stats = useQuery({
    queryKey: ['stats'],
    queryFn: () => api<Stats>('stats'),
    enabled: signedIn,
    refetchInterval: 5000,
  })
  const info = data.info ?? infoQuery.data
  const dim =
    info?.dimensions.find((d) => d.id === state.dim) ?? info?.dimensions[0]
  useEffect(() => {
    const unauthorized = () => {
      disconnectLive()
      setToken(null)
      setSignedIn(false)
      client.clear()
    }
    window.addEventListener('fastdiffusion:unauthorized', unauthorized)
    if (signedIn) connectLive()
    return () => {
      window.removeEventListener('fastdiffusion:unauthorized', unauthorized)
      disconnectLive()
    }
  }, [signedIn])
  useEffect(() => {
    if (!info || initialized) return
    const d =
      info.dimensions.find((d) => d.id === search.dim) ?? info.dimensions[0]
    if (d)
      ui.set({
        dim: d.id,
        embed: search.embed === 1,
        view: {
          cx: search.x ?? d.spawnX,
          cz: search.z ?? d.spawnZ,
          zoom: search.zoom ?? 2,
        },
      })
    if (search.layer) prefs.set({ mode: search.layer })
    setInitialized(true)
  }, [info, initialized, search])
  const onView = useCallback(
    (v: View) => {
      const s = ui.get()
      navigate({
        x: Math.round(v.cx),
        z: Math.round(v.cz),
        zoom: +v.zoom.toFixed(2),
        dim: s.dim ?? undefined,
        layer: prefs.get().mode,
        embed: s.embed ? 1 : undefined,
      })
    },
    [navigate],
  )
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        ui.set({ palette: !ui.get().palette })
        return
      }
      if (e.key === 'Escape') {
        ui.set({
          palette: false,
          preview: null,
          menu: null,
          target: null,
          tool: 'inspect',
          followed: null,
        })
        return
      }
      if (
        (e.target as HTMLElement)?.closest('input,select,textarea') ||
        e.ctrlKey ||
        e.metaKey
      )
        return
      const v = ui.get().view
      if (e.key === '+' || e.key === '=') flyTo(v.cx, v.cz, v.zoom - 1)
      if (e.key === '-') flyTo(v.cx, v.cz, v.zoom + 1)
      const step = 180 * 2 ** v.zoom
      const delta: Record<string, [number, number]> = {
        ArrowLeft: [-step, 0],
        ArrowRight: [step, 0],
        ArrowUp: [0, -step],
        ArrowDown: [0, step],
      }
      if (delta[e.key]) {
        e.preventDefault()
        flyTo(v.cx + delta[e.key][0], v.cz + delta[e.key][1], v.zoom)
      }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [])
  if (!signedIn)
    return (
      <main className="gate">
        <div>
          <Icon name="peak" size={48} />
          <h1>FastDiffusion atlas</h1>
          <p>
            Run <code>/fastdiffusion web</code> in Minecraft and open the login
            link.
          </p>
          <p className="hint">Operator access is required.</p>
        </div>
      </main>
    )
  if (infoQuery.isError && !info)
    return (
      <main className="gate">
        <div>
          <h1>Server unavailable</h1>
          <p>{infoQuery.error.message}</p>
          <button className="btn" onClick={() => void infoQuery.refetch()}>
            Reconnect
          </button>
        </div>
      </main>
    )
  if (!initialized || !info)
    return (
      <main className="gate">
        <p>Connecting to the server…</p>
      </main>
    )
  if (!dim)
    return (
      <main className="gate">
        <div>
          <h1>No FastDiffusion world</h1>
          <p>
            Open a world with a FastDiffusion terrain preset, then reload the
            map.
          </p>
          <button className="btn" onClick={() => location.reload()}>
            Reload
          </button>
        </div>
      </main>
    )
  const changeDim = (id: string) => {
    const d = info.dimensions.find((d) => d.id === id)!
    ui.set({
      dim: id,
      view: { cx: d.spawnX, cz: d.spawnZ, zoom: 2 },
      selection: null,
      measure: null,
      target: null,
      preview: null,
      followed: null,
      activeSearch: null,
    })
    onView(ui.get().view)
  }
  const copy = async () => {
    onView(state.view)
    const url = new URL(location.href)
    url.searchParams.set('layer', mode)
    try {
      await navigator.clipboard.writeText(url.href)
      toast('Map link copied')
    } catch {
      toast('Copy the link from the address bar', 'info')
    }
  }
  return (
    <main className={`atlas ${state.embed ? 'embedded' : ''}`}>
      <MapView key={dim.id} dim={dim} onView={onView} />
      {!state.embed && (
        <>
          <header className="topbar">
            <div className="surface brand">
              <Icon name="peak" size={20} />
              <span>FastDiffusion</span>
              <label className="sr-only" htmlFor="dimension">
                Dimension
              </label>
              <select
                id="dimension"
                value={dim.id}
                onChange={(e) => changeDim(e.target.value)}
              >
                {info.dimensions.map((d) => (
                  <option key={d.id} value={d.id}>
                    {dimName(d.id)}
                  </option>
                ))}
              </select>
            </div>
            <button
              className="surface search-launch"
              onClick={() => ui.set({ palette: true })}
            >
              <Icon name="find" size={18} />
              <span>Coordinates, players, places</span>
              <kbd>Ctrl K</kbd>
            </button>
            <div className="surface session">
              <span
                className={`connection ${data.connection}`}
                title={info.motd}
              >
                {data.connection === 'live'
                  ? 'Live'
                  : data.connection === 'offline'
                    ? 'Reconnecting'
                    : 'Connecting'}
              </span>
              <button
                className="icon-btn"
                aria-label="Copy map link"
                title="Copy map link"
                onClick={() => void copy()}
              >
                <Icon name="copy" size={18} />
              </button>
              <button
                className="text-btn"
                onClick={async () => {
                  try {
                    await api('session', { method: 'DELETE' })
                  } finally {
                    disconnectLive()
                    setToken(null)
                    location.reload()
                  }
                }}
              >
                Sign out
              </button>
            </div>
          </header>
          <div className={state.panel ? 'surface dock open' : 'surface dock'}>
            <nav className="rail" aria-label="Map panels">
              {panels
                .filter((p) => p.id !== 'generate' || info.chunky.installed)
                .map((p) => (
                  <button
                    key={p.id}
                    data-tip={p.title}
                    aria-label={p.title}
                    aria-pressed={state.panel === p.id}
                    onClick={() =>
                      ui.set({
                        panel: state.panel === p.id ? null : p.id,
                        menu: null,
                      })
                    }
                  >
                    <Icon name={p.icon} />
                  </button>
                ))}
            </nav>
            {state.panel && (
              <aside
                className="panel"
                aria-label={panels.find((p) => p.id === state.panel)?.title}
              >
                <header>
                  <h2>{panels.find((p) => p.id === state.panel)?.title}</h2>
                  <button
                    className="icon-btn"
                    aria-label="Close panel"
                    onClick={() => ui.set({ panel: null })}
                  >
                    <Icon name="close" size={18} />
                  </button>
                </header>
                <div className="panel-body">
                  {state.panel === 'layers' && <LayersPanel dim={dim} />}
                  {state.panel === 'find' && <FindPanel dim={dim} />}
                  {state.panel === 'players' && <PlayersPanel dim={dim} />}
                  {state.panel === 'waypoints' && <WaypointsPanel dim={dim} />}
                  {state.panel === 'generate' && <GeneratePanel dim={dim} />}
                </div>
              </aside>
            )}
          </div>
          <div className="map-tools">
            <div className="surface" role="group" aria-label="Map tool">
              <button
                className="icon-btn"
                aria-pressed={state.tool === 'inspect'}
                aria-label="Inspect and pan"
                data-tip="Inspect and pan"
                onClick={() => ui.set({ tool: 'inspect' })}
              >
                <Icon name="cursor" />
              </button>
              <button
                className="icon-btn"
                aria-pressed={state.tool === 'measure'}
                aria-label="Measure elevation profile"
                data-tip="Measure elevation"
                onClick={() => ui.set({ tool: 'measure', measure: null })}
              >
                <Icon name="ruler" />
              </button>
              <button
                className="icon-btn"
                aria-pressed={state.tool === 'teleport'}
                aria-label="Teleport player"
                data-tip="Teleport a player"
                onClick={() => ui.set({ tool: 'teleport', panel: 'players' })}
              >
                <Icon name="target" />
              </button>
            </div>
            <div className="surface" role="group" aria-label="Camera">
              <button
                className="icon-btn"
                aria-label="Zoom in"
                data-tip="Zoom in"
                onClick={() =>
                  flyTo(state.view.cx, state.view.cz, state.view.zoom - 1)
                }
              >
                <Icon name="plus" />
              </button>
              <button
                className="icon-btn"
                aria-label="Zoom out"
                data-tip="Zoom out"
                onClick={() =>
                  flyTo(state.view.cx, state.view.cz, state.view.zoom + 1)
                }
              >
                <Icon name="minus" />
              </button>
              <button
                className="icon-btn"
                aria-label="Go to world spawn"
                data-tip="World spawn"
                onClick={() => flyTo(dim.spawnX, dim.spawnZ, 2)}
              >
                <Icon name="home" />
              </button>
            </div>
          </div>
          <footer className="surface readout">
            <span className="coords">
              <span>X</span> {state.hover?.x ?? Math.round(state.view.cx)}
              <span>Z</span> {state.hover?.z ?? Math.round(state.view.cz)}
              {state.hover?.sample && (
                <>
                  <span>Y</span> {Math.round(state.hover.sample.y)}
                </>
              )}
            </span>
            {state.hover?.sample && (
              <>
                <span className="readout-biome">
                  <i
                    style={{
                      background: biome(state.hover.sample.biome).color,
                    }}
                  />
                  {biome(state.hover.sample.biome).name}
                </span>
                <span>{state.hover.sample.temp.toFixed(1)} °C</span>
                <span>
                  {Math.round(state.hover.sample.precip).toLocaleString()} mm
                </span>
                {state.hover.sample.quality !== 2 && (
                  <span className="faint" title="Detailed terrain is still loading">
                    Overview
                  </span>
                )}
              </>
            )}
          </footer>
          {stats.data && (
            <div
              className={
                stats.data.mspt > 50 ? 'surface server lagging' : 'surface server'
              }
              title={info.device ?? stats.data.device}
            >
              {stats.data.mspt.toFixed(1)} ms/tick
              {stats.data.queued ? (
                <span>{stats.data.queued} regions queued</span>
              ) : null}
            </div>
          )}
          {data.connection === 'offline' && (
            <div className="surface offline-banner" role="status">
              Server connection lost. Player positions may be out of date.
              Reconnecting…
            </div>
          )}
          {state.tool !== 'inspect' && (
            <div className="surface tool-hint">
              {state.tool === 'measure'
                ? 'Drag a line to inspect its elevation profile'
                : state.tool === 'teleport'
                  ? 'Click a destination on the map'
                  : 'Drag out from the centre; drag the edge to resize'}
              <button
                className="icon-btn"
                aria-label="Exit tool"
                onClick={() => ui.set({ tool: 'inspect' })}
              >
                <Icon name="close" size={16} />
              </button>
            </div>
          )}
          <TeleportCard dim={dim} />
          {state.measure && <ElevationProfile dim={dim} />}
          {state.menu && <ContextMenu dim={dim} />}
          {state.palette && <Palette dim={dim} />}
        </>
      )}
      {state.preview && mapContext.store && (
        <Terrain3D
          dim={dim}
          store={mapContext.store}
          area={state.preview}
          onClose={() => ui.set({ preview: null })}
        />
      )}
      <div className="toasts" role="status" aria-live="polite">
        {state.toasts.map((t) => (
          <div key={t.id} className={`toast ${t.tone}`}>
            {t.text}
          </div>
        ))}
      </div>
    </main>
  )
}
function ContextMenu({ dim }: { dim: Dimension }) {
  const menu = ui.use((s) => s.menu)!
  const act = (fn: () => void) => {
    fn()
    ui.set({ menu: null })
  }
  return (
    <div
      className="surface context-menu"
      style={{
        left: Math.min(menu.sx, window.innerWidth - 235),
        top: Math.min(menu.sy, window.innerHeight - 230),
      }}
    >
      <div className="menu-coords">
        {menu.x}, {menu.z}
      </div>
      <button
        onClick={() =>
          act(() => {
            findDraft.around(menu.x, menu.z)
            ui.set({ panel: 'find' })
          })
        }
      >
        Find places around here
      </button>
      <button
        onClick={() =>
          act(() =>
            ui.set({ tool: 'teleport', target: { x: menu.x, z: menu.z } }),
          )
        }
      >
        Teleport a player here
      </button>
      <button
        onClick={() =>
          act(() => {
            void saveWaypoint(
              dim.id,
              menu.x,
              menu.z,
              `Place at ${menu.x}, ${menu.z}`,
            )
            ui.set({ panel: 'waypoints' })
          })
        }
      >
        Save waypoint
      </button>
      <button
        onClick={() =>
          act(() =>
            ui.set({
              preview: {
                title: `${menu.x}, ${menu.z}`,
                x0: menu.x - 512,
                x1: menu.x + 512,
                z0: menu.z - 512,
                z1: menu.z + 512,
              },
            }),
          )
        }
      >
        View terrain in 3D
      </button>
    </div>
  )
}
function Palette({ dim }: { dim: Dimension }) {
  const [q, setQ] = useState('')
  const players = Object.values(live.use((s) => s.players)).filter((p) =>
    p.name.toLowerCase().includes(q.toLowerCase()),
  )
  const points = live
    .use((s) => s.waypoints)
    .filter(
      (p) => p.dim === dim.id && p.name.toLowerCase().includes(q.toLowerCase()),
    )
  const presets = PRESETS.filter((p) =>
    p.name.toLowerCase().includes(q.toLowerCase()),
  )
  const coords = q.trim().match(/^(-?\d+)\s*[, ]\s*(-?\d+)$/)
  const go = (x: number, z: number) => {
    flyTo(x, z)
    ui.set({ palette: false })
  }
  return (
    <div className="scrim" onClick={() => ui.set({ palette: false })}>
      <div
        className="surface palette"
        role="dialog"
        aria-modal="true"
        aria-label="Search map"
        onClick={(e) => e.stopPropagation()}
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            if (coords) go(+coords[1], +coords[2])
          }}
        >
          <Icon name="find" />
          <input
            autoFocus
            aria-label="Coordinates, players, or places"
            placeholder="Coordinates, players, or places"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <button
            type="button"
            className="icon-btn"
            aria-label="Close search"
            onClick={() => ui.set({ palette: false })}
          >
            <Icon name="close" />
          </button>
        </form>
        <div className="palette-results">
          {coords && (
            <button onClick={() => go(+coords[1], +coords[2])}>
              <Icon name="target" />
              Go to {coords[1]}, {coords[2]}
            </button>
          )}
          {players.map((p) => (
            <button
              key={p.uuid}
              disabled={p.dim !== dim.id}
              onClick={() => go(p.x, p.z)}
            >
              <Icon name="players" />
              {p.name}
              <small>{dimName(p.dim)}</small>
            </button>
          ))}
          {points.map((p) => (
            <button key={p.id} onClick={() => go(p.x, p.z)}>
              <Icon name="pin" />
              {p.name}
            </button>
          ))}
          {presets.map((p) => (
            <button
              key={p.name}
              onClick={() => {
                findDraft.preset(p.name)
                ui.set({ palette: false, panel: 'find' })
              }}
            >
              <Icon name="peak" />
              {p.name}
            </button>
          ))}
          {!coords && !players.length && !points.length && !presets.length && (
            <p className="empty">
              No matches. Enter X, Z coordinates or open the biome finder.
            </p>
          )}
          <button onClick={() => ui.set({ palette: false, panel: 'find' })}>
            <Icon name="find" />
            Open biome finder
          </button>
        </div>
      </div>
    </div>
  )
}
