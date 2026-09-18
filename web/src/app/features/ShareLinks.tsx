import { useState } from 'react'
import type { ShareLink } from '../api'
import { Button, Field, Input } from '../components/ui'

/**
 * Publishing is not a separate act: creating the first live link publishes the album, and revoking
 * the last one returns it to ready (SDD.md 4.2). The control says so rather than showing a
 * "publish" button that does something else.
 */
export function ShareLinks({
  links,
  onCreate,
  onRevoke,
}: {
  links: ShareLink[]
  onCreate: (options: { pin?: string; expiresInDays?: number }) => Promise<void>
  onRevoke: (id: string) => void
}) {
  const [pin, setPin] = useState('')
  const [expiry, setExpiry] = useState('')
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState<string | null>(null)

  const live = links.filter((link) => link.live)

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3">
        <Field label="PIN (optional)">
          <Input
            value={pin}
            inputMode="numeric"
            placeholder="4 to 12 digits"
            onChange={(event) => setPin(event.target.value.replace(/\D/g, ''))}
            className="w-40"
          />
        </Field>
        <Field label="Expires">
          <select
            value={expiry}
            onChange={(event) => setExpiry(event.target.value)}
            className="h-9 rounded-[10px] border border-line bg-[var(--work-lift)] px-2 text-sm text-ink"
          >
            <option value="">Never</option>
            <option value="7">7 days</option>
            <option value="30">30 days</option>
            <option value="90">90 days</option>
          </select>
        </Field>
        <Button
          variant="primary"
          disabled={busy}
          onClick={async () => {
            setBusy(true)
            try {
              await onCreate({
                pin: pin || undefined,
                expiresInDays: expiry ? Number(expiry) : undefined,
              })
              setPin('')
              setExpiry('')
            } finally {
              setBusy(false)
            }
          }}
        >
          {live.length === 0 ? 'Publish and get a link' : 'Create another link'}
        </Button>
      </div>

      {links.length === 0 ? (
        <p className="text-sm text-muted">Not shared yet. A link publishes the album.</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {links.map((link) => (
            <li
              key={link.id}
              className="flex flex-wrap items-center gap-2 rounded-[10px] border border-line bg-[var(--work-lift)] p-2.5"
            >
              <code className={`text-xs ${link.live ? 'text-ink' : 'text-muted line-through'}`}>{link.url}</code>
              {link.hasPin && <span className="rounded-full bg-[#232327] px-2 py-0.5 text-[10px] text-muted">PIN</span>}
              {link.expiresAt && (
                <span className="text-[10px] text-muted">
                  until {new Date(link.expiresAt).toLocaleDateString()}
                </span>
              )}
              {!link.live && <span className="text-[10px] text-muted">revoked</span>}
              <span className="grow" />
              {link.live && (
                <>
                  <Button
                    size="sm"
                    onClick={() => {
                      navigator.clipboard.writeText(link.url)
                      setCopied(link.id)
                      setTimeout(() => setCopied(null), 1500)
                    }}
                  >
                    {copied === link.id ? 'Copied' : 'Copy'}
                  </Button>
                  <Button size="sm" variant="danger" onClick={() => onRevoke(link.id)}>
                    Revoke
                  </Button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
