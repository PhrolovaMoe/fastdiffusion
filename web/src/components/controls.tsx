import { useId, type CSSProperties, type ReactNode } from 'react'

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
  label: string
}) {
  return (
    <div className="segmented" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.value}
          role="radio"
          aria-checked={o.value === value}
          className={o.value === value ? 'on' : undefined}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Toggle({
  checked,
  onChange,
  children,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  children: ReactNode
}) {
  return (
    <label className="toggle">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="toggle-track" aria-hidden />
      <span>{children}</span>
    </label>
  )
}

export function Slider({
  label,
  value,
  min,
  max,
  step,
  onChange,
  format,
}: {
  label: string
  value: number
  min: number
  max: number
  step: number
  onChange: (v: number) => void
  format?: (v: number) => string
}) {
  const id = useId()
  return (
    <div className="slider">
      <label htmlFor={id}>{label}</label>
      <output htmlFor={id}>{format ? format(value) : value}</output>
      <input
        id={id}
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        style={{ '--fill': `${((value - min) / (max - min)) * 100}%` } as CSSProperties}
        onChange={(e) => onChange(+e.target.value)}
      />
    </div>
  )
}

/** Two-thumb range. `null` means the bound is off. */
export function RangeField({
  label,
  value,
  min,
  max,
  step,
  onChange,
  format,
}: {
  label: string
  value: { min: number; max: number } | null
  min: number
  max: number
  step: number
  onChange: (v: { min: number; max: number } | null) => void
  format: (v: number) => string
}) {
  const v = value ?? { min, max }
  const lo = ((v.min - min) / (max - min)) * 100
  const hi = ((v.max - min) / (max - min)) * 100
  return (
    <div className={`range ${value ? 'active' : ''}`}>
      <div className="range-head">
        <Toggle
          checked={value !== null}
          onChange={(on) => onChange(on ? { min, max } : null)}
        >
          {label}
        </Toggle>
        <span className="range-value">
          {format(v.min)} to {format(v.max)}
        </span>
      </div>
      <div
        className="range-track"
        style={{ '--lo': `${lo}%`, '--hi': `${hi}%` } as CSSProperties}
      >
        <input
          type="range"
          aria-label={`${label} from`}
          min={min}
          max={max}
          step={step}
          value={v.min}
          disabled={!value}
          onChange={(e) =>
            onChange({ min: Math.min(+e.target.value, v.max), max: v.max })
          }
        />
        <input
          type="range"
          aria-label={`${label} to`}
          min={min}
          max={max}
          step={step}
          value={v.max}
          disabled={!value}
          onChange={(e) =>
            onChange({ min: v.min, max: Math.max(+e.target.value, v.min) })
          }
        />
      </div>
    </div>
  )
}

export function NumberField({
  label,
  value,
  onChange,
  suffix,
  min,
  max,
  step = 1,
}: {
  label: string
  value: number
  onChange: (v: number) => void
  suffix?: string
  min?: number
  max?: number
  step?: number
}) {
  const id = useId()
  return (
    <div className="numfield">
      <label htmlFor={id}>{label}</label>
      <div>
        <input
          id={id}
          type="number"
          inputMode="numeric"
          value={Number.isFinite(value) ? value : ''}
          min={min}
          max={max}
          step={step}
          onChange={(e) => {
            const n = parseFloat(e.target.value)
            if (Number.isFinite(n)) onChange(n)
          }}
        />
        {suffix && <span>{suffix}</span>}
      </div>
    </div>
  )
}

export function Section({
  title,
  children,
  aside,
}: {
  title: string
  children: ReactNode
  aside?: ReactNode
}) {
  return (
    <section className="psection">
      <header>
        <h3>{title}</h3>
        {aside}
      </header>
      {children}
    </section>
  )
}
