# Photos library

## What it does

The library becomes the product: every photograph an account owns, in the order it was taken, each one
viewable at full screen. A deleted media item goes to a trash for 30 days, and the person can restore
it. The phone app is built on this spec; `docs/specs/photos-app.md` depends on it. The web creator
keeps working against the changed API and gains no new screens.

## Decisions

- `SDD.md` §1 changes: Mantel is a personal photo library that shares albums as a link — the code
  already went there with the library and phone backup, and the SDD must say what is built.
- The recipient's viewer does not change — no account, no app, no tracking is still what the product
  does that Google Photos does not.
- `SDD.md` §1, §2, §4, §5, §6 and §10 change to match, on the owner's instruction of 2026-09-26 —
  `SDD.md` is the source of truth and must say what is built.
- Every photograph gets the 1600 px display WebP when it is backed up — the phone views a library
  photograph that is no longer on the phone, and a 300 px thumbnail is not viewable.
- AVIF and the 1080p video transcode stay for album items only — only a browser recipient needs them.
- The worker backfills the display WebP for media items that are backed up without one — the library
  already holds photographs that the phone cannot open.
- "backed up" now means the original, the thumbnail and the display WebP exist — the domain word
  changes in `AGENTS.md` through the vocabulary pass.
- A media item gains `taken_at` — a timeline groups by the day a photograph was taken, not the day it
  was uploaded.
- The upload intent accepts `takenAt` from the client — the phone knows `DATE_TAKEN` before a byte moves.
- The worker reads EXIF `DateTimeOriginal`, or a video's `creation_time`, and writes it over the
  declared value — a web upload has no `DATE_TAKEN`, and the file is the better witness.
- Upload time is the last fallback — every media item has a place in the timeline.
- The library orders by `taken_at` newest first and pages on (`taken_at`, id) — the id cursor orders
  by upload, which is the wrong order.
- `ItemView` carries `takenAt`, `contentHash` and `displayUrl` — the phone matches its camera roll to
  the library by hash and opens a photograph at display size.
- A media item gains a trashed state with `trashed_at` — a library that one wrong tap empties for good
  is not a photo library.
- `DELETE /api/library/{itemId}` moves a media item to the trash, and `POST
  /api/library/{itemId}/restore` brings it back — delete stays the one verb, and the trash is its undo.
- `GET /api/library/trash` lists the trash, and `DELETE /api/library/trash/{itemId}` removes one item now.
- A trashed item leaves the timeline and every album; a share link to the album keeps working without
  it — a recipient sees the album as it is.
- The worker's sweep removes a trashed item's bytes after 30 days through `removeItem` — the same
  cleanup a deliberate delete runs.
- A trashed item counts against quota until the sweep removes it — its bytes are still in storage.
- The web library moves to the new cursor, shows when each photograph was taken, and sends a delete to
  the trash with an undo — no client may delete for good while the phone has a trash.

## Out

- No new web screens: no web timeline, no web viewer, no web trash.
- No AVIF or video transcode for a media item that is not in an album.
- No favourites, no map, no search, no people.
- No change to the recipient's viewer or to share links.

## How I know it works

- `GET /api/library` returns media items newest `takenAt` first, and the next page continues where
  the first stopped.
- A photograph uploaded from the web with EXIF `DateTimeOriginal` shows that date as `takenAt`.
- A backed-up photograph's `displayUrl` opens a 1600 px WebP.
- After the backfill, no backed-up media item lacks a display WebP.
- A deleted item is gone from the library and from its album, appears in the trash, and returns to
  both on restore.
- With the clock moved 31 days on, the sweep removes a trashed item's bytes and the account's storage
  figure drops.
- The web library pages and deletes against the new API, and its delete offers an undo.
- `just check` passes.
