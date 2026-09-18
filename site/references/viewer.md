# Run A — the viewer

Judged on aesthetics and restraint. This is the surface every recipient sees, and the one place
where looking generic costs the product something.

**The target:** opening a printed book.
**The failure mode to punish:** looking like a well-made website.

The difference is not decoration. A website announces itself — header, navigation, footer, a logo in
the corner, a cookie banner, a share button. A book announces nothing. You open it and the
photographs are already there.

## What the references are

Photo books and gallery experiences. Not photo apps, not social feeds, not portfolio templates.

### Printed photo books — the primary reference

- **Steidl** — https://www.steidl.de — the reference for how a photograph sits inside a page. Wide
  margins, one image per spread, no caption competing with the picture.
- **MACK** — https://mackbooks.co.uk — sequencing. A book is an order, not a collection.
- **Aperture** — https://aperture.org — the monograph as an object: the cover is a photograph, the
  title is small, the author's name is smaller.

**Take:** the margin as the main structural decision. The absence of any chrome. Type that is small,
set once, and never competes. The picture is the interface.

**Reject:** page numbers, running heads, anything that says where you are in a book of forty photos.

### Gallery and museum viewers

- **Google Arts & Culture** — https://artsandculture.google.com — full-bleed images on black,
  controls that fade, deep zoom that is not a gimmick.
- **Gagosian** — https://www.gagosian.com — how a gallery presents a show online: image first, a
  line of type underneath, nothing else on the page.
- **Tate** — https://www.tate.org.uk — how a caption can exist without shouting.
- **Magnum Photos** — https://www.magnumphotos.com — dense grids of strong images that still breathe.
- **Fotomuseum Winterthur** — https://www.fotomuseum.ch — restraint at institutional scale.
- **International Center of Photography** — https://www.icp.org

**Take:** black or near-black surfaces where the photographs are the only light in the frame. Controls
that appear on intent and disappear on rest. Captions set as a whisper.

**Reject:** hover effects that move the image. Zoom on scroll. Anything that treats a photograph as
a card.

### Reading-first pages, for typography only

- **Are.na** — https://www.are.na — how little type is needed to hold a set of images together.
- **Kottke** — https://kottke.org — long-lived, plain, unbranded.

**Take:** one typeface, two sizes, one weight for emphasis. A measure that does not wander.

## Anti-references — the failure mode, named

These are what the viewer must not resemble. They are here so a critic can say "this is Google
Photos" and be understood.

- **Google Photos** — https://photos.google.com — dense uniform grid, cropped squares, a toolbar
  above the photographs, a floating action button. Every photograph the same size regardless of
  what it is.
- **Flickr** — https://www.flickr.com — chrome around every image: view counts, favourites,
  comments, tags, an owner's avatar.
- **500px** — https://500px.com — a photograph as a product card, with a score on it.
- **Instagram** — https://www.instagram.com — the square crop as an aesthetic, and the picture as
  the reason for the interface rather than the point of it.

If a variant has a header bar, a logo, a view count, a like, a comment, a follow, a square crop
applied to a non-square photograph, or a visible scrollbar over an image, it has failed run A
before any judging starts.

## Hard constraints, given to every variant

These are not preferences. A variant that misses one is disqualified.

- 60 kB gzipped JavaScript for the `/a/{token}` route (`SDD.md` §7.3)
- No cookies (`NoCookieTest` already enforces this in the API; the UI must not add one)
- No external requests beyond the CDN. No web fonts from a third party, no analytics, no fingerprint
- CSS custom properties for every colour. No hardcoded hex outside the token block
- Works at 360px wide
- Largest Contentful Paint under 2 seconds on 4G

## The states that are usually forgotten

`BUILD.md` §5 asks for the empty, processing and failed states to be as considered as the full one.
The fixture album can be put into any of them through the API, so there is no excuse for judging
them as mockups.

- **Processing.** A published album whose items are still rendering shows the ready ones and marks
  the rest. It does not block, and it does not show a broken grid.
- **Failed.** An item that failed is visible as a failure. It never silently disappears.
- **Empty.** An album with no items is a real state a recipient can open.
- **PIN.** The unlock screen is the first thing some recipients ever see of this product. It is one
  input on a page with nothing else on it.
- **Expired or revoked.** A 404 that does not confirm the link ever existed, and does not look like
  a crash.
