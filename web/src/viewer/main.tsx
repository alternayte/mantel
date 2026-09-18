import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Album } from './Album'
import { Lightbox } from './Lightbox'
import { Broken, Empty, Gone, StillProcessing } from './Notices'
import { PinScreen } from './PinScreen'
import { type Outcome, isReady, loadManifest, tokenFromLocation } from './manifest'
import './viewer.css'

function Viewer() {
  const token = tokenFromLocation()
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [open, setOpen] = useState<number | null>(null)

  const load = () => {
    loadManifest(token).then(setOutcome)
  }
  useEffect(load, [token])

  if (!outcome) return null
  if (outcome.state === 'pin') return <PinScreen token={token} onUnlocked={load} />
  if (outcome.state === 'gone') return <Gone />
  if (outcome.state === 'error') return <Broken />

  const { manifest } = outcome
  const waiting = manifest.itemCount - manifest.readyCount

  document.title = manifest.title

  return (
    <>
      <h1 className="title">{manifest.title}</h1>
      {manifest.itemCount === 0 ? (
        <Empty />
      ) : (
        <>
          <Album manifest={manifest} onOpen={setOpen} />
          {waiting > 0 && <StillProcessing ready={manifest.readyCount} total={manifest.itemCount} />}
        </>
      )}
      {open !== null && (
        <Lightbox
          items={manifest.items}
          index={open}
          onClose={() => setOpen(null)}
          onMove={(next) => {
            // Walk past anything that has no pixels behind it yet.
            let target = next
            while (target >= 0 && target < manifest.items.length && !isReady(manifest.items[target])) {
              target += next > open ? 1 : -1
            }
            if (target >= 0 && target < manifest.items.length) setOpen(target)
          }}
        />
      )}
    </>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Viewer />
  </StrictMode>,
)
