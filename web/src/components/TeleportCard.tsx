import { useState } from 'react'
import { api, type Dimension } from '../lib/api'
import { biome } from '../lib/biomes'
import { live } from '../lib/live'
import { toast, ui } from '../lib/ui'
import { mapContext } from '../map/MapView'

// Confirms a teleport to a picked map position. The server generates the target chunk first and
// places the player on the real surface; the Y shown here is the map's estimate.

export function TeleportCard({ dim }: { dim: Dimension }) {
  const target = ui.use((s) => s.target)
  const chosen = ui.use((s) => s.teleportPlayer)
  const players = Object.values(live.use((s) => s.players))
  const [busy, setBusy] = useState(false)
  if (!target) return null
  const player =
    (chosen && players.find((p) => p.uuid === chosen)) || players[0]
  const sample =
    mapContext.store?.sample(target.x + 0.5, target.z + 0.5, 0) ?? null
  const wet = sample && sample.y < dim.seaLevel - 1.5
  const close = () => ui.set({ target: null, tool: 'inspect' })

  const go = async () => {
    if (!player) return
    setBusy(true)
    try {
      const r = await api<{ y: number }>('teleport', {
        method: 'POST',
        json: { player: player.uuid, dim: dim.id, x: target.x, z: target.z },
      })
      toast(`Teleported ${player.name} to ${target.x}, ${r.y}, ${target.z}`)
      ui.set({ target: null, tool: 'inspect', followed: player.uuid })
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Teleport failed', 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card teleport" role="dialog" aria-label="Teleport">
      <h2>
        Teleport to {target.x}, {target.z}
      </h2>
      {sample ? (
        <p>
          {biome(sample.biome).name}, ground near Y {Math.round(sample.y)}.
          {wet && ' This is open water: the player will land on the surface.'}
        </p>
      ) : (
        <p>
          This spot has not been mapped yet. The player lands on the surface
          once it is generated.
        </p>
      )}
      {players.length === 0 ? (
        <p className="hint">Nobody is online to teleport.</p>
      ) : (
        <label className="field">
          Player
          <select
            className="select"
            value={chosen ?? player?.uuid ?? ''}
            onChange={(e) => ui.set({ teleportPlayer: e.target.value })}
          >
            {chosen && !player && (
              <option value={chosen}>Selected player went offline</option>
            )}
            {players.map((p) => (
              <option key={p.uuid} value={p.uuid}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
      )}
      <div className="row">
        <button className="btn primary" disabled={!player || busy} onClick={go}>
          {busy ? 'Generating terrain…' : `Teleport ${player?.name ?? ''}`}
        </button>
        <button className="btn ghost" onClick={close}>
          Cancel
        </button>
      </div>
    </div>
  )
}
