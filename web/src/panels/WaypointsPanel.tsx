import { useState } from 'react'
import { Icon } from '../components/Icon'
import { api, type Dimension, type Waypoint } from '../lib/api'
import { live } from '../lib/live'
import { flyTo, toast, ui } from '../lib/ui'

export async function saveWaypoint(
  dim: string,
  x: number,
  z: number,
  name: string,
  color = '#f2c14e',
) {
  try {
    await api<Waypoint>('waypoints', {
      method: 'POST',
      json: { dim, x, z, name, color },
    })
    toast('Waypoint saved')
  } catch (e) {
    toast(e instanceof Error ? e.message : 'Could not save waypoint', 'error')
  }
}
export function WaypointsPanel({ dim }: { dim: Dimension }) {
  const points = live.use((s) => s.waypoints).filter((p) => p.dim === dim.id)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [deleting, setDeleting] = useState<string | null>(null)
  const save = async () => {
    setBusy(true)
    const v = ui.get().view
    await saveWaypoint(dim.id, Math.floor(v.cx), Math.floor(v.cz), name)
    setBusy(false)
    setName('')
  }
  return (
    <>
      <form
        className="waypoint-form"
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
      >
        <label className="field">
          Save the map centre
          <input
            value={name}
            maxLength={64}
            required
            placeholder="Waypoint name"
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <button className="btn primary" disabled={busy || !name.trim()}>
          <Icon name="pin" />
          Save waypoint
        </button>
      </form>
      {!points.length && (
        <p className="empty">
          No waypoints in this dimension. Saved places are shared with every
          operator.
        </p>
      )}
      <ul className="waypoints">
        {points.map((p) => (
          <li key={p.id}>
            <button className="waypoint-link" onClick={() => flyTo(p.x, p.z)}>
              <span className="chip-dot" style={{ background: p.color }} />
              <span>
                <strong>{p.name}</strong>
                <small>
                  {p.x}, {p.z}
                </small>
              </span>
            </button>
            <button
              className="icon-btn"
              aria-label={`Delete ${p.name}`}
              onClick={() => setDeleting(p.id)}
            >
              <Icon name="trash" />
            </button>
            {deleting === p.id && (
              <div className="notice">
                <p>Delete {p.name} for all operators?</p>
                <button
                  className="btn danger"
                  onClick={async () => {
                    try {
                      await api(`waypoints/${p.id}`, { method: 'DELETE' })
                      setDeleting(null)
                    } catch (e) {
                      toast(
                        e instanceof Error ? e.message : 'Delete failed',
                        'error',
                      )
                    }
                  }}
                >
                  Delete
                </button>
                <button className="btn ghost" onClick={() => setDeleting(null)}>
                  Keep
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </>
  )
}
