/**
 * Seeds the permanent demo album that the README links to (BUILD.md §7).
 *
 * It is idempotent: it finds the album by title, only uploads what is missing, and only creates a
 * share link when there is no live one. Run it against a fresh deployment, or run it again after
 * one to check the demo is still whole.
 *
 *   bun run scripts/seed-demo.ts --base https://albums.example.com --token mantel_…
 *   bun run scripts/seed-demo.ts --local            # uses the compose stack and its log
 *
 * The token needs albums:read, albums:write and share:write. Make one at /app/settings.
 */
import { readdir, readFile, stat } from 'node:fs/promises'
import { basename, join } from 'node:path'

const ALBUM_TITLE = 'Cornwall, August'
const ALBUM_DESCRIPTION = 'Three days on the north coast. The demo album — every photograph is CC0.'
const FIXTURES = 'site/references/album'

/** A caption on some, not all: an album where every photograph is captioned is a catalogue. */
const CAPTIONS: Record<string, string> = {
  '01-cafe-table-0.jpg': 'low tide, first morning',
  '05-lighthouse-0.jpg': 'the long way back',
  '09-panorama.jpg': 'the whole bay, from the coast path',
}

type Args = { base: string; token?: string; local: boolean; email: string; replace: boolean }

function parseArgs(argv: string[]): Args {
  const args: Args = {
    base: process.env.MANTEL_BASE_URL ?? 'http://localhost:8080',
    token: process.env.MANTEL_SEED_TOKEN,
    local: false,
    email: process.env.MANTEL_SEED_EMAIL ?? 'demo@mantel.example',
    replace: false,
  }
  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i]
    if (flag === '--base') args.base = argv[++i]
    else if (flag === '--token') args.token = argv[++i]
    else if (flag === '--email') args.email = argv[++i]
    else if (flag === '--local') args.local = true
    else if (flag === '--replace') args.replace = true
    else if (flag === '--help') {
      console.log('usage: bun run scripts/seed-demo.ts [--base URL] [--token TOKEN | --local] [--email ADDRESS] [--replace]')
      process.exit(0)
    }
  }
  args.base = args.base.replace(/\/$/, '')
  return args
}

/** Locally there is no inbox, so the sign-in link is read out of the app's log. */
async function sessionFromLog(args: Args): Promise<string> {
  const sent = await fetch(`${args.base}/api/auth/magic-link`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: args.email }),
  })
  if (!sent.ok) throw new Error(`the instance refused a sign-in link (${sent.status})`)

  await new Promise((resolve) => setTimeout(resolve, 1500))
  const logs = Bun.spawnSync(['docker', 'compose', 'logs', 'app'])
  const found = [...logs.stdout.toString().matchAll(/\/api\/auth\/magic-link\/callback\?token=([A-Za-z0-9]+)/g)]
  if (found.length === 0) {
    throw new Error('no sign-in link in the app log. Is this the compose stack, and is it running?')
  }

  const callback = `${args.base}/api/auth/magic-link/callback?token=${found[found.length - 1][1]}`
  const response = await fetch(callback, { redirect: 'manual' })
  const cookie = response.headers.get('set-cookie')?.split(';')[0]
  if (!cookie) throw new Error('signing in produced no session')
  return cookie
}

class Client {
  constructor(
    private readonly base: string,
    private readonly headers: Record<string, string>,
  ) {}

  async call<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`${this.base}${path}`, {
      ...init,
      headers: { ...this.headers, ...(init?.body ? { 'content-type': 'application/json' } : {}), ...init?.headers },
    })
    const text = await response.text()
    if (!response.ok) {
      const code = (() => {
        try {
          return JSON.parse(text).error?.code
        } catch {
          return undefined
        }
      })()
      if (code === 'forbidden') {
        throw new Error(`${path}: the token is missing a scope. It needs albums:read, albums:write and share:write.`)
      }
      throw new Error(`${path}: ${response.status} ${text.slice(0, 200)}`)
    }
    return text ? (JSON.parse(text) as T) : (undefined as T)
  }
}

type Album = { id: string; title: string; itemCount: number; status: string }
type Item = { id: string; status: string; filename?: string | null; caption?: string | null; position: number }
type AlbumView = Album & { items: Item[]; coverItemId?: string | null }
type Link = { id: string; url: string; live: boolean; expiresAt?: string | null; hasPin: boolean }

async function main() {
  const args = parseArgs(process.argv.slice(2))

  const headers: Record<string, string> = {}
  if (args.token) headers.authorization = `Bearer ${args.token}`
  else if (args.local) headers.cookie = await sessionFromLog(args)
  else throw new Error('give me --token, or --local to sign in through the compose stack')

  const api = new Client(args.base, headers)
  console.log(`seeding ${args.base}`)

  // 1. The album, found by title so a second run does not make a second one.
  const albums = await api.call<Album[]>('/api/albums')
  let album = albums.find((candidate) => candidate.title === ALBUM_TITLE)
  if (!album) {
    album = await api.call<Album>('/api/albums', {
      method: 'POST',
      body: JSON.stringify({ title: ALBUM_TITLE, description: ALBUM_DESCRIPTION }),
    })
    console.log(`  created the album`)
  } else {
    console.log(`  the album is already there with ${album.itemCount} items`)
  }

  // 2. The photographs, skipping any already uploaded under the same name.
  const files = (await readdir(FIXTURES)).filter((name) => !name.startsWith('.')).sort()
  let view = await api.call<AlbumView>(`/api/albums/${album.id}`)
  const present = new Set(view.items.map((item) => item.filename).filter(Boolean) as string[])
  const missing = args.replace ? files : files.filter((name) => !present.has(name))

  if (missing.length > 0) {
    console.log(`  uploading ${missing.length} of ${files.length}`)
    const declared = await Promise.all(
      missing.map(async (name) => ({
        filename: name,
        contentType: name.endsWith('.mp4') ? 'video/mp4' : 'image/jpeg',
        sizeBytes: (await stat(join(FIXTURES, name))).size,
      })),
    )
    const intent = await api.call<{ items: { itemId: string; uploadUrl?: string; parts?: { partNumber: number; uploadUrl: string; sizeBytes: number }[] }[] }>(
      `/api/albums/${album.id}/upload-intent`,
      { method: 'POST', body: JSON.stringify({ files: declared }) },
    )

    for (const [index, name] of missing.entries()) {
      const target = intent.items[index]
      const bytes = await readFile(join(FIXTURES, name))
      const contentType = declared[index].contentType
      if (target.uploadUrl) {
        const put = await fetch(target.uploadUrl, { method: 'PUT', headers: { 'content-type': contentType }, body: bytes })
        if (!put.ok) throw new Error(`storage refused ${name} (${put.status})`)
      } else {
        // A large file, in parts. Nothing here is that large yet, but the demo should not be the
        // one place that breaks if somebody adds a video to the fixtures.
        let offset = 0
        for (const part of target.parts ?? []) {
          const slice = bytes.subarray(offset, offset + part.sizeBytes)
          const put = await fetch(part.uploadUrl, { method: 'PUT', headers: { 'content-type': contentType }, body: slice })
          if (!put.ok) throw new Error(`storage refused part ${part.partNumber} of ${name} (${put.status})`)
          offset += part.sizeBytes
        }
      }
      process.stdout.write(`\r  uploaded ${index + 1}/${missing.length}`)
    }
    process.stdout.write('\n')

    await api.call(`/api/albums/${album.id}/uploads/complete`, {
      method: 'POST',
      body: JSON.stringify({ itemIds: intent.items.map((item) => item.itemId) }),
    })
  } else {
    console.log('  every photograph is already there')
  }

  // 3. Wait for the worker. A demo album with half its photographs missing is worse than none.
  const deadline = Date.now() + 15 * 60 * 1000
  for (;;) {
    view = await api.call<AlbumView>(`/api/albums/${album.id}`)
    const waiting = view.items.filter((item) => item.status !== 'ready' && item.status !== 'failed')
    const failed = view.items.filter((item) => item.status === 'failed')
    if (waiting.length === 0) {
      if (failed.length > 0) {
        throw new Error(`${failed.length} items failed to process. Check the worker log; the demo should be whole.`)
      }
      console.log(`  ${view.items.length} photographs ready`)
      break
    }
    if (Date.now() > deadline) throw new Error(`gave up waiting: ${waiting.length} items are still not ready`)
    process.stdout.write(`\r  waiting for the worker: ${view.items.length - waiting.length}/${view.items.length} ready`)
    await new Promise((resolve) => setTimeout(resolve, 3000))
  }

  // 4. Captions and a cover, so the demo reads like somebody's album rather than a test fixture.
  for (const item of view.items) {
    const caption = item.filename ? CAPTIONS[item.filename] : undefined
    if (caption && item.caption !== caption) {
      await api.call(`/api/albums/${album.id}/items/${item.id}`, { method: 'PATCH', body: JSON.stringify({ caption }) })
    }
  }
  const cover = view.items.find((item) => item.filename === '07-mountain-lake-0.jpg') ?? view.items[0]
  if (cover && view.coverItemId !== cover.id) {
    await api.call(`/api/albums/${album.id}`, { method: 'PATCH', body: JSON.stringify({ coverItemId: cover.id }) })
  }

  // 5. One live link that never expires. BUILD.md §7: the demo must not expire.
  const links = await api.call<Link[]>(`/api/albums/${album.id}/share-links`)
  const permanent = links.find((link) => link.live && !link.expiresAt && !link.hasPin)
  const link = permanent ?? (await api.call<Link>(`/api/albums/${album.id}/share-links`, { method: 'POST', body: '{}' }))
  console.log(permanent ? '  the permanent link is already there' : '  created a permanent link')

  console.log('')
  console.log(`  ${link.url}`)
  console.log('')
  console.log('  For the README:')
  console.log('')
  console.log(`  **[${link.url}](${link.url})** — a permanent public album. It does not expire.`)
}

main().catch((failure) => {
  console.error(`seed-demo: ${failure instanceof Error ? failure.message : failure}`)
  process.exit(1)
})
