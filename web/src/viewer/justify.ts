import { type Item, ratioOf } from './manifest'

export type Row = { items: Item[]; height: number }

/**
 * Justified rows without cropping.
 *
 * A row is filled by solving for the height that makes the items' own aspect ratios add up to the
 * measure. Nothing is cropped and nothing is letterboxed: a 2.6:1 panorama and a 4:5 portrait sit
 * in one row at the same height and their own widths (DESIGN.md).
 *
 * The last row keeps the target height rather than stretching, so three leftover photographs do not
 * become three enormous ones.
 */
export function justify(items: Item[], measure: number, target: number, gutter: number): Row[] {
  if (measure <= 0 || items.length === 0) return []

  const rows: Row[] = []
  let current: Item[] = []
  let ratioSum = 0

  for (const item of items) {
    current.push(item)
    ratioSum += ratioOf(item)
    const gaps = gutter * (current.length - 1)
    if (ratioSum * target + gaps >= measure) {
      rows.push({ items: current, height: (measure - gaps) / ratioSum })
      current = []
      ratioSum = 0
    }
  }

  if (current.length > 0) {
    const gaps = gutter * (current.length - 1)
    rows.push({ items: current, height: Math.min(target, (measure - gaps) / ratioSum) })
  }
  return rows
}

export function widthOf(item: Item, rowHeight: number): number {
  return ratioOf(item) * rowHeight
}
