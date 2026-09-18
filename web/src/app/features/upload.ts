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
 * One batch, whatever its size: quota is checked once, forty photographs are one intent and one
 * completion call, and the bytes go straight to storage (SDD.md 6.3).
 *
 * Progress is per file rather than one bar, because a creator has to be able to tell "that one
 * failed" from "that one is slow" (site/references/creator.md).
 */
export async function uploadBatch(
  albumId: string,
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

  const intent = await api.uploadIntent(
    albumId,
    files.map((file) => ({ filename: file.name, contentType: file.type || 'image/jpeg', sizeBytes: file.size })),
  )

  const arrived: string[] = []
  for (const [index, file] of files.entries()) {
    const target = intent.items[index]
    states[index].itemId = target.itemId
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

  if (arrived.length > 0) await api.completeUploads(albumId, arrived)
  return { uploaded: arrived.length, failed: states.filter((state) => state.error) }
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
