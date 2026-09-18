# Run B — the creator

Judged on task completion, not beauty. Aesthetics matter less than legibility of state.

**The measured task:** forty photographs, from picker to a shareable link.

The fixture album is thirteen photographs; run B duplicates it to forty, because how a reorder feels
at forty items is the thing being judged and it cannot be judged at six.

## What the references are

Tools where the work is the point and the interface gets out of the way.

- **Linear** — https://linear.app — keyboard-first, state visible at a glance, no ceremony between
  intent and result.
- **Cloudflare dashboard** — https://dash.cloudflare.com — long operations that report honestly:
  what is running, what failed, what you can do about it.
- **GitHub Actions** — https://github.com — per-item progress inside a batch, with failures that
  stay on screen and stay actionable.
- **macOS Finder** — batch operations on files, drag to reorder, multi-select that behaves the way a
  hand expects.

**Take:** progress that names what is happening to which item. A failure that carries its own retry.
Selection and reordering that survive forty items without a scroll fight.

**Reject:** a spinner that says nothing. A toast that vanishes with the only copy of an error. A
modal in front of a long-running upload.

## What is being measured

- **Interactions from picker to link.** Count every click, drag and keystroke. Fewer is better, and
  a shortcut that only an expert finds does not count.
- **Clarity of upload and processing progress.** Per item, not a single bar. A creator must be able
  to tell "that one failed" from "that one is slow".
- **Reorder at forty items.** Dragging item 38 to position 2 without a scroll hijack, on a laptop
  trackpad and on a phone.
- **How failure is surfaced.** An item that failed to process is a state the creator can act on:
  retry or remove, both reachable without leaving the album.

## Permitted here, and only here

shadcn/ui, with its tokens restyled so it does not read as a stock template (`SDD.md` §7.2). The
viewer stays custom; the creator is allowed the component library.

## Constraints

- No hard bundle cap, but the creator app must not be loaded by a recipient opening a link. Route
  splitting is the mechanism, and `SDD.md` §7.3 makes it a CI gate on the viewer route
- Works on a phone: a creator uploading holiday photographs is often not at a desk
- Every destructive action is reversible or confirmed. Deleting an item deletes bytes
