import { useEffect, useRef, useState } from 'react'
import { justify, widthOf } from './justify'
import { type Item, type Manifest, isReady, ratioOf } from './manifest'

/** The measure, watched so the rows re-solve on resize and rotation. */
function useMeasure() {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    setWidth(element.getBoundingClientRect().width)
    return () => observer.disconnect()
  }, [])
  return [ref, width] as const
}

function readToken(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

function seconds(durationMs?: number | null): string {
  if (!durationMs) return ''
  const total = Math.round(durationMs / 1000)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function Tile({ item, height, onOpen }: { item: Item; height: number; onOpen: () => void }) {
  const style = { width: `${widthOf(item, height)}px`, height: `${height}px`, ['--tile-ratio' as string]: `${ratioOf(item)}` }

  if (item.status === 'failed') {
    return (
      <div className="tile tile--waiting tile--failed" style={style}>
        could not be processed
      </div>
    )
  }
  if (!isReady(item)) {
    return (
      <div className="tile tile--waiting" style={style}>
        still processing
      </div>
    )
  }

  return (
    <button type="button" className="tile" style={style} onClick={onOpen}>
      <img
        src={item.thumbUrl!}
        alt={item.caption ?? ''}
        width={item.width ?? undefined}
        height={item.height ?? undefined}
        loading="lazy"
        decoding="async"
      />
      {item.kind === 'video' && <span className="tile__video-mark">{seconds(item.durationMs) || '▶'}</span>}
    </button>
  )
}

export function Album({ manifest, onOpen }: { manifest: Manifest; onOpen: (index: number) => void }) {
  const [ref, width] = useMeasure()
  const phone = width > 0 && width <= 640
  const target = Number.parseInt(readToken(phone ? '--layout-row-height-sm' : '--layout-row-height', '340'), 10)
  const gutter = Number.parseInt(readToken(phone ? '--space-gutter-tight' : '--space-gutter', '8'), 10)
  const rows = justify(manifest.items, width, target, gutter)

  // An index into the flat list, so the lightbox walks the album rather than the row.
  let index = -1

  return (
    <div className="album" ref={ref}>
      {rows.map((row, rowIndex) => (
        <div className="row" key={rowIndex}>
          {row.items.map((item) => {
            index += 1
            const at = index
            return <Tile key={item.id} item={item} height={row.height} onOpen={() => onOpen(at)} />
          })}
        </div>
      ))}
    </div>
  )
}
