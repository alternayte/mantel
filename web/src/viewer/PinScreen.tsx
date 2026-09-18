import { useState } from 'react'
import { unlock } from './manifest'

/** One input on an otherwise empty page. It is the first thing some recipients see of this product. */
export function PinScreen({ token, onUnlocked }: { token: string; onUnlocked: () => void }) {
  const [pin, setPin] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  return (
    <main className="pin">
      <p>This album is protected.</p>
      <form
        onSubmit={async (event) => {
          event.preventDefault()
          setBusy(true)
          setProblem(null)
          const outcome = await unlock(token, pin)
          setBusy(false)
          if (outcome === 'ok') return onUnlocked()
          if (outcome === 'wrong') setProblem('That PIN is not right.')
          else if (outcome === 'too-many') setProblem('Too many attempts. Wait a few minutes.')
          else setProblem('Something went wrong. Try again.')
          setPin('')
        }}
      >
        <input
          autoFocus
          inputMode="numeric"
          pattern="[0-9]*"
          maxLength={12}
          value={pin}
          aria-label="PIN"
          onChange={(event) => setPin(event.target.value.replace(/\D/g, ''))}
        />
        <button type="submit" disabled={busy || pin.length < 4}>
          Open
        </button>
      </form>
      {problem && <p role="alert">{problem}</p>}
    </main>
  )
}
