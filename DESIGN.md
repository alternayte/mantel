# Design

Frozen by gauntlet run A (`BUILD.md` §5, `site/references/run-a.md`). This document is authoritative
for all UI work. Changing it is a deliberate act, not a side effect of a later pull request.

The tokens live in `design/tokens.json`, and `web/src/styles/tokens.css` is generated from it. Edit
the JSON, run `just tokens`, commit both. A future Compose client reads the same JSON (`SDD.md` §10).

## The direction

**A book of plates.** The photographs are laid out in justified rows on near-black, at their own
proportions, with nothing else on the screen.

The failure mode being avoided is "a well-made website": a header bar, a logo, a view count, a share
button, a square crop. Run A's reference set names Google Photos, Flickr, 500px and Instagram as the
things the viewer must not resemble.

## The decisions, and why

**Nothing is cropped.** Each row is filled by solving for the row height that makes the
photographs' own aspect ratios add up to the measure. A 2.6:1 panorama and a 4:5 portrait sit in the
same row at the same height, each at its own width. Two of the four run A variants cropped every
photograph to make a tidy grid, which is the one thing a photo product may not do.

**Near-black, not black.** `#0c0c0d`. A true black turns a dark photograph into a hole in the page;
a near-black gives the frame an edge.

**Warm off-white type.** `#ece9e4` against a warm photograph, because pure white vibrates.

**The title is content, not chrome.** It sits above the first row like a book's title page and
scrolls away. Once it has, there is no interface on the screen at all. The measured chrome at rest
is zero pixels: no bar, no logo, no floating control.

**Controls appear on intent.** In the lightbox, the close and the arrows fade in on pointer movement
or focus and fade out again. A keyboard user always has them.

**System typeface, one family, three sizes.** A web font is an external request, and `SDD.md` §7.3
forbids those. The identity comes from the spacing and the restraint, not from a licence.

**Photographs are not rounded.** A rounded corner turns a photograph into a card, and a card is a
thing in an interface rather than a thing to look at. `radius.card` exists for the PIN card only.

**Failure is desaturated.** `#b0705f` rather than a warning red. A red alert next to somebody's
holiday photographs is an alarm in a gallery.

**A row becomes a column on a phone.** Below 640px a justified row of four is four postage stamps.
One photograph per row, full width, at its own ratio.

## The component inventory

Nine components for the viewer. Each is here because it has a second use or a state nobody else
owns; `site/references/output.md` sets that bar.

| Component | Responsible for |
|---|---|
| `AlbumTitle` | The title, and nothing else on the first screen |
| `JustifiedRows` | Solving row heights so nothing is cropped, and resizing without shift |
| `Photo` | One photograph: the right source for the screen, its own ratio, no crop |
| `VideoTile` | A video in the grid: poster, duration, the fact that it is a video |
| `Lightbox` | One item full-bleed, keyboard and swipe, controls that fade |
| `PinScreen` | One input on an otherwise empty page |
| `ProcessingTile` | An item that is not ready yet, in its place in the order |
| `FailedTile` | An item that failed, visible rather than missing |
| `EmptyState` | An album with nothing in it, and an album that is gone |

## The creator's inventory

Run B (`site/references/run-b.md`) chose one workspace over a wizard: drop, watch, publish, with
nothing between the creator and the album. Seven more components, shadcn-style — the project owns
the source, and every one is styled from the tokens above rather than from a library's defaults.

| Component | Responsible for |
|---|---|
| `Button`, `Input`, `Field`, `Card`, `Dialog` | The plain furniture, in three variants and two sizes |
| `Meter` | Storage used against quota, in the header where it is noticed |
| `ItemGrid` | Every item in its position, draggable by pointer and by keyboard |
| `UploadProgress` | One row per file, so a slow upload is distinguishable from a dead one |
| `ShareLinks` | Publishing, PIN, expiry, copy and revoke, on the album's own page |

The creator surface is one shade lighter than the viewer: the viewer is a gallery at night, the
creator is a desk with a lamp on it. Both read the same tokens.

## What is not in the viewer

No header, no footer, no navigation, no logo, no share button, no download button in v1's grid, no
view count, no avatar, no comment, no theme switch. Dark mode is not a setting because the viewer
has one mode.
