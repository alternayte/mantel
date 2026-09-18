# Library and phone backup

## What it does

An account owns a library of media items. An album is an ordered selection from that library, so
deleting an album keeps the photographs. The Android app backs up the phone's media to the library
in the background when the person turns sync on. Both clients show the library as one
reverse-chronological grid, and an album is assembled by selecting from it.

## Decisions

- Media belongs to the account, not to an album — backup and sharing want opposite lifetimes.
- `album_item` is a join row holding position and caption — one photograph sits in two albums with a
  different caption in each.
- Quota counts a media item once, whatever number of albums hold it — the bytes exist once.
- The library is browsable, reverse-chronological, with multi-select — picking forty photographs out
  of four thousand needs that screen anyway.
- A media item gets its thumbnail on arrival, and a video its poster frame — the grid needs one.
- Display and video derivatives render when the item first joins an album — transcoding media nobody
  shares is the difference between a hosted plan that works and one that does not.
- The media lifecycle gains a state for backed up and a state for shareable — `ready` cannot mean
  both. An album does not publish while any of its items is only backed up.
- Sync is one-way and additive — a deletion on the phone leaves the backup, which is the point.
- Sync never deletes from the phone — an app that can empty a camera roll will, once.
- Turning sync off stops new uploads and removes nothing — off means off, not undo.
- A media item's identity is a SHA-256 of its original bytes, unique per account — it survives a
  reinstall and a new phone, and the picker and sync agree for free.
- The upload intent carries the hash, and the API issues no presigned PUT for media it already holds
  — the app sends the bytes once.
- Dedupe is per account, never across accounts — cross-account dedupe tells one person what a
  stranger holds, and makes deletion a reference count.
- Sync asks for `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` only when the person turns it on — an
  install that never syncs grants nothing, and the picker still needs no permission.
- Sync offers the device's media folders with Camera on and the rest off — the rest of the device is
  screenshots and downloads.
- Sync runs on an unmetered network and on charge by default, and the person can change both — a
  backup that spends a data plan is a backup that gets turned off.
- The library stores any file the camera produced, whatever its type — a backup that silently drops
  raw files is not a backup.
- `ACCEPTED_TYPES` classifies rather than admits. An item nothing can render has no thumbnail, shows
  as a filename, and cannot join an album — albums stay renderable, so the viewer is untouched.
- Originals stay byte-identical, so a derivative set is re-derivable — support for a new format is a
  re-render of media the account already holds, on the existing retry path.
- A per-file size ceiling replaces the format check as the thing that refuses a 40 GB video.
- The API grows the library endpoints and both clients use them — no client gets a branch of its own.
- The viewer never sees a library. A share link points at an album.

## Out

- Search of any kind: faces, places, text, dates, labels.
- Billing, plans and payment. Quota stays one number per account that the operator sets.
- What happens when a hosted subscription lapses. That belongs to the build that takes the money.
- Two-way sync, and restore to the phone.
- Folders, tags or any organisation of the library other than time order.
- iOS.

## How I know it works

- An album is deleted. The photographs stay in the library, and the account's used bytes do not
  change.
- The same photograph is added to two albums. It carries a different caption in each, and the
  account's used bytes count it once.
- Sync is turned on with Camera selected. The phone's camera folder appears in the library grid, and
  screenshots do not.
- The app is reinstalled and sync runs again. No photograph uploads twice, and used bytes do not
  move.
- A photograph is deleted from the phone after it backs up. It stays in the library.
- Sync is turned off. Nothing leaves the library, and no new media arrives.
- A backed-up photograph that is in no album has a thumbnail and no display derivative. It gains one
  when it joins an album.
- An album with a backed-up-only item refuses to publish, and says which item is not ready.
- A DNG file backs up. It appears in the grid as a filename, has no thumbnail, and cannot be added to
  an album.
- An account export contains every media item in the library, not only the ones in albums.
