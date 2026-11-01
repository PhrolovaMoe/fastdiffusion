import { useMemo } from 'react'
import { BIOMES } from '../lib/biomes'
import { live } from '../lib/live'

export function BiomeChips({
  selected,
  onChange,
}: {
  selected: number[]
  onChange: (ids: number[]) => void
}) {
  const info = live.use((s) => s.info)
  const list = useMemo(() => {
    const generated = new Set(
      info?.biomes.filter((b) => b.generated).map((b) => b.id) ??
        BIOMES.map((b) => b.id),
    )
    return BIOMES.filter((b) => generated.has(b.id))
  }, [info])
  const set = new Set(selected)
  return (
    <div className="chips" role="group" aria-label="Biomes">
      {list.map((b) => (
        <button
          key={b.id}
          type="button"
          aria-pressed={set.has(b.id)}
          className={set.has(b.id) ? 'chip on' : 'chip'}
          onClick={() =>
            onChange(
              set.has(b.id)
                ? selected.filter((x) => x !== b.id)
                : [...selected, b.id],
            )
          }
        >
          <span className="chip-dot" style={{ background: b.color }} />
          {b.name}
        </button>
      ))}
    </div>
  )
}
