import { useMemo, useState } from 'react'
import { Icon } from '../components/Icon'
import { NumberField, Section } from '../components/controls'
import { api, type ChunkyStatus, type Dimension } from '../lib/api'
import { live } from '../lib/live'
import { prefs } from '../lib/prefs'
import {
  chunkCount,
  isUniform,
  type Selection,
  type ShapeKind,
} from '../lib/shapes'
import { toast, ui } from '../lib/ui'
import { mapContext } from '../map/MapView'

export function GeneratePanel({ dim }: { dim: Dimension }) {
  const selection = ui.use((s) => s.selection)
  const info = live.use((s) => s.info)?.chunky
  const status = live.use((s) => s.chunky[dim.id])
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const count = useMemo(
    () => (selection ? chunkCount(selection) : 0),
    [selection],
  )
  const active =
    status?.state === 'running' ||
    status?.state === 'paused' ||
    status?.state === 'pausing'
  const patch = (p: Partial<Selection>) => {
    if (selection) {
      ui.set({ selection: { ...selection, ...p } })
      setConfirm(false)
    }
  }
  const action = async (name: string) => {
    setBusy(true)
    try {
      const r = await api<ChunkyStatus>(
        `chunky/${encodeURIComponent(dim.id)}/${name}`,
        { method: 'POST', json: selection ?? {} },
      )
      live.set((s) => ({ chunky: { ...s.chunky, [dim.id]: r } }))
      setConfirm(false)
      toast(
        name === 'start'
          ? 'Chunk generation started'
          : name === 'continue'
            ? 'Chunk generation resumed'
            : `Chunk generation ${name === 'pause' ? 'paused' : 'cancelled'}`,
      )
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Chunky request failed', 'error')
    } finally {
      setBusy(false)
    }
  }
  if (!info?.installed)
    return (
      <p className="empty">
        Install Chunky on the server to generate chunks from the map.
      </p>
    )
  if (!info.ready)
    return (
      <p className="empty">
        Chunky is starting. Reopen the map once the server is ready.
      </p>
    )
  return (
    <>
      {active && (
        <Section title="Generation">
          <div className="progress-bar chunky">
            <span style={{ width: `${status.percent ?? 0}%` }} />
          </div>
          <div className="metrics">
            <strong>{(status.percent ?? 0).toFixed(1)}%</strong>
            <span>{status.state}</span>
          </div>
          <p className="hint">
            {(status.done ?? 0).toLocaleString()} /{' '}
            {(status.total ?? 0).toLocaleString()} chunks ·{' '}
            {(status.rate ?? 0).toFixed(0)} chunks/s ·{' '}
            {duration(status.etaSeconds ?? 0)} remaining
          </p>
          <div className="row">
            <button
              className="btn primary"
              disabled={busy || status.state === 'pausing'}
              onClick={() =>
                void action(status.state === 'paused' ? 'continue' : 'pause')
              }
            >
              <Icon name={status.state === 'paused' ? 'play' : 'pause'} />
              {status.state === 'paused'
                ? 'Resume'
                : status.state === 'pausing'
                  ? 'Pausing…'
                  : 'Pause'}
            </button>
            <button
              className="btn danger"
              disabled={busy}
              onClick={() => setConfirm(!confirm)}
            >
              Cancel task
            </button>
          </div>
          {confirm && (
            <div className="notice">
              <p>
                Cancel this generation task? Chunks already generated stay in
                the world.
              </p>
              <button
                className="btn danger"
                disabled={busy}
                onClick={() => void action('cancel')}
              >
                Cancel generation
              </button>
            </div>
          )}
        </Section>
      )}
      <Section title="Area">
        <button
          className="btn"
          onClick={() => {
            const v = ui.get().view
            ui.set({
              tool: 'select',
              panel: window.innerWidth < 640 ? null : 'generate',
              selection: selection ?? {
                shape: 'square',
                pattern: 'concentric',
                x: Math.round(v.cx),
                z: Math.round(v.cz),
                radiusX: 512,
                radiusZ: 512,
              },
            })
            prefs.set({ generated: true })
          }}
        >
          <Icon name="select" />
          Draw on map
        </button>
        {selection && (
          <>
            <label className="field">
              Shape
              <select
                className="select"
                value={selection.shape}
                onChange={(e) => patch({ shape: e.target.value as ShapeKind })}
              >
                {info.shapes.map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
            <div className="two-col">
              <NumberField
                label="Centre X"
                value={selection.x}
                onChange={(x) => patch({ x })}
              />
              <NumberField
                label="Centre Z"
                value={selection.z}
                onChange={(z) => patch({ z })}
              />
            </div>
            <NumberField
              label={isUniform(selection.shape) ? 'Radius' : 'X radius'}
              value={selection.radiusX}
              min={16}
              max={1000000}
              suffix="blocks"
              onChange={(radiusX) =>
                patch({ radiusX: Math.max(16, Math.min(1000000, radiusX)) })
              }
            />
            {!isUniform(selection.shape) && (
              <NumberField
                label="Z radius"
                value={selection.radiusZ}
                min={16}
                max={1000000}
                suffix="blocks"
                onChange={(radiusZ) =>
                  patch({ radiusZ: Math.max(16, Math.min(1000000, radiusZ)) })
                }
              />
            )}
            <label className="field">
              Pattern
              <select
                className="select"
                value={selection.pattern}
                onChange={(e) => patch({ pattern: e.target.value })}
              >
                {info.patterns.map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
            <div className="metrics">
              <strong>{count.toLocaleString()}</strong>
              <span>
                {count > 200000 ? 'estimated chunks' : 'chunks selected'}
              </span>
            </div>
            <p className="hint">
              Estimated time: {duration(count / (status?.rate || 128))}.{' '}
              {mapContext.generated?.bytesPerChunk
                ? `Disk estimate: ${((count * mapContext.generated.bytesPerChunk) / 1024 ** 3).toFixed(2)} GiB.`
                : 'Disk estimate becomes available after chunks have been saved.'}
            </p>
            <button
              className="btn"
              onClick={() => {
                const rz = isUniform(selection.shape)
                  ? selection.radiusX
                  : selection.radiusZ
                ui.set({
                  preview: {
                    title: 'Generation area',
                    x0: selection.x - selection.radiusX,
                    x1: selection.x + selection.radiusX,
                    z0: selection.z - rz,
                    z1: selection.z + rz,
                  },
                })
              }}
            >
              <Icon name="cube" />
              Preview terrain in 3D
            </button>
            {!active && (
              <>
                <button
                  className="btn primary wide"
                  disabled={busy || count === 0}
                  onClick={() => setConfirm(true)}
                >
                  Generate {count.toLocaleString()} chunks
                </button>
                {confirm && (
                  <div className="notice">
                    <p>
                      Generate this area in {dim.id}? This writes chunks to the
                      world. The time estimate uses{' '}
                      {status?.rate
                        ? 'the latest measured rate'
                        : '128 chunks/s as a starting estimate'}
                      .
                    </p>
                    <button
                      className="btn primary"
                      disabled={busy}
                      onClick={() => void action('start')}
                    >
                      {busy ? 'Starting…' : 'Start generation'}
                    </button>
                    <button
                      className="btn ghost"
                      onClick={() => setConfirm(false)}
                    >
                      Back
                    </button>
                  </div>
                )}
              </>
            )}
          </>
        )}
      </Section>
    </>
  )
}
function duration(s: number) {
  return s >= 3600
    ? `${(s / 3600).toFixed(1)} h`
    : s >= 60
      ? `${Math.ceil(s / 60)} min`
      : `${Math.ceil(s)} s`
}
