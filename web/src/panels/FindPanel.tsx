import { useState } from 'react'
import { BiomeChips } from '../components/BiomeChips'
import { Icon } from '../components/Icon'
import {
  NumberField,
  RangeField,
  Section,
  Segmented,
  Slider,
} from '../components/controls'
import {
  api,
  type Dimension,
  type SearchQuery,
  type SearchState,
} from '../lib/api'
import { biome } from '../lib/biomes'
import { live } from '../lib/live'
import type { Filters } from '../lib/prefs'
import { createStore } from '../lib/store'
import { flyTo, toast, ui } from '../lib/ui'
import { formatDistance } from '../map/view'

// Scenic place finder: presets in real-world metres, converted to blocks for the world's scale.

interface Preset {
  name: string
  about: string
  query: (mpb: number, sea: number) => Partial<SearchQuery>
}

const m = (meters: number, mpb: number) => Math.round(meters / mpb)

export const PRESETS: Preset[] = [
  {
    name: 'Meadow valley',
    about:
      'Flowering meadow in a wide valley, with mountains rising at least 1 km above it.',
    query: (mpb) => ({
      biomes: [11],
      minFraction: 0.35,
      windowRadius: m(1500, mpb),
      reliefRadius: m(6000, mpb),
      minRelief: m(1000, mpb),
    }),
  },
  {
    name: 'Fjord coast',
    about:
      'Cold shoreline where steep walls climb 800 m or more straight out of the sea.',
    query: (mpb, sea) => ({
      biomes: [],
      floorMinY: sea - 1,
      floorMaxY: sea + m(60, mpb),
      reliefRadius: m(3000, mpb),
      minRelief: m(800, mpb),
      tempMax: 8,
    }),
  },
  {
    name: 'Frozen summits',
    about: 'Snow-capped peaks above 4,000 m.',
    query: (mpb, sea) => ({
      biomes: [13, 14],
      minFraction: 0.2,
      windowRadius: m(1000, mpb),
      reliefRadius: m(2000, mpb),
      minPeakY: sea + m(4000, mpb),
    }),
  },
  {
    name: 'Jungle lowland',
    about: 'Dense jungle across a low, wide basin.',
    query: (mpb, sea) => ({
      biomes: [9],
      minFraction: 0.55,
      windowRadius: m(2000, mpb),
      floorMaxY: sea + m(300, mpb),
    }),
  },
  {
    name: 'Canyon desert',
    about: 'Desert cut by 400 m of relief nearby.',
    query: (mpb) => ({
      biomes: [2],
      minFraction: 0.4,
      windowRadius: m(1500, mpb),
      reliefRadius: m(2500, mpb),
      minRelief: m(400, mpb),
    }),
  },
  {
    name: 'Warm beach',
    about: 'Sand beaches on warm coasts.',
    query: (mpb) => ({
      biomes: [27],
      minFraction: 0.06,
      windowRadius: m(600, mpb),
      tempMin: 22,
    }),
  },
  {
    name: 'High savanna',
    about: 'Savanna on a plateau at least 800 m up.',
    query: (mpb, sea) => ({
      biomes: [7],
      minFraction: 0.4,
      windowRadius: m(1500, mpb),
      floorMinY: sea + m(800, mpb),
    }),
  },
]

const baseQuery = (mpb: number): SearchQuery => ({
  biomes: [],
  minFraction: 0.3,
  windowRadius: m(1500, mpb),
  reliefRadius: m(5000, mpb),
  minRelief: 0,
  minPeakY: null,
  floorMinY: null,
  floorMaxY: null,
  tempMin: null,
  tempMax: null,
  precipMin: null,
  precipMax: null,
  maxResults: 25,
})

type Origin = 'view' | 'spawn' | string

interface Draft {
  preset: string | null
  query: SearchQuery | null
  origin: Origin
  radius: number
}

const draftStore = createStore<Draft>({
  preset: PRESETS[0].name,
  query: null,
  origin: 'view',
  radius: 12_000,
})

/** Lets other panels hand the finder a starting point. */
export const findDraft = {
  preset(name: string) {
    draftStore.set({ preset: name, query: null })
  },
  fromFilters(f: Filters) {
    draftStore.set((d) => ({
      preset: null,
      query: {
        ...(d.query ?? baseQuery(5)),
        biomes: f.biomes,
        minFraction: f.biomes.length ? 0.3 : 0,
        floorMinY: f.height?.min ?? null,
        floorMaxY: f.height?.max ?? null,
        tempMin: f.temperature?.min ?? null,
        tempMax: f.temperature?.max ?? null,
        precipMin: f.rainfall?.min ?? null,
        precipMax: f.rainfall?.max ?? null,
      },
    }))
  },
  around(x: number, z: number) {
    ui.set((s) => ({ view: { ...s.view, cx: x, cz: z } }))
    draftStore.set({ origin: 'view' })
  },
}

export function FindPanel({ dim }: { dim: Dimension }) {
  const draft = draftStore.use((d) => d)
  const players = live.use((s) => s.players)
  const activeId = ui.use((s) => s.activeSearch)
  const search = live.use((s) => (activeId ? s.searches[activeId] : undefined))
  const [busy, setBusy] = useState(false)
  const mpb = dim.metersPerBlock
  const sea = dim.seaLevel - 1
  const query: SearchQuery = draft.query ?? {
    ...baseQuery(mpb),
    ...(PRESETS.find((p) => p.name === draft.preset)?.query(mpb, sea) ?? {}),
  }
  const setQuery = (patch: Partial<SearchQuery>) =>
    draftStore.set({ query: { ...query, ...patch }, preset: null })
  const running =
    search && ['screening', 'scanning', 'verifying'].includes(search.phase)

  const start = async () => {
    const v = ui.get().view
    let x = v.cx
    let z = v.cz
    if (draft.origin === 'spawn') {
      x = dim.spawnX
      z = dim.spawnZ
    } else if (draft.origin !== 'view') {
      const p = players[draft.origin]
      if (p) {
        x = p.x
        z = p.z
      }
    }
    setBusy(true)
    try {
      const s = await api<SearchState>('search', {
        method: 'POST',
        json: { dim: dim.id, x, z, radius: draft.radius, query },
      })
      live.set((l) => ({ searches: { ...l.searches, [s.id]: s } }))
      ui.set({ activeSearch: s.id, hoveredResult: null })
    } catch (e) {
      toast(
        e instanceof Error ? e.message : 'The search could not start.',
        'error',
      )
    } finally {
      setBusy(false)
    }
  }

  const stop = () => {
    if (search)
      void api(`search/${search.id}`, { method: 'DELETE' }).catch((e: Error) =>
        toast(e.message, 'error'),
      )
  }

  return (
    <>
      <Section title="What to look for">
        <div className="presets">
          {PRESETS.map((p) => (
            <button
              key={p.name}
              className={
                draft.preset === p.name && !draft.query ? 'preset on' : 'preset'
              }
              onClick={() => draftStore.set({ preset: p.name, query: null })}
            >
              <strong>{p.name}</strong>
              <span>{p.about}</span>
            </button>
          ))}
        </div>
      </Section>

      <Section title="Ground at the spot">
        <BiomeChips
          selected={query.biomes}
          onChange={(biomes) => setQuery({ biomes })}
        />
        {query.biomes.length > 0 && (
          <>
            <Slider
              label="Share of the surroundings"
              value={Math.round(query.minFraction * 100)}
              min={5}
              max={100}
              step={5}
              onChange={(v) => setQuery({ minFraction: v / 100 })}
              format={(v) => `at least ${v}%`}
            />
            <Slider
              label="Surroundings"
              value={query.windowRadius}
              min={64}
              max={4096}
              step={32}
              onChange={(v) => setQuery({ windowRadius: v })}
              format={(v) =>
                `${v.toLocaleString()} blocks around (${formatDistance(v, mpb)})`
              }
            />
          </>
        )}
        <RangeField
          label="Height of the spot"
          value={
            query.floorMinY != null || query.floorMaxY != null
              ? {
                  min: query.floorMinY ?? dim.minY,
                  max: query.floorMaxY ?? dim.maxY,
                }
              : null
          }
          min={dim.minY}
          max={dim.maxY}
          step={1}
          onChange={(v) =>
            setQuery({ floorMinY: v?.min ?? null, floorMaxY: v?.max ?? null })
          }
          format={(v) => `Y ${v}`}
        />
      </Section>

      <Section title="Mountains around it">
        <Slider
          label="Rising above the spot"
          value={query.minRelief}
          min={0}
          max={Math.max(400, m(4000, mpb))}
          step={10}
          onChange={(v) => setQuery({ minRelief: v })}
          format={(v) =>
            v
              ? `${v.toLocaleString()} blocks (${formatDistance(v, mpb)})`
              : 'any'
          }
        />
        <Slider
          label="Within"
          value={query.reliefRadius}
          min={64}
          max={8192}
          step={64}
          onChange={(v) => setQuery({ reliefRadius: v })}
          format={(v) =>
            `${v.toLocaleString()} blocks (${formatDistance(v, mpb)})`
          }
        />
        <NumberField
          label="Highest point at least"
          value={query.minPeakY ?? NaN}
          onChange={(v) => setQuery({ minPeakY: v })}
          suffix="Y"
          min={dim.minY}
          max={dim.maxY}
        />
        {query.minPeakY != null && (
          <button
            className="btn ghost"
            onClick={() => setQuery({ minPeakY: null })}
          >
            Clear minimum peak
          </button>
        )}
      </Section>

      <Section title="Climate">
        <RangeField
          label="Temperature"
          value={
            query.tempMin != null || query.tempMax != null
              ? { min: query.tempMin ?? -30, max: query.tempMax ?? 45 }
              : null
          }
          min={-30}
          max={45}
          step={0.5}
          onChange={(v) =>
            setQuery({ tempMin: v?.min ?? null, tempMax: v?.max ?? null })
          }
          format={(v) => `${v} °C`}
        />
        <RangeField
          label="Rainfall"
          value={
            query.precipMin != null || query.precipMax != null
              ? { min: query.precipMin ?? 0, max: query.precipMax ?? 5000 }
              : null
          }
          min={0}
          max={5000}
          step={20}
          onChange={(v) =>
            setQuery({ precipMin: v?.min ?? null, precipMax: v?.max ?? null })
          }
          format={(v) => `${v.toLocaleString()} mm`}
        />
      </Section>

      <Section title="Where to search">
        <Segmented
          label="Search around"
          value={
            draft.origin === 'view' || draft.origin === 'spawn'
              ? draft.origin
              : 'player'
          }
          options={[
            { value: 'view', label: 'Map centre' },
            { value: 'spawn', label: 'Spawn' },
            ...(Object.keys(players).length
              ? [{ value: 'player', label: 'A player' }]
              : []),
          ]}
          onChange={(v) =>
            draftStore.set({
              origin: v === 'player' ? (Object.keys(players)[0] ?? 'view') : v,
            })
          }
        />
        {draft.origin !== 'view' && draft.origin !== 'spawn' && (
          <select
            className="select"
            value={draft.origin}
            onChange={(e) => draftStore.set({ origin: e.target.value })}
            aria-label="Player"
          >
            {Object.values(players).map((p) => (
              <option key={p.uuid} value={p.uuid}>
                {p.name}
              </option>
            ))}
          </select>
        )}
        <Slider
          label="Radius"
          value={draft.radius}
          min={1000}
          max={100_000}
          step={500}
          onChange={(v) => draftStore.set({ radius: v })}
          format={(v) =>
            `${v.toLocaleString()} blocks (${formatDistance(v, mpb)})`
          }
        />
        {running ? (
          <button className="btn wide" onClick={stop}>
            <Icon name="stop" size={16} /> Stop searching
          </button>
        ) : (
          <button className="btn primary wide" onClick={start} disabled={busy}>
            <Icon name="find" size={16} /> Search
          </button>
        )}
      </Section>

      {search && <SearchResults search={search} dim={dim} />}
    </>
  )
}

function progressText(s: SearchState) {
  const secs = Math.round(s.elapsedMs / 1000)
  switch (s.phase) {
    case 'screening':
      return 'Ruling out open ocean and the wrong climates…'
    case 'scanning':
      return `Scanning ${s.cellsDone.toLocaleString()} of ${s.cellsTotal.toLocaleString()} areas (${s.cellsSkipped.toLocaleString()} ruled out early)`
    case 'verifying':
      return 'Checking the best spots on full terrain…'
    case 'done':
      return `Found ${s.results?.length ?? 0} places in ${secs} s`
    case 'cancelled':
      return `Stopped after ${secs} s`
    case 'failed':
      return `The search failed: ${s.error ?? 'unknown error'}`
  }
}

function SearchResults({
  search,
  dim,
}: {
  search: SearchState
  dim: Dimension
}) {
  const hovered = ui.use((s) => s.hoveredResult)
  const results = search.results ?? []
  const mpb = dim.metersPerBlock
  const pct = search.cellsTotal
    ? (search.cellsDone / search.cellsTotal) * 100
    : 0
  return (
    <Section title="Places">
      <div className="progress" aria-live="polite">
        <div className="progress-bar">
          <span
            style={{
              width: `${search.phase === 'done' || search.phase === 'verifying' ? 100 : pct}%`,
            }}
          />
        </div>
        <p>{progressText(search)}</p>
      </div>
      {results.length === 0 && search.phase === 'done' && (
        <p className="hint">
          Nothing matched. Loosen a requirement or search a wider radius.
        </p>
      )}
      <ol className="results">
        {results.map((r, i) => (
          <li
            key={`${r.x},${r.z}`}
            className={hovered === i ? 'hot' : undefined}
            onMouseEnter={() => ui.set({ hoveredResult: i })}
            onMouseLeave={() => ui.set({ hoveredResult: null })}
          >
            <button
              className="result-main"
              onClick={() =>
                flyTo(
                  r.x,
                  r.z,
                  Math.log2(
                    Math.max(
                      300,
                      Math.hypot(r.peakX - r.x, r.peakZ - r.z) * 2.6,
                    ) / 700,
                  ),
                )
              }
            >
              <span className="result-rank">{i + 1}</span>
              <span className="result-body">
                <strong>
                  {biome(r.biome).name} at Y {r.y}
                </strong>
                <span className="result-stats">
                  <span>
                    {formatDistance(r.relief, mpb)} rise to Y {r.peakY}
                  </span>
                  {r.fraction > 0 && (
                    <span>{Math.round(r.fraction * 100)}% match</span>
                  )}
                  <span>{formatDistance(r.distance, mpb)} away</span>
                </span>
                <span className="result-meta">
                  <span>
                    {r.x}, {r.z}
                  </span>
                  <span>{r.temp.toFixed(1)} °C</span>
                  <span>{Math.round(r.precip).toLocaleString()} mm</span>
                  {r.verified ? (
                    <span className="verified">Verified</span>
                  ) : (
                    <span title="Not yet checked on full-resolution terrain">
                      Estimated
                    </span>
                  )}
                </span>
              </span>
            </button>
            <div className="result-actions">
              <button
                className="icon-btn"
                title="View in 3D"
                aria-label="View in 3D"
                onClick={() => {
                  const half = Math.max(
                    400,
                    Math.hypot(r.peakX - r.x, r.peakZ - r.z) * 1.3,
                  )
                  ui.set({
                    preview: {
                      title: `${biome(r.biome).name} at ${r.x}, ${r.z}`,
                      x0: r.x - half,
                      z0: r.z - half,
                      x1: r.x + half,
                      z1: r.z + half,
                      mark: { x: r.x, z: r.z },
                    },
                  })
                }}
              >
                <Icon name="cube" size={18} />
              </button>
              <button
                className="icon-btn"
                title="Teleport a player here"
                aria-label="Teleport a player here"
                onClick={() =>
                  ui.set({ tool: 'teleport', target: { x: r.x, z: r.z } })
                }
              >
                <Icon name="target" size={18} />
              </button>
              <button
                className="icon-btn"
                title="Save as waypoint"
                aria-label="Save as waypoint"
                onClick={() =>
                  void api('waypoints', {
                    method: 'POST',
                    json: {
                      name: `${biome(r.biome).name} ${i + 1}`,
                      dim: dim.id,
                      x: r.x,
                      z: r.z,
                      y: r.y,
                      color: '#f4889a',
                      icon: 'peak',
                    },
                  })
                    .then(() => toast('Waypoint saved'))
                    .catch((e: Error) => toast(e.message, 'error'))
                }
              >
                <Icon name="pin" size={18} />
              </button>
            </div>
          </li>
        ))}
      </ol>
    </Section>
  )
}
