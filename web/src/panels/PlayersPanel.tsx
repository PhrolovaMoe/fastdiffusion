import { Icon } from '../components/Icon'
import type { Dimension } from '../lib/api'
import { live } from '../lib/live'
import { flyTo, ui } from '../lib/ui'

export function playerHue(uuid: string) {
  let h = 0
  for (const c of uuid) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return h % 360
}

export function dimName(id: string) {
  const known: Record<string, string> = {
    'minecraft:overworld': 'Overworld',
    'minecraft:the_nether': 'Nether',
    'minecraft:the_end': 'End',
  }
  return known[id] ?? id.replace(/^.*:/, '').replace(/_/g, ' ')
}

export function PlayersPanel({ dim }: { dim: Dimension }) {
  const players = Object.values(live.use((s) => s.players)).sort((a, b) =>
    a.name.localeCompare(b.name),
  )
  const followed = ui.use((s) => s.followed)
  const picking = ui.use((s) =>
    s.tool === 'teleport' ? s.teleportPlayer : null,
  )

  if (players.length === 0)
    return (
      <p className="empty">
        Nobody is online. Players appear here the moment they join, and move
        live on the map.
      </p>
    )

  return (
    <ul className="players">
      {players.map((p) => {
        const here = p.dim === dim.id
        return (
          <li
            key={p.uuid}
            className={followed === p.uuid ? 'followed' : undefined}
          >
            <span
              className="avatar"
              style={{ '--hue': playerHue(p.uuid) } as React.CSSProperties}
              aria-hidden
            >
              {p.name.slice(0, 1).toUpperCase()}
            </span>
            <div className="player-body">
              <strong>{p.name}</strong>
              <span className="player-where">
                {here
                  ? `${Math.floor(p.x)}, ${Math.floor(p.y)}, ${Math.floor(p.z)}`
                  : dimName(p.dim)}
                {p.mode !== 'survival' && <span className="tag">{p.mode}</span>}
                {p.sleeping && <span className="tag">asleep</span>}
              </span>
              <span
                className="vitals"
                aria-label={`Health ${Math.round(p.health)} of 20, food ${p.food} of 20`}
              >
                <span
                  className="vital health"
                  style={{ width: `${(p.health / 20) * 100}%` }}
                />
                <span
                  className="vital food"
                  style={{ width: `${(p.food / 20) * 100}%` }}
                />
              </span>
            </div>
            <div className="player-actions">
              <button
                className="icon-btn"
                disabled={!here}
                title="Show on map"
                aria-label={`Show ${p.name} on the map`}
                onClick={() => flyTo(p.x, p.z)}
              >
                <Icon name="pin" size={18} />
              </button>
              <button
                className={followed === p.uuid ? 'icon-btn on' : 'icon-btn'}
                disabled={!here}
                title="Follow"
                aria-pressed={followed === p.uuid}
                aria-label={`Follow ${p.name}`}
                onClick={() =>
                  ui.set({ followed: followed === p.uuid ? null : p.uuid })
                }
              >
                <Icon name="follow" size={18} />
              </button>
              <button
                className={picking === p.uuid ? 'icon-btn on' : 'icon-btn'}
                title="Teleport"
                aria-label={`Teleport ${p.name}`}
                onClick={() =>
                  ui.set({
                    tool: 'teleport',
                    teleportPlayer: p.uuid,
                    target: null,
                  })
                }
              >
                <Icon name="target" size={18} />
              </button>
            </div>
          </li>
        )
      })}
      {picking && (
        <li className="hint">
          Click the map where {live.get().players[picking]?.name} should go.
        </li>
      )}
    </ul>
  )
}
