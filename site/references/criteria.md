# Criteria

`BUILD.md` §5: the critic's verdict is a committed failing test or a specific measured number, never
prose. This file is the list of numbers and the tests that take them.

A variant is not compared to another variant by opinion. It is measured on the fixture album, in a
real browser, against the same list.

## Run A — the viewer

| # | What is measured | How | Pass |
|---|---|---|---|
| 1 | Gzipped JavaScript for `/a/{token}` | CI bundle check | ≤ 60 kB |
| 2 | Cookies set while viewing an unprotected album | `NoCookieTest` | zero |
| 3 | External requests beyond the CDN | Network panel, cold load | zero |
| 4 | Largest Contentful Paint, 4G throttle, cold cache | Lighthouse | < 2.0 s |
| 5 | Cumulative Layout Shift while the album loads | Lighthouse | < 0.01 |
| 6 | Pixels of non-photograph interface at rest, 1440×900 | Screenshot, count rows of non-image pixels | < 5% of viewport |
| 7 | Interactions to see photograph 13 from a cold open | Manual count | ≤ 2 |
| 8 | Aspect ratios rendered without cropping | Fixture album: 2.6:1, 1:1, 4:5, 3:4 | all four uncropped |
| 9 | Works at 360px wide with no horizontal scroll | Browser at 360px | no horizontal scroll |
| 10 | Hardcoded colours outside the token block | `grep -rE "#[0-9a-fA-F]{3,8}" web/src --include=*.tsx` | zero |
| 11 | Every state rendered: full, processing, failed, empty, PIN, revoked | Six screenshots | six exist |
| 12 | Typeface families loaded | Network panel | ≤ 1, self-hosted or system |

Numbers 1, 2, 9 and 10 are automatable and belong in CI. The rest are measured once per variant and
recorded in the run's notes.

## Run B — the creator

| # | What is measured | How | Pass |
|---|---|---|---|
| 1 | Interactions from picker to shareable link, 40 photographs | Manual count | recorded; fewest wins |
| 2 | Time from drop to all items uploaded, 40 photographs, local stack | Stopwatch | recorded |
| 3 | Per-item state visible during processing | Screenshot mid-run | every item has a state |
| 4 | Dragging item 38 to position 2 | Trackpad and touch | completes without a scroll fight |
| 5 | A failed item offers retry and remove without leaving the album | Force a failure with a corrupt file | both reachable |
| 6 | Creator bundle loaded on the viewer route | Network panel on `/a/{token}` | zero bytes of it |
| 7 | Works at 360px wide | Browser at 360px | upload, reorder and publish all possible |

## How a verdict is written

Not: "the grid feels cramped."

Either a number from the table above, or a committed failing test:

```
tests/gauntlet/viewer/panorama-is-not-cropped.spec.ts
  the 2.6:1 photograph renders at its own ratio
  expected: no crop
  actual:   cropped to 3:2, 41% of the image lost
```

A criticism that cannot be written as one of those two things is not a verdict; it is a preference,
and it is recorded as a preference or dropped.

## What wins

The variant that passes every hard constraint and takes the most rows in its table. A variant that
loses on a single hard constraint does not win on beauty: the constraints are the product's
promises, and the promises are what is being sold.
