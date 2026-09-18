import { useState } from 'react'

type Phase = 'idle' | 'building' | 'failed'

/**
 * The album, as a folder that works without us. One quiet control at the end of the photographs,
 * because DESIGN.md keeps the grid free of interface and this is the only thing a recipient might
 * want that the photographs cannot say themselves.
 *
 * "Include originals" carries its warning next to it rather than behind a link: an original
 * photograph often names the place it was taken, and a recipient about to forward the folder should
 * be told before they do, not after (SDD.md 4.5).
 */
export function Download({ token }: { token: string }) {
  const [originals, setOriginals] = useState(false)
  const [phase, setPhase] = useState<Phase>('idle')

  const start = async () => {
    setPhase('building')
    const url = `/api/share/${encodeURIComponent(token)}/download${originals ? '?originals=true' : ''}`
    try {
      // A redirect means it is packed; 202 means the worker is still packing it.
      const response = await fetch(url, { credentials: 'same-origin', redirect: 'follow' })
      if (response.redirected || response.ok) {
        if (response.headers.get('content-type')?.includes('application/json')) {
          setTimeout(start, 2500)
          return
        }
        window.location.href = response.url
        setPhase('idle')
        return
      }
      if (response.status === 202) {
        setTimeout(start, 2500)
        return
      }
      setPhase('failed')
    } catch {
      setPhase('failed')
    }
  }

  return (
    <section className="download">
      <button type="button" className="download__button" onClick={start} disabled={phase === 'building'}>
        {phase === 'building' ? 'Packing the album…' : 'Download the album'}
      </button>

      <label className="download__option">
        <input type="checkbox" checked={originals} onChange={(event) => setOriginals(event.target.checked)} />
        Include the original files
      </label>

      {originals && (
        <p className="download__warning">
          Originals carry what the camera recorded, which usually includes where the photograph was taken and the
          device that took it. The standard download has that removed.
        </p>
      )}

      {phase === 'failed' && <p className="download__warning">The album could not be packed. Try again shortly.</p>}
    </section>
  )
}
