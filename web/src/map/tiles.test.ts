import { expect, test } from 'bun:test'
import { decodeTile, TILE } from './tiles'
import { chunkCount, forEachChunk, type Selection } from '../lib/shapes'

test('tile decoder handles signed height deltas and planar climate attributes', () => {
  const n = TILE * TILE
  const buffer = new ArrayBuffer(16 + n * 5)
  const view = new DataView(buffer)
  view.setUint32(0, 0x544d4446, true)
  view.setUint8(4, 2)
  view.setUint8(5, 2)
  view.setUint16(6, TILE, true)
  view.setInt16(16, -64, true)
  view.setInt16(18, 2095, true)
  view.setInt16(20, -2095, true)
  const attrs = new Uint8Array(buffer, 16 + n * 2)
  attrs[0] = 20
  attrs[n] = 141
  attrs[2 * n] = 42
  const tile = decodeTile(buffer)
  expect(Array.from(tile.y.slice(0, 3))).toEqual([-64, 2031, -64])
  expect(Array.from(tile.attrs.slice(0, 4))).toEqual([20, 141, 42, 255])
  expect(tile.quality).toBe(2)
  expect(() => decodeTile(new ArrayBuffer(20))).toThrow('bad tile')
})

test('selection counts match Chunky rounding, including negative coordinates', () => {
  const sel: Selection = {
    shape: 'square',
    x: -768,
    z: 3840,
    radiusX: 32,
    radiusZ: 32,
    pattern: 'concentric',
  }
  expect(chunkCount(sel)).toBe(25) // Confirmed against Chunky 1.4.23's live task.
  expect(chunkCount({ ...sel, radiusX: 512 })).toBe(4225)
  let count = 0
  forEachChunk(sel, 100, () => count++)
  expect(count).toBe(25)
  expect(chunkCount({ ...sel, shape: 'rectangle', radiusZ: 64 })).toBe(45)
})
