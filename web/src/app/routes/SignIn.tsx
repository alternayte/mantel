import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { Button, Card, Input } from '../components/ui'

/** Magic link or GitHub. There is no password, so there is nothing to forget or to leak. */
export function SignIn() {
  const methods = useQuery({ queryKey: ['auth-methods'], queryFn: api.signInMethods })
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  return (
    <main className="grid min-h-screen place-items-center p-6">
      <Card className="flex w-full max-w-sm flex-col gap-4">
        <h1 className="m-0 text-sm font-medium tracking-[0.2em] text-muted uppercase">Mantel</h1>
        {sent ? (
          <p className="text-sm text-ink">
            Check <strong className="font-medium">{email}</strong>. The link works once and expires in 15 minutes.
          </p>
        ) : (
          <form
            className="flex flex-col gap-3"
            onSubmit={async (event) => {
              event.preventDefault()
              setProblem(null)
              try {
                await api.requestMagicLink(email)
                setSent(true)
              } catch (failure) {
                setProblem(failure instanceof Error ? failure.message : 'That did not work')
              }
            }}
          >
            <Input
              type="email"
              required
              autoFocus
              value={email}
              placeholder="you@example.com"
              aria-label="Email"
              onChange={(event) => setEmail(event.target.value)}
            />
            <Button type="submit" variant="primary">
              Send a sign-in link
            </Button>
            {problem && <p className="m-0 text-xs text-fail">{problem}</p>}
          </form>
        )}
        {methods.data?.github && (
          <>
            <div className="flex items-center gap-3 text-xs text-muted">
              <span className="h-px grow bg-line" />
              or
              <span className="h-px grow bg-line" />
            </div>
            <a
              href="/api/auth/github"
              className="inline-flex h-9 items-center justify-center rounded-[10px] border border-line bg-[var(--work-lift)] text-sm text-ink no-underline hover:bg-[#232327]"
            >
              Continue with GitHub
            </a>
          </>
        )}
      </Card>
    </main>
  )
}
