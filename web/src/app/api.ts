/** The one API (SDD.md 6). Every call the creator makes lives here. */

export type Me = { email: string; displayName?: string | null; storageQuotaBytes: number; storageUsedBytes: number }

export type AlbumSummary = {
  id: string
  title: string
  description?: string | null
  status: string
  itemCount: number
  totalBytes: number
  coverItemId?: string | null
  createdAt: string
  updatedAt: string
}

/**
 * `backed_up` means the library holds the original and a thumbnail; `shareable` means every
 * derivative a viewer needs exists. They were one state called `ready` until the library existed.
 */
export type ItemStatus = 'pending_upload' | 'uploaded' | 'processing' | 'backed_up' | 'shareable' | 'failed'

/** An item nothing is waiting on: it is either shown or it failed. */
export const settled = (status: string) => status === 'shareable' || status === 'failed'

export type Item = {
  id: string
  position: number
  kind: string
  status: string
  byteSize: number
  caption?: string | null
  width?: number | null
  height?: number | null
  durationMs?: number | null
  lastError?: string | null
  filename?: string | null
  thumbUrl?: string | null
}

export type AlbumView = AlbumSummary & { items: Item[] }

export type ShareLink = {
  id: string
  url: string
  token: string
  hasPin: boolean
  expiresAt?: string | null
  revokedAt?: string | null
  createdAt: string
  live: boolean
}

export type PresignedPart = { partNumber: number; uploadUrl: string; sizeBytes: number }

export type PresignedUpload = {
  itemId: string
  filename: string
  contentType: string
  sizeBytes: number
  uploadUrl?: string | null
  uploadId?: string | null
  parts?: PresignedPart[] | null
  /** The library already holds these bytes. Nothing to send. */
  alreadyHeld?: boolean
}

export type LibraryPage = { items: Item[]; next?: string | null; totalItems: number }

export type DeclaredFile = {
  filename: string
  contentType: string
  sizeBytes: number
  contentHash?: string
}

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    credentials: 'same-origin',
    headers: init?.body ? { 'content-type': 'application/json', ...init?.headers } : init?.headers,
  })
  if (response.status === 204) return undefined as T
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) {
    const error = body?.error
    throw new ApiError(error?.code ?? 'internal', error?.message ?? 'Something went wrong', response.status)
  }
  return body as T
}

export type ApiTokenView = {
  id: string
  name: string
  scopes: string[]
  createdAt: string
  lastUsedAt?: string | null
  revokedAt?: string | null
  token?: string | null
}

export const api = {
  me: () => call<Me>('/me'),
  signInMethods: () => call<{ magicLink: boolean; github: boolean }>('/auth/methods'),
  requestMagicLink: (email: string) =>
    call<{ status: string }>('/auth/magic-link', { method: 'POST', body: JSON.stringify({ email }) }),
  logout: () => call<void>('/auth/logout', { method: 'POST' }),

  albums: () => call<AlbumSummary[]>('/albums'),
  album: (id: string) => call<AlbumView>(`/albums/${id}`),
  createAlbum: (title: string) => call<AlbumSummary>('/albums', { method: 'POST', body: JSON.stringify({ title }) }),
  updateAlbum: (id: string, patch: { title?: string; description?: string; coverItemId?: string }) =>
    call<AlbumSummary>(`/albums/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  archiveAlbum: (id: string) => call<void>(`/albums/${id}`, { method: 'DELETE' }),

  // Media lands in the library. An album is a selection from it, so an upload names no album.
  library: (after?: string | null) =>
    call<LibraryPage>(`/library${after ? `?after=${after}` : ''}`),
  uploadIntent: (files: DeclaredFile[]) =>
    call<{ items: PresignedUpload[]; expiresInSeconds: number }>('/library/upload-intent', {
      method: 'POST',
      body: JSON.stringify({ files }),
    }),
  completeUploads: (itemIds: string[]) =>
    call<{ uploaded: string[]; missing: string[] }>('/library/uploads/complete', {
      method: 'POST',
      body: JSON.stringify({ itemIds }),
    }),
  deleteFromLibrary: (itemId: string) => call<void>(`/library/${itemId}`, { method: 'DELETE' }),
  addToAlbum: (albumId: string, mediaItemIds: string[]) =>
    call<void>(`/albums/${albumId}/items`, { method: 'POST', body: JSON.stringify({ mediaItemIds }) }),
  uploadProgress: (albumId: string, itemId: string) =>
    call<{ received: { partNumber: number }[]; remaining: PresignedPart[] }>(
      `/albums/${albumId}/items/${itemId}/upload-progress`,
    ),

  reorder: (albumId: string, itemIds: string[]) =>
    call<void>(`/albums/${albumId}/items/reorder`, { method: 'PATCH', body: JSON.stringify({ itemIds }) }),
  setCaption: (albumId: string, itemId: string, caption: string) =>
    call<void>(`/albums/${albumId}/items/${itemId}`, { method: 'PATCH', body: JSON.stringify({ caption }) }),
  /** Out of the album, not out of the library: an album is a selection, and unselecting is not deleting. */
  removeFromAlbum: (albumId: string, itemId: string) =>
    call<void>(`/albums/${albumId}/items/${itemId}`, { method: 'DELETE' }),
  retryItem: (albumId: string, itemId: string) =>
    call<void>(`/albums/${albumId}/items/${itemId}/retry`, { method: 'POST' }),

  shareLinks: (albumId: string) => call<ShareLink[]>(`/albums/${albumId}/share-links`),
  createShareLink: (albumId: string, options: { pin?: string; expiresInDays?: number }) =>
    call<ShareLink>(`/albums/${albumId}/share-links`, { method: 'POST', body: JSON.stringify(options) }),
  revokeShareLink: (id: string) => call<void>(`/share-links/${id}`, { method: 'DELETE' }),

  tokens: () => call<ApiTokenView[]>('/tokens'),
  createApiToken: (name: string, scopes: string[]) =>
    call<ApiTokenView>('/tokens', { method: 'POST', body: JSON.stringify({ name, scopes }) }),
  revokeApiToken: (id: string) => call<void>(`/tokens/${id}`, { method: 'DELETE' }),

  deleteAccount: () => call<void>('/account', { method: 'DELETE' }),
}

/**
 * Bytes go from here to storage and never through the API (SDD.md 6.3). XHR rather than fetch,
 * because a creator watching forty photographs upload needs per-file progress and fetch does not
 * report it.
 */
export function putToStorage(
  url: string,
  body: Blob,
  contentType: string,
  onProgress: (fraction: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('PUT', url)
    request.setRequestHeader('content-type', contentType)
    request.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable) onProgress(event.loaded / event.total)
    })
    request.addEventListener('load', () =>
      request.status >= 200 && request.status < 300
        ? resolve()
        : reject(new Error(`storage refused the upload (${request.status})`)),
    )
    request.addEventListener('error', () => reject(new Error('the upload could not reach storage')))
    request.addEventListener('abort', () => reject(new Error('the upload was cancelled')))
    request.send(body)
  })
}
