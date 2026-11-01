import { useEffect, useState } from 'react'
import type { Dimension } from '../lib/api'
import { ui } from '../lib/ui'
import { mapContext } from '../map/MapView'
import { formatDistance } from '../map/view'
import { Icon } from './Icon'

export function ElevationProfile({ dim }: { dim: Dimension }) {
  const line = ui.use((s) => s.measure)!
  const [heights, setHeights] = useState<number[]>([])
  useEffect(() => {
    const update = () => {
      const values = Array.from(
        { length: 128 },
        (_, i) =>
          mapContext.store?.sample(
            line.ax + ((line.bx - line.ax) * i) / 127,
            line.az + ((line.bz - line.az) * i) / 127,
            0,
          )?.y ?? NaN,
      )
      setHeights(values)
    }
    update()
    const timer = setInterval(update, 1000)
    return () => clearInterval(timer)
  }, [line])
  const available = heights.filter(Number.isFinite)
  const min = available.length ? Math.min(...available) : 0
  const max = available.length ? Math.max(...available) : 1
  const span = Math.max(1, max - min)
  // Break the path at unmapped ground, so missing data never looks like real terrain.
  let joined = false
  const path = heights
    .map((y, i) => {
      if (!Number.isFinite(y)) {
        joined = false
        return ''
      }
      const command = joined ? 'L' : 'M'
      joined = true
      return `${command}${(i / 127) * 600},${90 - ((y - min) / span) * 72}`
    })
    .join(' ')
  return (
    <div className="profile card">
      <header>
        <h2>Elevation profile</h2>
        <span>
          {formatDistance(
            Math.hypot(line.bx - line.ax, line.bz - line.az),
            dim.metersPerBlock,
          )}
        </span>
        <button
          className="icon-btn"
          aria-label="Close elevation profile"
          onClick={() => ui.set({ measure: null, tool: 'inspect' })}
        >
          <Icon name="close" />
        </button>
      </header>
      {available.length ? (
        <>
          <svg
            viewBox="0 0 600 100"
            role="img"
            aria-label={`Elevation from Y ${Math.round(min)} to Y ${Math.round(max)}`}
          >
            <path d={path} fill="none" stroke="var(--accent)" strokeWidth="2" />
          </svg>
          <div className="row hint">
            <span>
              Y {Math.round(min)} – {Math.round(max)}
            </span>
            {available.length < heights.length && (
              <span>Gaps are terrain that has not loaded yet</span>
            )}
          </div>
        </>
      ) : (
        <p className="hint">
          Zoom in or wait for the terrain to load along this line.
        </p>
      )}
    </div>
  )
}
