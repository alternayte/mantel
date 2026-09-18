/** An album with nothing in it, and an album that is gone. Both are real states a recipient opens. */
export function Empty() {
  return (
    <div className="notice">
      <strong>Nothing here yet</strong>
      <p>The photographs have not been added.</p>
    </div>
  )
}

export function Gone() {
  return (
    <div className="notice">
      <strong>This link does not work</strong>
      <p>It may have been revoked, or it may never have existed.</p>
    </div>
  )
}

export function Broken() {
  return (
    <div className="notice">
      <strong>The album could not be loaded</strong>
      <p>Check the connection and try again.</p>
    </div>
  )
}

/** Shown under the album while items are still being rendered. It is a product surface, not a spinner. */
export function StillProcessing({ ready, total }: { ready: number; total: number }) {
  return (
    <p className="notice" style={{ minHeight: 'auto', paddingTop: '2rem' }}>
      {ready} of {total} ready. The rest are still being prepared.
    </p>
  )
}
