import { expect, test } from 'bun:test'
import { justify, widthOf } from './justify'
import type { Item } from './manifest'

const photo = (id: string, width: number, height: number): Item =>
  ({ id, kind: 'photo', status: 'shareable', width, height, thumbUrl: 't' }) as Item

// The fixture album's real shapes, including the ones that break a grid.
const panorama = photo('panorama', 2000, 780)
const portrait = photo('portrait', 683, 1024)
const square = photo('square', 1024, 1024)
const landscape = photo('landscape', 2000, 1333)

test('a row fills the measure exactly', () => {
  const rows = justify([landscape, portrait, square, panorama, landscape], 1200, 300, 8)
  for (const row of rows.slice(0, -1)) {
    const width = row.items.reduce((sum, item) => sum + widthOf(item, row.height), 0) + 8 * (row.items.length - 1)
    expect(Math.round(width)).toBe(1200)
  }
})

test('nothing is cropped: every tile keeps its own ratio', () => {
  const items = [panorama, portrait, square, landscape]
  for (const row of justify(items, 1200, 300, 8)) {
    for (const item of row.items) {
      const ratio = widthOf(item, row.height) / row.height
      expect(ratio).toBeCloseTo(item.width! / item.height!, 5)
    }
  }
})

test('the last row does not stretch', () => {
  // Two leftovers must not become two enormous photographs.
  const rows = justify([landscape, landscape, landscape, portrait, portrait], 1200, 300, 8)
  expect(rows[rows.length - 1].height).toBeLessThanOrEqual(300)
})

test('a panorama beside a portrait does not produce an absurd row height', () => {
  const rows = justify([panorama, portrait], 1200, 300, 8)
  for (const row of rows) expect(row.height).toBeLessThan(700)
})

test('every item appears exactly once, in order', () => {
  const items = [panorama, portrait, square, landscape, photo('e', 1600, 900), photo('f', 900, 1600)]
  const flat = justify(items, 1000, 260, 8).flatMap((row) => row.items.map((item) => item.id))
  expect(flat).toEqual(items.map((item) => item.id))
})

test('a missing size falls back rather than dividing by zero', () => {
  const unknown = { id: 'x', kind: 'photo', status: 'shareable', thumbUrl: 't' } as Item
  const rows = justify([unknown], 1200, 300, 8)
  expect(rows[0].height).toBeGreaterThan(0)
  expect(Number.isFinite(widthOf(unknown, rows[0].height))).toBe(true)
})

test('an empty album produces no rows', () => {
  expect(justify([], 1200, 300, 8)).toEqual([])
  expect(justify([landscape], 0, 300, 8)).toEqual([])
})
