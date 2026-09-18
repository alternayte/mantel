import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, settled, type Item } from '../api'
import { Button } from '../components/ui'

/**
 * Every photograph the account owns, newest first.
 *
 * It is a grid and a selection and nothing else. Search is what people stay on a photo library for
 * and it is deliberately not here: the library exists so a backup is visible and so an album can be
 * assembled from more than what was uploaded in this session.
 */
export function Library({ onOpenAlbum, onBack }: { onOpenAlbum: (id: string) => void; onBack: () => void }) {
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [target, setTarget] = useState('')

  const library = useQuery({
    queryKey: ['library'],
    queryFn: () => api.library(),
    refetchInterval: (query) => (query.state.data?.items.some((item) => !settled(item.status)) ? 2000 : false),
  })
  const albums = useQuery({ queryKey: ['albums'], queryFn: api.albums })

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['library'] })
    void queryClient.invalidateQueries({ queryKey: ['me'] })
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
    mutationFn: async () => {
      for (const id of selected) await api.deleteFromLibrary(id)
    },
    onSuccess: () => {
      setSelected(new Set())
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

  const items = library.data?.items ?? []

  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-4 p-6">
      <header className="flex flex-wrap items-center gap-3">
        <Button size="sm" onClick={onBack}>
          ← Albums
        </Button>
        <h1 className="m-0 text-sm uppercase tracking-[0.2em] text-muted">Library</h1>
        <span className="text-xs text-muted">
          {library.data?.totalItems ?? 0} item{library.data?.totalItems === 1 ? '' : 's'}
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
          <Button size="sm" variant="danger" disabled={remove.isPending} onClick={() => remove.mutate()}>
            Delete
          </Button>
          <Button size="sm" variant="quiet" onClick={() => setSelected(new Set())}>
            Clear
          </Button>
        </div>
      )}

      {addToAlbum.error && <p className="text-xs text-fail">{(addToAlbum.error as Error).message}</p>}

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
    </main>
  )
}

function LibraryTile({ item, chosen, onToggle }: { item: Item; chosen: boolean; onToggle: () => void }) {
  return (
    <li>
      <button
        type="button"
        onClick={onToggle}
        aria-pressed={chosen}
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
      </button>
    </li>
  )
}
