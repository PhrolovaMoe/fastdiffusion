// Line icons drawn for this map: 20px grid, 1.6px strokes, round joins.

const PATHS: Record<string, string> = {
  layers: 'M10 3 2.5 7 10 11l7.5-4L10 3Zm-7.5 7L10 14l7.5-4M2.5 13 10 17l7.5-4',
  find: 'M8.5 3.5a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm3.6 8.6 4.4 4.4M6.3 10.2l1.6-2.6 1.3 1.5 1-1.3',
  players:
    'M10 9.5a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm-5.5 7c.6-3 2.8-4.5 5.5-4.5s4.9 1.5 5.5 4.5',
  pin: 'M10 17.5s5-4.6 5-8.5a5 5 0 0 0-10 0c0 3.9 5 8.5 5 8.5Zm0-6.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z',
  generate: 'M3 3h6v6H3V3Zm8 0h6v6h-6V3ZM3 11h6v6H3v-6Zm11 1v4m-2-2h4',
  close: 'm5 5 10 10M15 5 5 15',
  plus: 'M10 4v12M4 10h12',
  minus: 'M4 10h12',
  ruler:
    'm3 13.5 10.5-10.5 3.5 3.5L6.5 17 3 13.5Zm3-1 1.5 1.5m.5-4.5 2 2m.5-4.5 1.5 1.5',
  target: 'M10 2.5v3m0 9v3M2.5 10h3m9 0h3M10 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  cube: 'm10 2.5 6.5 3.7v7.6L10 17.5l-6.5-3.7V6.2L10 2.5Zm0 0v0m-6.5 3.7L10 10l6.5-3.8M10 10v7.5',
  follow: 'M10 2.5 16 17l-6-3.5L4 17l6-14.5Z',
  cursor: 'm4 3 12 6-5.5 1.5L9 16 4 3Z',
  select: 'M3 3h3m3 0h2m3 0h3v3m0 3v2m0 3v3h-3m-3 0H9m-3 0H3v-3m0-3V9m0-3V3',
  trash: 'M4 6h12M8 6V4h4v2m-6 0 .7 10h6.6L14 6',
  play: 'M6 4v12l10-6L6 4Z',
  pause: 'M6.5 4v12m7-12v12',
  stop: 'M5 5h10v10H5z',
  copy: 'M7 7h9v9H7V7Zm-3 6V4h9',
  chevron: 'm8 5 5 5-5 5',
  sun: 'M10 13.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM10 1.5v2m0 13v2M1.5 10h2m13 0h2M4 4l1.4 1.4m9.2 9.2L16 16M4 16l1.4-1.4m9.2-9.2L16 4',
  peak: 'm2 16 5.5-9 3 4.5L13 8l5 8H2Z',
  home: 'M3.5 9.5 10 4l6.5 5.5M5.5 8v8.5h9V8m-6 8.5V12h3v4.5',
}

export function Icon({
  name,
  size = 20,
  label,
}: {
  name: keyof typeof PATHS | string
  size?: number
  label?: string
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <path d={PATHS[name] ?? ''} />
    </svg>
  )
}
