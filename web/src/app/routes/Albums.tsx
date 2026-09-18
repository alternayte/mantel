import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type AlbumSummary } from '../api'
import { Button, Input, Meter, gigabytes } from '../components/ui'

export function Albums({
  onOpen,
  onLibrary,
  onSettings,
}: {
  onOpen: (id: string) => void
  onLibrary: () => void
  onSettings: () => void
}) {
  const client = useQueryClient()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const albums = useQuery({ queryKey: ['albums'], queryFn: api.albums })
  const [title, setTitle] = useState('')

  const create = useMutation({
    mutationFn: (name: string) => api.createAlbum(name),
    onSuccess: (album) => {
      client.invalidateQueries({ queryKey: ['albums'] })
      setTitle('')
      onOpen(album.id)
    },
  })

  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="m-0 text-sm font-medium tracking-[0.2em] text-muted uppercase">Albums</h1>
        <div className="flex items-center gap-4">
          {me.data && <Meter used={me.data.storageUsedBytes} total={me.data.storageQuotaBytes} />}
          <Button size="sm" onClick={onLibrary}>
            Library
          </Button>
          <Button size="sm" onClick={onSettings}>
            Settings
          </Button>
        </div>
      </header>

      <form
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          if (title.trim()) create.mutate(title.trim())
        }}
      >
        <Input
          value={title}
          placeholder="New album"
          aria-label="New album title"
          onChange={(event) => setTitle(event.target.value)}
          className="w-64"
        />
        <Button type="submit" variant="primary" disabled={!title.trim() || create.isPending}>
          Create
        </Button>
      </form>

      {albums.data?.length === 0 && <p className="text-sm text-muted">No albums yet.</p>}

      <ul className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(230px,1fr))] gap-3 p-0">
        {albums.data?.map((album) => (
          <li key={album.id}>
            <button
              type="button"
              onClick={() => onOpen(album.id)}
              className="flex w-full flex-col gap-1 rounded-[10px] border border-line bg-[var(--work-lift)] p-4 text-left hover:bg-[#232327]"
            >
              <span className="text-sm text-ink">{album.title}</span>
              <span className="text-xs text-muted">
                {album.itemCount} {album.itemCount === 1 ? 'item' : 'items'} · {gigabytes(album.totalBytes)}
              </span>
              <span className="text-[10px] uppercase tracking-wider text-muted">{statusOf(album)}</span>
            </button>
          </li>
        ))}
      </ul>
    </main>
  )
}

function statusOf(album: AlbumSummary): string {
  return album.status === 'published' ? 'shared' : album.status
}
