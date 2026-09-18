/** The shape the API sends. Mirrors Manifest in features/viewer/Manifest.kt. */
export type ItemStatus = 'pending_upload' | 'uploaded' | 'processing' | 'backed_up' | 'shareable' | 'failed'

export type Item = {
  id: string
  kind: 'photo' | 'video'
  status: ItemStatus
  caption?: string | null
  width?: number | null
  height?: number | null
  durationMs?: number | null
  thumbUrl?: string | null
  displayWebpUrl?: string | null
  displayAvifUrl?: string | null
  posterUrl?: string | null
  mp4Url?: string | null
}

export type Manifest = {
  title: string
  description?: string | null
  status: string
  itemCount: number
  readyCount: number
  items: Item[]
}

export type Outcome =
  | { state: 'album'; manifest: Manifest }
  | { state: 'pin' }
  | { state: 'gone' }
  | { state: 'error' }

/** The token is the last segment of /a/{token}. It never goes anywhere but this fetch. */
export function tokenFromLocation(pathname = window.location.pathname): string {
  return decodeURIComponent(pathname.replace(/^\/a\//, '').replace(/\/$/, ''))
}

export async function loadManifest(token: string): Promise<Outcome> {
  let response: Response
  try {
    response = await fetch(`/api/share/${encodeURIComponent(token)}`, { credentials: 'same-origin' })
  } catch {
    return { state: 'error' }
  }
  if (response.status === 404) return { state: 'gone' }
  if (response.status === 401) return { state: 'pin' }
  if (!response.ok) return { state: 'error' }
  return { state: 'album', manifest: (await response.json()) as Manifest }
}

export async function unlock(token: string, pin: string): Promise<'ok' | 'wrong' | 'too-many' | 'error'> {
  let response: Response
  try {
    response = await fetch(`/api/share/${encodeURIComponent(token)}/unlock`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ pin }),
      credentials: 'same-origin',
    })
  } catch {
    return 'error'
  }
  if (response.ok) return 'ok'
  if (response.status === 429) return 'too-many'
  if (response.status === 422) return 'wrong'
  return 'error'
}

/** An item with pixels behind it. Anything else is a placeholder in the same position. */
export function isReady(item: Item): boolean {
  return item.status === 'shareable' && Boolean(item.thumbUrl)
}

export function ratioOf(item: Item): number {
  const width = item.width ?? 3
  const height = item.height ?? 2
  return height > 0 ? width / height : 1.5
}
