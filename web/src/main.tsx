import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

// Routing arrives with the viewer (M6) and the creator (M7). Until then this is the mount point
// that proves the build pipeline and the /api proxy.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <main>
      <h1>Mantel</h1>
    </main>
  </StrictMode>,
)
