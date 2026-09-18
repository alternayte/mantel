import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type ApiTokenView } from '../api'
import { Button, Card, Input } from '../components/ui'

const SCOPES: { value: string; label: string; note: string }[] = [
  { value: 'albums:read', label: 'Read albums', note: 'List albums and their items. Never share links.' },
  { value: 'albums:write', label: 'Write albums', note: 'Create albums, upload, caption, reorder, delete.' },
  { value: 'share:write', label: 'Share', note: 'Create and revoke share links. This gives albums away.' },
]

/**
 * Tokens for agents (SDD.md 9). The scopes are shown with what they actually allow, because "a
 * token that can enumerate and re-share every album is a data-exfiltration API with good
 * intentions" and the person handing one out is the only check on that.
 */
export function ApiTokens() {
  const client = useQueryClient()
  const tokens = useQuery({ queryKey: ['tokens'], queryFn: api.tokens })
  const [name, setName] = useState('')
  const [chosen, setChosen] = useState<string[]>(['albums:read'])
  const [minted, setMinted] = useState<ApiTokenView | null>(null)

  const create = useMutation({
    mutationFn: () => api.createApiToken(name.trim(), chosen),
    onSuccess: (token) => {
      setMinted(token)
      setName('')
      client.invalidateQueries({ queryKey: ['tokens'] })
    },
  })

  return (
    <Card className="flex flex-col gap-3">
      <div>
        <h2 className="m-0 text-sm font-medium text-ink">API tokens</h2>
        <p className="m-0 mt-1 text-xs text-muted">
          For an agent or a script. The token is shown once, and it can do exactly what you tick.
        </p>
      </div>

      <div className="flex flex-col gap-2">
        {SCOPES.map((scope) => (
          <label key={scope.value} className="flex items-start gap-2 text-xs text-muted">
            <input
              type="checkbox"
              className="mt-0.5 accent-[var(--colour-muted)]"
              checked={chosen.includes(scope.value)}
              onChange={(event) =>
                setChosen((previous) =>
                  event.target.checked
                    ? [...previous, scope.value]
                    : previous.filter((value) => value !== scope.value),
                )
              }
            />
            <span>
              <span className="text-ink">{scope.label}</span> — {scope.note}
            </span>
          </label>
        ))}
      </div>

      <div className="flex gap-2">
        <Input
          value={name}
          placeholder="What is it for?"
          aria-label="Token name"
          onChange={(event) => setName(event.target.value)}
          className="w-56"
        />
        <Button
          variant="primary"
          disabled={!name.trim() || chosen.length === 0 || create.isPending}
          onClick={() => create.mutate()}
        >
          Create token
        </Button>
      </div>

      {minted?.token && (
        <div className="rounded-[10px] border border-line bg-[#0f0f11] p-3">
          <p className="m-0 mb-2 text-xs text-muted">Copy it now. It is stored as a hash and cannot be shown again.</p>
          <code className="block break-all text-xs text-ink">{minted.token}</code>
        </div>
      )}

      {tokens.data && tokens.data.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {tokens.data.map((token) => (
            <li key={token.id} className="flex flex-wrap items-center gap-2 text-xs">
              <span className={token.revokedAt ? 'text-muted line-through' : 'text-ink'}>{token.name}</span>
              <span className="text-muted">{token.scopes.join(', ')}</span>
              <span className="text-muted">
                {token.lastUsedAt ? `used ${new Date(token.lastUsedAt).toLocaleDateString()}` : 'never used'}
              </span>
              <span className="grow" />
              {!token.revokedAt && (
                <Button
                  size="sm"
                  variant="danger"
                  onClick={() =>
                    api.revokeApiToken(token.id).then(() => client.invalidateQueries({ queryKey: ['tokens'] }))
                  }
                >
                  Revoke
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
