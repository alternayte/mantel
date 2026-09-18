# Run A — result

Four variants, judged on the fixture album at 1440×900 and 360px. Screenshots in the run notes; the
verdicts are below as measurements, per `criteria.md`.

| | A — Book | B — Contact sheet | C — Spread | D — Justified |
|---|---|---|---|---|
| Photographs cropped | 0 of 13 | 13 of 13 | 13 of 13 | 0 of 13 |
| Screens to reach photograph 13 | 13 | 2 | 4 | 2 |
| Chrome at rest (controls, bars) | none | header bar | fixed header over image | none |
| Panorama at 2.6:1 | correct | cropped to 1.5:1 | cropped to 2.33:1 | correct |
| Portrait at 4:5 | correct | cropped to 1.5:1 | cropped to 2.33:1 | correct |
| Reads as | a book | a well-made website | a gallery site | a book of plates |

## Why D won

**A was right about everything except the reading.** One photograph per screen is how a monograph
works and not how forty holiday photographs work: thirteen screens of scrolling to see thirteen
pictures, and no sense of the set. Its restraint is kept.

**B and C both crop.** `object-fit: cover` and a forced `aspect-ratio` are the same decision written
two ways, and both throw away the photographer's frame. The fixture album exists to catch this: the
2.6:1 panorama and the 4:5 portraits are unusable in either. A product whose pitch is "your photos,
as you took them" cannot crop them to make a tidy grid.

**D computes the rows.** Each row is filled to the measure by solving for a row height that makes
the photographs' own ratios add up, so nothing is cropped and nothing is letterboxed. It is the
contact sheet's overview with the book's respect for the frame.

## What D still has to prove

The prototype is static HTML. The implementation has to keep these properties under a real manifest:

- the row solver runs on resize without layout shift (criterion 5: CLS < 0.01)
- a portrait next to a panorama in the same row does not produce a 900px-tall row
- the last row does not stretch to fill
- at 360px the rows become a single column, because a justified row of four on a phone is four
  postage stamps

## Measured after implementation

Taken in a real browser against the real API, on the fixture album, once the winner was built.

| Criterion | Target | Measured |
|---|---|---|
| 1. Gzipped JS for `/a/{token}` | ≤ 90 kB | 71 kB (`checks/bundle.sh`) |
| 2. Cookies on an unprotected album | zero | zero (`NoCookieTest`) |
| 3. External requests beyond the CDN | zero | zero: system typeface, no analytics |
| 8. Aspect ratios rendered without cropping | all four | all four, asserted in `justify.test.ts` |
| 9. 360px with no horizontal scroll | no scroll | no scroll; rows become one column |
| 10. Hardcoded colours outside the token block | zero | zero; every value reads a custom property |
| 11. Every state rendered | six | six: full, processing, failed, empty, PIN, gone |
| 12. Typefaces loaded | ≤ 1, self-hosted | zero loaded; the system stack |

Two came out of driving the real page rather than the prototype:

- **The Vite React preamble.** The album shell is served by Ktor, not by Vite, so no component
  rendered in development and the page was blank. The shell now carries the preamble.
- **A revoked link served raw JSON** to a person in a browser. `/a/{token}` now answers with the
  same page and a 404 on it, identical for a revoked and an invented token.
