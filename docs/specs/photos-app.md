# Photos app

## What it does

The Android app is a photo library that also shares albums as a link. It opens on one timeline of
the phone's camera roll and the library together, grouped by the day each photograph was taken. A
photograph opens full screen, zooms, and plays if it is a video. Backup starts when a photograph is
taken, and the app always says what it is doing. This spec depends on `docs/specs/photos-library.md`,
and on gauntlet run C, which fixes the look before any screen is built.

## Decisions

- Gauntlet run C decides the look and freezes it into `DESIGN.md`, replacing "The creator's motion"
  and the peers section; its verdicts are numbers from the real app — `BUILD.md` §5.
- Run C's hard rule is Google Photos' function without its look; its references are Halide,
  Darkroom, Flighty and Things, and its anti-references are Google Photos and stock Material 3 — the
  owner's constraint.
- A navigation bar at the foot of the screen holds Photos, Albums and Shared, each with an icon —
  three real sections replace the type switch.
- Shared lists every live share link across all albums, with what it opens, when it expires, and a
  revoke — Mantel exists for this, and nothing else lists it.
- Account, storage, backup settings, the trash and sign-out sit behind the avatar at the top of
  Photos — they are visited, not lived in.
- Photos is one timeline of the camera roll and the library — a photograph shows the moment it is
  taken, before it is backed up.
- A photograph on the phone opens from the phone; one only in the library opens from `displayUrl` —
  the phone's own copy is instant and full quality.
- Each tile carries a badge for backed up or not yet — the state of every photograph is visible.
- An on-device index in Room maps each camera-roll entry to its content hash and library item — it is
  how the timeline shows a photograph once.
- Uploads write their hashes to the index; photographs backed up before it existed are hashed once,
  in the background, only while charging, and again only if the file changes — hashing a camera roll
  costs battery.
- Until a photograph is hashed, a library item that matches it on taken date and size is held back —
  the timeline never shows one picture twice.
- The viewer grows from the tile; swipe moves on, pinch and double-tap zoom, swipe down closes; video
  plays with a scrubber — the gestures a person already knows.
- Telephoto draws the zoom from tiles, and Media3 plays video — a 50-megapixel original decoded whole
  crashes the app, and a player is its own problem.
- The viewer offers Share, Add to album, Info and Delete — Info says when it was taken, its size, and
  whether it lives on the phone, in the library, or both.
- Delete sends a media item to the trash and removes nothing from the phone — the backup screen
  promises that Mantel never deletes from the phone.
- Long-press starts a selection, a drag extends it, and a day header selects its day; the bar offers
  Share, Add to album with New album, and Delete — an album is made from a selection.
- Pinch moves the grid between three densities, and a handle on the right edge scrolls by month —
  years of photographs are a long way down.
- Icons are Lucide, converted to `ImageVector` source by a script under `design/icons/`, with the
  stroke width a token run C sets — R8 is off, so an icon library ships whole.
- A MediaStore content trigger starts a backup within a minute of a new photograph; the six-hourly
  sweep stays as a safety net — a backup that waits six hours looks broken.
- The status beside the avatar names the one thing true now: backing up N of M, waiting for wi-fi,
  waiting to charge, library full, N files too large, or up to date — silence reads as failure.
- The type switch and the peer disappear; retiring those domain words needs an agents-audit item —
  only an audit or the owner removes a term from `AGENTS.md`.

## Out

- No deleting from the phone, no favourites, no map, no search, no people, no editing.
- No change to the web creator or to the recipient's viewer.
- No icon library as a dependency.

## How I know it works

- The app opens on photographs from a cold start in under 500 ms on the emulator.
- Scrolling a 10,000-photograph timeline has fewer than 1% janky frames in `dumpsys gfxinfo`.
- The viewer's transition starts within one frame of the tap.
- Every touch shows feedback within 100 ms.
- A photograph taken on the phone appears in Photos at once, marked not yet backed up, and is backed
  up within a minute on wi-fi.
- With wi-fi off and "only on wi-fi" set, the status says it is waiting for wi-fi.
- A photograph on the phone and in the library shows once.
- A deleted photograph appears in the trash and returns on restore; the phone still holds it.
- A 50-megapixel photograph zooms to full detail without a crash.
- `just check` and `just check-android` pass.
