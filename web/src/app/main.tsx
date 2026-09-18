import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ApiError, api } from './api'
import { Album } from './routes/Album'
import { Albums } from './routes/Albums'
import { Library } from './routes/Library'
import { Settings } from './routes/Settings'
import { SignIn } from './routes/SignIn'
import './app.css'

/**
 * Three screens and the path in the address bar. TanStack Router earns its keep on a route tree;
 * this is a list, an album and a settings page, and a router here would be a dependency standing in
 * for an if (SDD.md 13: no abstraction without a second concrete use).
 */
type Screen = { name: 'albums' } | { name: 'album'; id: string } | { name: 'library' } | { name: 'settings' }

function screenFromPath(pathname: string): Screen {
  const album = pathname.match(/^\/app\/albums\/([^/]+)/)
  if (album) return { name: 'album', id: album[1] }
  if (pathname.startsWith('/app/library')) return { name: 'library' }
  if (pathname.startsWith('/app/settings')) return { name: 'settings' }
  return { name: 'albums' }
}

function pathFor(screen: Screen): string {
  if (screen.name === 'album') return `/app/albums/${screen.id}`
  if (screen.name === 'library') return '/app/library'
  if (screen.name === 'settings') return '/app/settings'
  return '/app'
}

function Creator() {
  const [screen, setScreen] = useState<Screen>(() => screenFromPath(window.location.pathname))
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, retry: false })

  useEffect(() => {
    const onPop = () => setScreen(screenFromPath(window.location.pathname))
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const go = (next: Screen) => {
    window.history.pushState(null, '', pathFor(next))
    setScreen(next)
  }

  if (me.isLoading) return null
  if (me.error instanceof ApiError && me.error.status === 401) return <SignIn />
  if (me.error) return <p className="p-6 text-sm text-fail">The account could not be loaded.</p>

  if (screen.name === 'album') {
    return (
      <Album
        id={screen.id}
        onBack={() => go({ name: 'albums' })}
        onLibrary={() => go({ name: 'library' })}
      />
    )
  }
  if (screen.name === 'library') {
    return <Library onOpenAlbum={(id) => go({ name: 'album', id })} onBack={() => go({ name: 'albums' })} />
  }
  if (screen.name === 'settings') {
    return (
      <Settings
        onBack={() => go({ name: 'albums' })}
        onSignedOut={() => {
          window.location.href = '/app'
        }}
      />
    )
  }
  return (
    <Albums
      onOpen={(id) => go({ name: 'album', id })}
      onLibrary={() => go({ name: 'library' })}
      onSettings={() => go({ name: 'settings' })}
    />
  )
}

const client = new QueryClient({ defaultOptions: { queries: { staleTime: 1000, refetchOnWindowFocus: false } } })

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={client}>
      <Creator />
    </QueryClientProvider>
  </StrictMode>,
)
