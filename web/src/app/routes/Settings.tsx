import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { Button, Card, Dialog, Input, Meter } from '../components/ui'
import { ApiTokens } from '../features/ApiTokens'

/** Export and deletion are product promises, so they are on the page rather than in a support email. */
export function Settings({ onBack, onSignedOut }: { onBack: () => void; onSignedOut: () => void }) {
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const [confirming, setConfirming] = useState(false)
  const [typed, setTyped] = useState('')

  return (
    <main className="mx-auto flex max-w-2xl flex-col gap-5 p-6">
      <header className="flex items-center gap-3">
        <Button size="sm" onClick={onBack}>
          ← Albums
        </Button>
        <h1 className="m-0 text-sm font-medium tracking-[0.2em] text-muted uppercase">Settings</h1>
      </header>

      <Card className="flex flex-col gap-3">
        <p className="m-0 text-sm text-ink">{me.data?.email}</p>
        {me.data && <Meter used={me.data.storageUsedBytes} total={me.data.storageQuotaBytes} />}
        <div className="flex gap-2">
          <a
            href="/api/account/export"
            className="inline-flex h-9 items-center rounded-[10px] border border-line bg-[var(--work-lift)] px-3.5 text-sm text-ink no-underline hover:bg-[#232327]"
          >
            Export everything
          </a>
          <Button
            onClick={async () => {
              await api.logout()
              onSignedOut()
            }}
          >
            Sign out
          </Button>
        </div>
      </Card>

      <ApiTokens />

      <Card className="flex flex-col gap-2">
        <h2 className="m-0 text-sm font-medium text-ink">Delete this account</h2>
        <p className="m-0 text-xs text-muted">
          Every album, every photograph and every share link goes, along with the files in storage. It cannot be
          undone.
        </p>
        <div>
          <Button variant="danger" onClick={() => setConfirming(true)}>
            Delete account
          </Button>
        </div>
      </Card>

      <Dialog open={confirming} onClose={() => setConfirming(false)} title="Delete this account">
        <p className="mt-0 text-sm text-muted">
          Type <strong className="text-ink">delete</strong> to confirm.
        </p>
        <Input value={typed} onChange={(event) => setTyped(event.target.value)} className="w-full" aria-label="Confirm" />
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={() => setConfirming(false)}>Cancel</Button>
          <Button
            variant="danger"
            disabled={typed !== 'delete'}
            onClick={async () => {
              await api.deleteAccount()
              onSignedOut()
            }}
          >
            Delete everything
          </Button>
        </div>
      </Dialog>
    </main>
  )
}
