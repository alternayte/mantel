# What a run produces

`BUILD.md` §5: the winning direction is frozen into `DESIGN.md` as machine-readable tokens plus the
rationale for each choice, and `DESIGN.md` is then authoritative for all later UI work.

This file says what "machine-readable" means here, because the answer decides whether a second
client can render the same product or has to redraw it.

## Three artefacts

1. **`design/tokens.json`** — every value the design is made of. Colour, spacing, type scale, radii,
   shadow, motion duration and easing. One file, no platform in it.
2. **Generated stylesheets** — `web/src/styles/tokens.css` as CSS custom properties, written by a
   script from `tokens.json` and never edited by hand. Tailwind v4 reads the same properties through
   `@theme`, so the creator's shadcn components restyle themselves from the tokens rather than from
   Tailwind's defaults (`SDD.md` §7.2).
3. **`DESIGN.md`** — the rationale. One short paragraph per decision: why this measure, why this
   scale, why this black rather than that one. Tokens without reasons get overwritten by the next
   person with an opinion.

## Why the tokens are a file and not a stylesheet

A stylesheet is a platform. A JSON file is a set of decisions.

`SDD.md` §10 plans a KMP creator client. When that is built it needs the same palette, the same
scale and the same motion, or the product has two designs that drift. Reading `tokens.json` and
emitting a Compose theme is a small script. Reverse-engineering a stylesheet is a redesign.

**Nothing about this is Android code, and none is written in v1.** `SDD.md` §2.2 is explicit that
deferred means not built. The choice here is only the serialisation of a file `BUILD.md` already
requires, and it costs v1 nothing: the web reads generated CSS either way.

## The component inventory

A run also produces the list of components it built, by name, with what each one is responsible for.
This is the shadcn model rather than the component-library model: the project owns the source, so
the set has to be deliberate and small.

The inventory matters for three reasons, and only the third is about Android:

1. It is the difference between a design system and a pile of CSS.
2. It shows whether the design survives being decomposed. A direction that needs sixty components to
   express itself is not restraint.
3. A second client implements the same names with the same responsibilities, which is what makes it
   the same product rather than a lookalike.

Keep it honest: a component earns its place by having a second use, the same rule the rest of the
codebase follows.

## What is deliberately not produced

- No shared UI code between web and a future client. Compose and React do not share components, and
  pretending otherwise produces a layer that serves neither.
- No abstraction layer over the tokens. A token is a value with a name.
- No component in the inventory that exists only for symmetry with a platform nobody has built.
