import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { api, type Item } from '../api'
import { Button } from '../components/ui'

/**
 * Every photograph the account owns, newest taken first.
 *
 * It is a grid and a selection and nothing else. Search is what people stay on a photo library for
 * and it is deliberately not here: the library exists so a backup is visible and so an album can be
 * assembled from more than what was uploaded in this session. The timeline, the viewer and the trash
 * belong to the phone; the web deletes to the trash and offers the undo, and nothing more.
 */
export function Library({ onOpenAlbum, onBack }: { onOpenAlbum: (id: string) => void; onBack: () => void }) {
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [target, setTarget] = useState('')
  // What the last delete sent to the trash. The web has no trash screen, so this is its way back.
  const [trashed, setTrashed] = useState<string[]>([])

  const library = useInfiniteQuery({
    queryKey: ['library'],
    queryFn: ({ pageParam }) => api.library(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.next ?? undefined,
    // Backed up is finished as far as the library is concerned: it has a thumbnail to show.
    refetchInterval: (query) =>
      query.state.data?.pages.some((page) => page.items.some((item) => !shown(item.status))) ? 2000 : false,
  })
  const albums = useQuery({ queryKey: ['albums'], queryFn: api.albums })

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['library'] })
    void queryClient.invalidateQueries({ queryKey: ['me'] })
    // A deletion or a restore changes every album that holds the photograph.
    void queryClient.invalidateQueries({ queryKey: ['albums'] })
    void queryClient.invalidateQueries({ queryKey: ['album'] })
  }

  const addToAlbum = useMutation({
    mutationFn: (albumId: string) => api.addToAlbum(albumId, [...selected]),
    onSuccess: (_result, albumId) => {
      setSelected(new Set())
      refresh()
      onOpenAlbum(albumId)
    },
  })

  const remove = useMutation({
    mutationFn: async (ids: string[]) => {
      for (const id of ids) await api.moveToTrash(id)
      return ids
    },
    onSuccess: (ids) => {
      setSelected(new Set())
      setTrashed(ids)
      refresh()
    },
  })

  const undo = useMutation({
    mutationFn: async (ids: string[]) => {
      for (const id of ids) await api.restoreFromTrash(id)
    },
    onSuccess: () => {
      setTrashed([])
      refresh()
    },
  })

  const toggle = (id: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const pages = library.data?.pages ?? []
  const items = pages.flatMap((page) => page.items)
  const total = pages[0]?.totalItems ?? 0

  // The next page loads as the end of the grid comes into view.
  const end = useRef<HTMLDivElement>(null)
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = library
  useEffect(() => {
    const node = end.current
    if (!node || !hasNextPage) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) void fetchNextPage()
      },
      { rootMargin: '600px' },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-4 p-6">
      <header className="flex flex-wrap items-center gap-3">
        <Button size="sm" onClick={onBack}>
          ← Albums
        </Button>
        <h1 className="m-0 text-sm uppercase tracking-[0.2em] text-muted">Library</h1>
        <span className="text-xs text-muted">
          {total} item{total === 1 ? '' : 's'}
        </span>
      </header>

      {selected.size > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-card border border-line bg-surface-lift p-3">
          <span className="text-xs text-muted">{selected.size} selected</span>
          <select
            value={target}
            onChange={(event) => setTarget(event.target.value)}
            className="h-8 rounded-[6px] border border-line bg-transparent px-2 text-xs text-ink"
          >
            <option value="">Add to album…</option>
            {(albums.data ?? []).map((album) => (
              <option key={album.id} value={album.id}>
                {album.title}
              </option>
            ))}
          </select>
          <Button size="sm" disabled={!target || addToAlbum.isPending} onClick={() => addToAlbum.mutate(target)}>
            Add
          </Button>
          <Button size="sm" variant="danger" disabled={remove.isPending} onClick={() => remove.mutate([...selected])}>
            Delete
          </Button>
          <Button size="sm" variant="quiet" onClick={() => setSelected(new Set())}>
            Clear
          </Button>
        </div>
      )}

      {trashed.length > 0 && (
        <div
          role="status"
          className="flex flex-wrap items-center gap-2 rounded-card border border-line bg-surface-lift p-3"
        >
          <span className="text-xs text-muted">
            {trashed.length === 1 ? 'Moved to the trash' : `${trashed.length} moved to the trash`}. It is kept for 30
            days.
          </span>
          <span className="flex gap-2">
            <Button size="sm" disabled={undo.isPending} onClick={() => undo.mutate(trashed)}>
              Undo
            </Button>
            <Button size="sm" variant="quiet" aria-label="Dismiss" onClick={() => setTrashed([])}>
              ×
            </Button>
          </span>
        </div>
      )}

      {addToAlbum.error && <p className="text-xs text-fail">{(addToAlbum.error as Error).message}</p>}
      {remove.error && <p className="text-xs text-fail">{(remove.error as Error).message}</p>}
      {undo.error && <p className="text-xs text-fail">{(undo.error as Error).message}</p>}

      {items.length === 0 && !library.isLoading && (
        <p className="text-xs text-muted">
          Nothing here yet. Everything uploaded, from this browser or from the phone, arrives in the library.
        </p>
      )}

      <ul className="grid grid-cols-[repeat(auto-fill,minmax(104px,1fr))] gap-[3px]">
        {items.map((item) => (
          <LibraryTile key={item.id} item={item} chosen={selected.has(item.id)} onToggle={() => toggle(item.id)} />
        ))}
      </ul>

      <div ref={end}>
        {hasNextPage && (
          <Button size="sm" variant="quiet" disabled={isFetchingNextPage} onClick={() => void fetchNextPage()}>
            {isFetchingNextPage ? 'Loading…' : 'More'}
          </Button>
        )}
      </div>
    </main>
  )
}

/** A library item nothing is waiting on: backed up or shareable has a picture, and failed says so. */
const shown = (status: string) => status === 'backed_up' || status === 'shareable' || status === 'failed'

const takenFormat = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })

function LibraryTile({ item, chosen, onToggle }: { item: Item; chosen: boolean; onToggle: () => void }) {
  const taken = item.takenAt ? takenFormat.format(new Date(item.takenAt)) : null
  return (
    <li>
      <button
        type="button"
        onClick={onToggle}
        aria-pressed={chosen}
        title={taken ? `${item.filename ?? 'Photograph'}, taken ${taken}` : (item.filename ?? undefined)}
        className={`relative flex aspect-square w-full items-center justify-center overflow-hidden bg-surface-lift text-xs ${
          chosen ? 'outline outline-2 outline-ink' : ''
        }`}
      >
        {item.thumbUrl ? (
          <img src={item.thumbUrl} alt="" className="h-full w-full object-cover" loading="lazy" draggable={false} />
        ) : item.kind === 'file' ? (
          // Kept, not rendered. The filename is all there is to show.
          <span className="px-2 text-center text-muted">{item.filename ?? 'file'}</span>
        ) : item.status === 'failed' ? (
          <span className="px-2 text-center text-fail">could not be processed</span>
        ) : (
          <span className="animate-pulse text-muted">{item.status.replace('_', ' ')}</span>
        )}
        {taken && (
          <span className="pointer-events-none absolute bottom-1 left-1 rounded-[4px] bg-black/60 px-1 py-px text-[10px] leading-tight tabular-nums text-ink">
            {taken}
          </span>
        )}
      </button>
    </li>
  )
}
