# Run C — result

The Android app's look, judged against Halide, Darkroom, Flighty and Things. The hard rule is Google
Photos' function without its look; Google Photos and stock Material 3 are the anti-references
(`docs/specs/photos-app.md`).

## Method

The real app on the `Pixel_6_VIC` emulator, against the local stack, with the fixture album in the
library. Three parts, each cropped from a screenshot of the real app and set beside a crop of a
reference screen:

| Part | App screen | Reference |
|---|---|---|
| Header | Albums, top | Things 3, dark mode project header |
| List | Albums, album rows | Things 3, dark mode rows |
| Grid | Library | Darkroom, library grid |

The references are the App Store screenshots of each app, cropped to the device screen:
Things 3 (`apps.apple.com/app/id904237743`, screenshot 7), Darkroom
(`apps.apple.com/app/id953286746`, screenshot 6). They are not committed: they are not ours.

One critic per part, fresh context each round, shown only the two crops in random order and the
critic prompt. Each image is scored 1–10 on typography, spacing, alignment, colour and hierarchy;
a part's score is the sum, out of 50.

## Scores

| Part | Round 1: app / reference | Round 2: app / reference |
|---|---|---|
| Header | 30 / 41 | 28 / 42 |
| List | 27 / 43 | 27 / 42 |
| Grid | 23 / 40 | 24 / 38 |
| **App total** | **80** | **79** |

Round 1's largest gap was the grid, so round 2 changed the grid and nothing else. The header and the
list were not changed between rounds; their movement is the critics' own spread, about two points.

The run stops when the total does not improve. It did not, so there is no round 3.

## What changed

**The library grid runs to the screen's edges in four columns, and a tile has no frame at rest.**
Round 1's grid critic named the frame round every tile and the page margin round the grid as the
largest differences. Round 2 closed the grid's gap from 17 to 14. Selection still draws the frame,
in `ink`.

## What every critic named, and was not changed

These recur across both rounds and all three parts. They are the open questions for the look, not
decisions: the run stopped before any of them was tried.

1. **A screen has no title.** Every critic asked for one large bold title per screen, with the
   `ALBUMS · LIBRARY` switch below it or gone. The switch goes anyway in the app spec.
2. **Rows are cards.** The list critics asked for flat rows on the page with hairline dividers,
   about 40% less vertical padding, and a leading thumbnail.
3. **There is no accent colour.** Every critic asked for one, on structure and state only.
4. **The surface is neutral near-black.** Two critics asked for a cool tint.

Two critic proposals were refused because they break a fixed rule: a white page (the grid critic,
twice), because Google Photos is the anti-reference and `DESIGN.md` sets near-black; and a red
selection outline, because failure owns the only warm colour.
