import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'

/**
 * The component inventory for the creator (DESIGN.md). shadcn's model: the project owns the source,
 * so the set stays small and every piece is styled from the frozen tokens rather than from a
 * library's defaults.
 */

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'quiet' | 'danger'
  size?: 'md' | 'sm'
}

const buttonBase =
  'inline-flex items-center justify-center gap-2 rounded-[10px] font-medium transition-colors ' +
  'disabled:opacity-40 disabled:pointer-events-none whitespace-nowrap'

const variants = {
  primary: 'bg-ink text-[var(--work-surface)] hover:bg-white',
  quiet: 'bg-[var(--work-lift)] text-ink hover:bg-[#232327] border border-line',
  danger: 'bg-transparent text-fail border border-[#3a2a28] hover:bg-[#241c1b]',
}

const sizes = { md: 'h-9 px-3.5 text-sm', sm: 'h-7 px-2.5 text-xs' }

export function Button({ variant = 'quiet', size = 'md', className = '', ...props }: ButtonProps) {
  return <button {...props} className={`${buttonBase} ${variants[variant]} ${sizes[size]} ${className}`} />
}

export function Input({ className = '', ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={
        'h-9 rounded-[10px] border border-line bg-[var(--work-lift)] px-3 text-sm text-ink ' +
        `placeholder:text-muted ${className}`
      }
    />
  )
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5 text-sm text-muted">
      {label}
      {children}
    </label>
  )
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`rounded-[10px] border border-line bg-[var(--work-lift)] p-4 ${className}`}>{children}</div>
  )
}

/** Native dialog: a modal is one of the few places a browser already does the hard part. */
export function Dialog({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
}) {
  if (!open) return null
  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/60 p-4"
      onClick={onClose}
      role="presentation"
    >
      <div
        className="w-full max-w-md rounded-[10px] border border-line bg-[var(--work-lift)] p-5"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <h2 className="mb-4 text-sm font-medium text-ink">{title}</h2>
        {children}
      </div>
    </div>
  )
}

export function Meter({ used, total }: { used: number; total: number }) {
  const percent = total > 0 ? Math.min(100, (used / total) * 100) : 0
  return (
    <div className="flex items-center gap-2" title={`${gigabytes(used)} of ${gigabytes(total)} used`}>
      <div className="h-1.5 w-28 overflow-hidden rounded-full bg-[var(--work-lift)]">
        <div className="h-full bg-muted" style={{ width: `${percent}%` }} />
      </div>
      <span className="text-xs text-muted tabular-nums">
        {gigabytes(used)} / {gigabytes(total)}
      </span>
    </div>
  )
}

export function gigabytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} kB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}
