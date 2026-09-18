import { api, putToStorage, type PresignedUpload } from '../api'

export type UploadState = {
  key: string
  filename: string
  sizeBytes: number
  fraction: number
  itemId?: string
  error?: string
}

/**
 * The largest file this hashes before uploading. A hash needs the whole file in memory, and dedupe
 * is worth one read of a photograph and not worth one of a two gigabyte video. A file above this
 * uploads without one, which costs nothing except a second copy if it is ever offered twice.
 */
const HASHABLE_BYTES = 256 * 1024 * 1024

/**
 * One batch, whatever its size: quota is checked once, forty photographs are one intent and one
 * completion call, and the bytes go straight to storage (SDD.md 6.3).
 *
 * Media lands in the library. An album, when there is one, is a selection made afterwards.
 *
 * Progress is per file rather than one bar, because a creator has to be able to tell "that one
 * failed" from "that one is slow" (site/references/creator.md).
 */
export async function uploadBatch(
  albumId: string | null,
  files: File[],
  onChange: (states: UploadState[]) => void,
): Promise<{ uploaded: number; failed: UploadState[] }> {
  const states: UploadState[] = files.map((file, index) => ({
    key: `${index}-${file.name}`,
    filename: file.name,
    sizeBytes: file.size,
    fraction: 0,
  }))
  const publish = () => onChange([...states])
  publish()

  const declared = await Promise.all(
    files.map(async (file) => ({
      filename: file.name,
      contentType: file.type || 'image/jpeg',
      sizeBytes: file.size,
      contentHash: await hashOf(file),
    })),
  )
  const intent = await api.uploadIntent(declared)

  const arrived: string[] = []
  for (const [index, file] of files.entries()) {
    const target = intent.items[index]
    states[index].itemId = target.itemId
    // The library already holds these bytes. Nothing to send, and nothing was reserved.
    if (target.alreadyHeld) {
      states[index].fraction = 1
      arrived.push(target.itemId)
      publish()
      continue
    }
    try {
      await send(file, target, (fraction) => {
        states[index].fraction = fraction
        publish()
      })
      states[index].fraction = 1
      arrived.push(target.itemId)
    } catch (failure) {
      states[index].error = failure instanceof Error ? failure.message : 'the upload failed'
    }
    publish()
  }

  const fresh = arrived.filter((itemId) => !intent.items.find((item) => item.itemId === itemId)?.alreadyHeld)
  if (fresh.length > 0) await api.completeUploads(fresh)
  if (albumId && arrived.length > 0) await api.addToAlbum(albumId, arrived)
  return { uploaded: arrived.length, failed: states.filter((state) => state.error) }
}

/** The SHA-256 the API dedupes on, as lower-case hex. Undefined when the file is too big to read. */
async function hashOf(file: File): Promise<string | undefined> {
  if (file.size > HASHABLE_BYTES || !globalThis.crypto?.subtle) return undefined
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/** A small file is one PUT. A large one is parts, and a part that fails is retried alone. */
async function send(
  file: File,
  target: PresignedUpload,
  onProgress: (fraction: number) => void,
): Promise<void> {
  if (target.uploadUrl) {
    return putToStorage(target.uploadUrl, file, target.contentType, onProgress)
  }

  const parts = target.parts ?? []
  let done = 0
  for (const part of parts) {
    const from = (part.partNumber - 1) * (parts[0]?.sizeBytes ?? part.sizeBytes)
    const slice = file.slice(from, from + part.sizeBytes)
    await withRetry(() =>
      putToStorage(part.uploadUrl, slice, target.contentType, (fraction) =>
        onProgress((done + fraction * part.sizeBytes) / file.size),
      ),
    )
    done += part.sizeBytes
    onProgress(done / file.size)
  }
}

/** Three goes at one part, because one dropped part should not cost a two gigabyte upload. */
async function withRetry(attempt: () => Promise<void>): Promise<void> {
  let lastFailure: unknown
  for (let tries = 0; tries < 3; tries += 1) {
    try {
      return await attempt()
    } catch (failure) {
      lastFailure = failure
      await new Promise((resolve) => setTimeout(resolve, 400 * 2 ** tries))
    }
  }
  throw lastFailure
}
