# Android motion and perceived speed

## What it does

The Android client keeps the data it has already read, so a screen draws its contents at once
instead of drawing an empty screen and filling it when the network answers. A press, a screen
change, a thumbnail arriving and a tile changing state each animate with the frozen motion tokens.
Albums and the library become peers, reached by a type switch at the top of both, and a back stack
returns you to the screen you came from. The library pages past its first hundred items, and an
album that is still rendering polls less.

## Decisions

- `AppModel` holds the last album list, the last library page with its cursor, and each album it has
  opened, in memory — the screen that starts empty is the whole of the perceived lag, and no
  animation hides it.
- A screen renders the held copy first, then refetches and replaces on a real change — the held copy
  is what you look at during the round trip, never a substitute for the fetch.
- Nothing is held on disk — a cold start reads the server, and a stale copy across days is worse
  than a blank screen.
- Pull to refresh on Albums and Library — a person who doubts what they see needs a way to ask.
- `DESIGN.md` gains a section, "The creator's motion" — the web creator has the same four problems
  and must not invent a second vocabulary.
- Motion in the creator marks a change of state and never decorates a static one — no entrance
  animation on a list, no staggered grid, no pulsing placeholder.
- A press takes the target to `surface-lift` over `motion.fast`, released on lift — `Button` and
  `Choices` pass bare `clickable` today, so a tap is invisible until the screen changes.
- A screen crossfades over `motion.medium`, forward and back, with no slide — `when (screen)` has no
  spatial model, and a slide claims one.
- A thumbnail crossfades from the tile's `surface-lift` ground over `motion.medium` — the tile is
  already at its final size, so only the image resolves and nothing moves.
- A selection border and a state change animate their colour over `motion.fast`.
- `AppModel` holds a screen stack — `back()` sets `Screen.Albums` unconditionally today, so backing
  out of the library drops you to the album list rather than the album you opened it from.
- Albums and the library are peers and replace each other; an album pushes — you live in the two,
  and you visit an album.
- The peer switch is `ALBUMS · LIBRARY` in the title style, the current one in `ink` and the other
  in `muted` — the app has no icon vocabulary, and two invented glyphs are the templated look
  `DESIGN.md` rejects.
- Backup stays a text link at the foot of the albums screen — it is a settings screen you open once,
  not a third destination.
- The album poll asks for the album alone, at 2s for the first 20 seconds and 10s after, and stops
  while the activity is not resumed — share links do not change while an item renders, and the app
  polls in your pocket today.
- Share links refetch after an action that changes them.
- The library requests 60 items and fetches the next page as the grid nears its end — it asks for
  100 once today and silently hides the rest.
- The library reads the held album list and fetches albums only when the picker opens.

## Out

- No navigation library. The stack is a list in `AppModel`.
- No bottom navigation bar and no icons.
- Nothing is held on disk. No image prefetch, no request coalescing layer.
- No change to any endpoint.
- No Compose UI test suite, no screenshot tests, no macrobenchmark.
- No change to the viewer. `DESIGN.md`'s viewer rules are untouched.

## How I know it works

- `just check-android` passes, including three new unit tests on `AppModel` against a fake
  `MantelApi`: back from the library returns the album you opened it from; entering a held screen
  emits a populated screen before the API answers; an album with one rendering item makes 20
  requests in two minutes, against 120 today.
- On a device, tapping an album you have opened before shows it with no blank frame.
- On a device, a press on any button darkens the target before the screen changes.
- On a device, thumbnails fade in rather than appear.
- On a device, an account with more than 100 library items scrolls past the hundredth.
- On a device, Albums → Album → Library → back returns to that album.
