# Run B — result

Judged on the measured task in `creator.md`: forty photographs, from picker to a shareable link.

Run A compared four builds, because aesthetics only exist once rendered. Run B compares three
flows, because what is being measured is interactions and legibility of state, and both can be
counted before a line is written. The winner is then measured again as built, at the bottom of this
file.

## The three flows, counted

An interaction is a click, a drag or a deliberate keystroke sequence. Waiting is not an interaction.

| Step | A — One workspace | B — Wizard | C — Two pane |
|---|---|---|---|
| Start an album | 1 (New album) | 1 | 2 (New, then focus the pane) |
| Name it | 1 | 1 | 1 |
| Add forty photographs | 1 (drop) | 2 (next, drop) | 1 |
| Reach the share control | 0 (it is on the page) | 2 (next, next) | 1 (open the share tab) |
| Create the link | 1 | 1 | 1 |
| Copy it | 1 | 1 | 1 |
| **Total** | **5** | **8** | **7** |

## Why A won

**The wizard charges for its own structure.** Three of its eight interactions are moving between its
own steps, and each step hides the previous one: while forty photographs upload, the wizard wants to
know whether you are ready for the next screen. Uploading is the longest part of the task, and it is
the part a wizard is worst at showing.

**Two panes spend interactions on navigation** that a single album does not need. It earns its keep
when a creator works across many albums at once, which is not this product's task.

**One workspace has nothing between the creator and the album.** Drop, watch, publish. The share
control lives on the same page as the photographs, which is also where the answer to "is it ready
to share?" lives.

## What A must prove, and what it usually gets wrong

- Per-item progress, not a single bar. A creator must be able to tell *that one failed* from *that
  one is slow*, which is criterion 3.
- Drag item 38 to position 2 without a scroll fight, on a trackpad and on a phone (criterion 4).
- A failed item offering retry and remove without leaving the album (criterion 5).
- The viewer route must not load a byte of it (criterion 6), which `checks/after-build/bundle.sh`
  already enforces.

## Measured as built

Driven in a real browser against the real API and a real worker, on an album of eight photographs.

| Criterion | Target | Measured |
|---|---|---|
| 1. Interactions from picker to link | fewest wins | **5**: create, name, add, publish, copy |
| 3. Per-item state during processing | every item has a state | every item: uploading %, uploaded, processing, ready, failed |
| 4. Dragging an item to position 2 | completes without a scroll fight | pointer drag moved item 8 to position 2 and persisted; keyboard moves one position per arrow |
| 5. A failed item offers retry and remove | both reachable | both, on the tile, without leaving the album |
| 6. Creator bundle on the viewer route | zero bytes | zero: separate entries, enforced by `checks/after-build/bundle.sh` |
| 7. Works at 360px | upload, reorder and publish possible | the grid reflows to one column; the share controls wrap |

Creator bundle: 34 kB gzipped on top of the shared React chunk. No cap applies, and it is recorded
so a later dependency shows up as a number rather than as a feeling.

Two things came out of driving it:

- **Keyboard reordering did not exist.** dnd-kit with only a pointer sensor leaves a keyboard user
  unable to reorder at all. A keyboard sensor and a labelled, focusable handle fixed it.
- **GitHub sign-in that is not configured answered with raw JSON.** The sign-in page asks
  `GET /api/auth/methods` and only offers what the instance can actually do.
