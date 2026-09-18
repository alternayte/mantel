import { useEffect, useRef, useState } from 'react'
import { type Item, isReady } from './manifest'

/**
 * One item, full bleed. Controls fade in on pointer movement or focus and fade out again, so a
 * still screen has nothing on it but the photograph (DESIGN.md).
 */
export function Lightbox({
  items,
  index,
  onClose,
  onMove,
}: {
  items: Item[]
  index: number
  onClose: () => void
  onMove: (next: number) => void
}) {
  const [intent, setIntent] = useState(true)
  const idle = useRef<number | undefined>(undefined)
  const item = items[index]

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
      if (event.key === 'ArrowRight') onMove(index + 1)
      if (event.key === 'ArrowLeft') onMove(index - 1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [index, onClose, onMove])

  // A photograph is not a page: the album behind must not scroll while one is open.
  useEffect(() => {
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [])

  const wake = () => {
    setIntent(true)
    window.clearTimeout(idle.current)
    idle.current = window.setTimeout(() => setIntent(false), 2200)
  }

  useEffect(() => {
    wake()
    return () => window.clearTimeout(idle.current)
  }, [index])

  const touch = useRef<number | null>(null)

  if (!item || !isReady(item)) return null

  return (
    <div
      className={`lightbox${intent ? ' lightbox--intent' : ''}`}
      onMouseMove={wake}
      onTouchStart={(event) => {
        touch.current = event.touches[0].clientX
        wake()
      }}
      onTouchEnd={(event) => {
        if (touch.current === null) return
        const travelled = event.changedTouches[0].clientX - touch.current
        if (Math.abs(travelled) > 60) onMove(index + (travelled < 0 ? 1 : -1))
        touch.current = null
      }}
    >
      {item.kind === 'video' ? (
        <video src={item.mp4Url ?? undefined} poster={item.posterUrl ?? undefined} controls autoPlay playsInline />
      ) : (
        <picture>
          {item.displayAvifUrl && <source srcSet={item.displayAvifUrl} type="image/avif" />}
          <img src={item.displayWebpUrl ?? item.thumbUrl!} alt={item.caption ?? ''} />
        </picture>
      )}

      {item.caption && <p className="lightbox__caption">{item.caption}</p>}

      <button type="button" className="control control--close" onClick={onClose} aria-label="Close">
        ✕
      </button>
      {index > 0 && (
        <button type="button" className="control control--prev" onClick={() => onMove(index - 1)} aria-label="Previous">
          ‹
        </button>
      )}
      {index < items.length - 1 && (
        <button type="button" className="control control--next" onClick={() => onMove(index + 1)} aria-label="Next">
          ›
        </button>
      )}
    </div>
  )
}
