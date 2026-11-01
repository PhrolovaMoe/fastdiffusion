import { BiomeChips } from '../components/BiomeChips'
import { RangeField, Section, Slider, Toggle } from '../components/controls'
import type { Dimension } from '../lib/api'
import {
  defaultFilters,
  filtersActive,
  prefs,
  setFilters,
  type MapMode,
} from '../lib/prefs'
import { ui } from '../lib/ui'
import { findDraft } from './FindPanel'

const MODES: { value: MapMode; label: string; swatch: string }[] = [
  {
    value: 'atlas',
    label: 'Atlas',
    swatch:
      'linear-gradient(135deg, #93ab7d, #cfbf91 45%, #b5a187 70%, #f3f5f8)',
  },
  {
    value: 'biomes',
    label: 'Biomes',
    swatch:
      'conic-gradient(#4f8a47, #e3cf98, #b9cfcf, #3d8c35, #a9c27a, #4f8a47)',
  },
  {
    value: 'height',
    label: 'Height',
    swatch: 'linear-gradient(135deg, #3d7852, #d4c973, #94613f, #f7f7fa)',
  },
  {
    value: 'temperature',
    label: 'Temperature',
    swatch: 'linear-gradient(135deg, #2c55a8, #e8edf2, #c7403a)',
  },
  {
    value: 'rainfall',
    label: 'Rainfall',
    swatch: 'linear-gradient(135deg, #d9c49a, #9cc58f, #3f9a9a, #1d4f87)',
  },
  {
    value: 'slope',
    label: 'Slope',
    swatch: 'linear-gradient(135deg, #f2f0e8, #e3b04b, #c8443c, #5a1f2b)',
  },
]

export function LayersPanel({ dim }: { dim: Dimension }) {
  const p = prefs.use((s) => s)
  const f = p.filters
  const mpb = dim.metersPerBlock
  return (
    <>
      <Section title="Map style">
        <div className="modes" role="radiogroup" aria-label="Map style">
          {MODES.map((m) => (
            <button
              key={m.value}
              role="radio"
              aria-checked={p.mode === m.value}
              className={p.mode === m.value ? 'mode on' : 'mode'}
              onClick={() => prefs.set({ mode: m.value })}
            >
              <span className="mode-swatch" style={{ background: m.swatch }} />
              {m.label}
            </button>
          ))}
        </div>
        <Slider
          label="Relief"
          value={p.relief}
          min={0}
          max={4}
          step={0.1}
          onChange={(v) => prefs.set({ relief: v })}
          format={(v) => `${v.toFixed(1)}×`}
        />
        <Slider
          label="Light from"
          value={p.sunAzimuth}
          min={0}
          max={359}
          step={1}
          onChange={(v) => prefs.set({ sunAzimuth: v })}
          format={(v) => compass(v)}
        />
        <Slider
          label="Sun height"
          value={p.sunAltitude}
          min={10}
          max={80}
          step={1}
          onChange={(v) => prefs.set({ sunAltitude: v })}
          format={(v) => `${v}°`}
        />
        <Toggle
          checked={p.contours}
          onChange={(v) => prefs.set({ contours: v })}
        >
          Contour lines
        </Toggle>
        {p.contours && (
          <Slider
            label="Contour interval"
            value={p.contourInterval}
            min={5}
            max={250}
            step={5}
            onChange={(v) => prefs.set({ contourInterval: v })}
            format={(v) => `${v} blocks, ${Math.round(v * mpb)} m`}
          />
        )}
      </Section>

      <Section title="On the map">
        <Toggle checked={p.labels} onChange={(v) => prefs.set({ labels: v })}>
          Names of players and waypoints
        </Toggle>
        <Toggle
          checked={p.chunkGrid}
          onChange={(v) => prefs.set({ chunkGrid: v })}
        >
          Chunk grid when zoomed in
        </Toggle>
        <Toggle
          checked={p.regionGrid}
          onChange={(v) => prefs.set({ regionGrid: v })}
        >
          Region files (512 blocks)
        </Toggle>
        <Toggle
          checked={p.generated}
          onChange={(v) => prefs.set({ generated: v })}
        >
          Chunks saved to disk
        </Toggle>
      </Section>

      <Section
        title="Highlight matching ground"
        aside={
          <Toggle
            checked={f.enabled}
            onChange={(v) => setFilters({ enabled: v })}
          >
            <span className="sr-only">Highlight</span>
          </Toggle>
        }
      >
        <fieldset disabled={!f.enabled} className="filters">
          <RangeField
            label="Height"
            value={f.height}
            min={dim.minY}
            max={dim.maxY}
            step={1}
            onChange={(v) => setFilters({ height: v })}
            format={(v) => `Y ${v}`}
          />
          <RangeField
            label="Temperature"
            value={f.temperature}
            min={-30}
            max={45}
            step={0.5}
            onChange={(v) => setFilters({ temperature: v })}
            format={(v) => `${v} °C`}
          />
          <RangeField
            label="Rainfall"
            value={f.rainfall}
            min={0}
            max={5000}
            step={20}
            onChange={(v) => setFilters({ rainfall: v })}
            format={(v) => `${v.toLocaleString()} mm`}
          />
          <RangeField
            label="Steepness"
            value={f.slopeMax === null ? null : { min: 0, max: f.slopeMax }}
            min={0}
            max={3}
            step={0.05}
            onChange={(v) => setFilters({ slopeMax: v ? v.max : null })}
            format={(v) => `${Math.round((Math.atan(v) * 180) / Math.PI)}°`}
          />
          <BiomeChips
            selected={f.biomes}
            onChange={(biomes) => setFilters({ biomes })}
          />
        </fieldset>
        {filtersActive(f) && (
          <div className="row">
            <button
              className="btn"
              onClick={() => {
                findDraft.fromFilters(f)
                ui.set({ panel: 'find' })
              }}
            >
              Find places like this
            </button>
            <button
              className="btn ghost"
              onClick={() => setFilters({ ...defaultFilters, enabled: true })}
            >
              Clear
            </button>
          </div>
        )}
      </Section>
    </>
  )
}

function compass(deg: number) {
  const names = [
    'north',
    'north-east',
    'east',
    'south-east',
    'south',
    'south-west',
    'west',
    'north-west',
  ]
  return `${names[Math.round(deg / 45) % 8]} (${deg}°)`
}
