// Biome names and map colours, indexed by FastDiffusion's native biome ids (TerrainBiomes.java).
// Colours are muted field-guide tints; they read well under relief shading.

export interface Biome {
  id: number
  name: string
  color: string
  ocean: boolean
}

const TABLE: [string, string][] = [
  ['Plains', '#a9c27a'],
  ['Snowy plains', '#e8eef2'],
  ['Desert', '#e3cf98'],
  ['Swamp', '#6f7d4a'],
  ['Forest', '#4f8a47'],
  ['Taiga', '#3f6e55'],
  ['Snowy taiga', '#b9cfcf'],
  ['Savanna', '#c6b86a'],
  ['Windswept hills', '#8e9a86'],
  ['Jungle', '#3d8c35'],
  ['Badlands', '#c47e4e'],
  ['Meadow', '#9fcf86'],
  ['Dry grove', '#b7c49b'],
  ['Snowy slopes', '#dfe7ef'],
  ['Frozen peaks', '#eef4fb'],
  ['Stony peaks', '#9b9c98'],
  ['Warm ocean', '#3f9fb8'],
  ['Ocean', '#2f6fa8'],
  ['Cold ocean', '#335f94'],
  ['Frozen ocean', '#7d97b8'],
  ['Open forest', '#77a35c'],
  ['Open taiga', '#5f8a6a'],
  ['Open snowy taiga', '#a7c0bc'],
  ['Deep ocean', '#1f4f87'],
  ['Deep cold ocean', '#22477a'],
  ['Deep frozen ocean', '#5e779b'],
  ['Deep lukewarm ocean', '#2b7b9c'],
  ['Beach', '#ead9a8'],
  ['Snowy beach', '#e9ebe2'],
  ['Stony shore', '#8f918a'],
]

export const BIOMES: Biome[] = TABLE.map(([name, color], id) => ({
  id,
  name,
  color,
  ocean: [16, 17, 18, 19, 23, 24, 25, 26].includes(id),
}))

export function biome(id: number): Biome {
  return (
    BIOMES[id] ?? { id, name: `Biome ${id}`, color: '#888888', ocean: false }
  )
}

export function hexToRgb(hex: string): [number, number, number] {
  const v = parseInt(hex.slice(1), 16)
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255]
}

/** Biome colours as a 64-entry RGBA8 palette texture. */
export function biomePalette(): Uint8Array {
  const out = new Uint8Array(64 * 4)
  BIOMES.forEach((b, i) => {
    const [r, g, bl] = hexToRgb(b.color)
    out.set([r, g, bl, 255], i * 4)
  })
  return out
}

export function tempC(code: number) {
  return code * 0.5 - 50
}

export function rainMm(code: number) {
  return code * 20
}
