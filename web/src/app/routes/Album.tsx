import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { api } from '../api'
import { Button, gigabytes } from '../components/ui'
import { ItemGrid } from '../features/ItemGrid'
import { ShareLinks } from '../features/ShareLinks'
import { uploadBatch, type UploadState } from '../features/upload'

/**
 * One workspace: drop, watch, publish. Run B counted this at five interactions from picker to link,
 * against eight for a wizard, because a wizard charges for its own structure at exactly the moment
 * a creator is waiting on uploads (site/references/run-b.md).
 */
export function Album({ id, onBack }: { id: string; onBack: () => void }) {
  const client = useQueryClient()
  const [uploads, setUploads] = useState<UploadState[]>([])
  const [dragging, setDragging] = useState(false)
  const picker = useRef<HTMLInputElement>(null)

  const album = useQuery({
    queryKey: ['album', id],
    queryFn: () => api.album(id),
    // While anything is unfinished the album answers for itself, rather than the creator refreshing.
    refetchInterval: (query) =>
      query.state.data?.items.some((item) => item.status !== 'ready' && item.status !== 'failed') ? 2000 : false,
  })
  const links = useQuery({ queryKey: ['links', id], queryFn: () => api.shareLinks(id) })

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['album', id] })
    client.invalidateQueries({ queryKey: ['me'] })
  }

  const send = async (files: File[]) => {
    if (files.length === 0) return
    try {
      await uploadBatch(id, files, setUploads)
    } finally {
      refresh()
      setTimeout(() => setUploads([]), 1200)
    }
  }

  const reorder = useMutation({
    mutationFn: (ids: string[]) => api.reorder(id, ids),
    onMutate: async (ids) => {
      // The drag already happened on screen; the request only confirms it.
      await client.cancelQueries({ queryKey: ['album', id] })
      const previous = client.getQueryData(['album', id])
      client.setQueryData(['album', id], (old: typeof album.data) =>
        old
          ? { ...old, items: ids.map((itemId, index) => ({ ...old.items.find((i) => i.id === itemId)!, position: index })) }
          : old,
      )
      return { previous }
    },
    onError: (_error, _ids, context) => client.setQueryData(['album', id], context?.previous),
    onSettled: refresh,
  })

  const data = album.data
  const busy = uploads.length > 0
  const unfinished = data?.items.filter((item) => item.status !== 'ready' && item.status !== 'failed').length ?? 0
  const failed = data?.items.filter((item) => item.status === 'failed').length ?? 0

  return (
    <main
      className="mx-auto flex max-w-6xl flex-col gap-5 p-6"
      onDragOver={(event) => {
        event.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        event.preventDefault()
        setDragging(false)
        send([...event.dataTransfer.files])
      }}
    >
      <header className="flex flex-wrap items-center gap-3">
        <Button size="sm" onClick={onBack}>
          ← Albums
        </Button>
        <h1 className="m-0 text-base font-medium text-ink">{data?.title ?? ' '}</h1>
        <span className="text-xs text-muted">
          {data ? `${data.itemCount} items · ${gigabytes(data.totalBytes)}` : ''}
          {unfinished > 0 && ` · ${unfinished} still processing`}
          {failed > 0 && ` · ${failed} failed`}
        </span>
        <span className="grow" />
        <input
          ref={picker}
          type="file"
          multiple
          accept="image/jpeg,image/png,image/webp,image/heic,image/heif,video/mp4,video/quicktime"
          className="hidden"
          onChange={(event) => {
            send([...(event.target.files ?? [])])
            event.target.value = ''
          }}
        />
        <Button variant="primary" onClick={() => picker.current?.click()} disabled={busy}>
          Add photos
        </Button>
      </header>

      {dragging && (
        <div className="rounded-[10px] border border-dashed border-muted p-8 text-center text-sm text-muted">
          Drop to add them
        </div>
      )}

      {busy && <UploadProgress uploads={uploads} />}

      {data && data.items.length === 0 && !busy && (
        <div
          className="rounded-[10px] border border-dashed border-line p-10 text-center text-sm text-muted"
          onClick={() => picker.current?.click()}
          role="presentation"
        >
          Drop photographs here, or use Add photos.
        </div>
      )}

      {data && data.items.length > 0 && (
        <ItemGrid
          items={data.items}
          coverItemId={data.coverItemId}
          onReorder={(ids) => reorder.mutate(ids)}
          onCaption={(itemId, caption) => api.setCaption(id, itemId, caption).then(refresh)}
          onCover={(itemId) => api.updateAlbum(id, { coverItemId: itemId }).then(refresh)}
          onDelete={(itemId) => api.deleteItem(id, itemId).then(refresh)}
          onRetry={(itemId) => api.retryItem(id, itemId).then(refresh)}
        />
      )}

      <section className="flex flex-col gap-3 border-t border-line pt-5">
        <h2 className="m-0 text-xs font-medium uppercase tracking-[0.18em] text-muted">Share</h2>
        <ShareLinks
          links={links.data ?? []}
          onCreate={async (options) => {
            await api.createShareLink(id, options)
            client.invalidateQueries({ queryKey: ['links', id] })
            refresh()
          }}
          onRevoke={(linkId) =>
            api.revokeShareLink(linkId).then(() => {
              client.invalidateQueries({ queryKey: ['links', id] })
              refresh()
            })
          }
        />
      </section>
    </main>
  )
}

/** Per file, not one bar: a creator has to see which one is slow and which one is dead. */
function UploadProgress({ uploads }: { uploads: UploadState[] }) {
  const done = uploads.filter((upload) => upload.fraction >= 1 && !upload.error).length
  return (
    <section className="flex flex-col gap-2 rounded-[10px] border border-line bg-[var(--work-lift)] p-3">
      <p className="m-0 text-xs text-muted">
        Uploading {done} of {uploads.length}
      </p>
      <ul className="m-0 grid max-h-52 list-none grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-1.5 overflow-auto p-0">
        {uploads.map((upload) => (
          <li key={upload.key} className="flex items-center gap-2 text-xs">
            <span className="w-32 truncate text-muted" title={upload.filename}>
              {upload.filename}
            </span>
            <span className="h-1 grow overflow-hidden rounded-full bg-[#0f0f11]">
              <span
                className={`block h-full ${upload.error ? 'bg-fail' : 'bg-muted'}`}
                style={{ width: `${Math.round((upload.error ? 1 : upload.fraction) * 100)}%` }}
              />
            </span>
            <span className={`w-16 text-right tabular-nums ${upload.error ? 'text-fail' : 'text-muted'}`}>
              {upload.error ? 'failed' : `${Math.round(upload.fraction * 100)}%`}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
