/**
 * Generates web/src/styles/tokens.css and the Compose theme from design/tokens.json.
 *
 * The JSON is the source. Two clients read the same file and each emits its own theme (SDD.md 10),
 * which is only possible while the values live somewhere that is not a stylesheet.
 *
 * The CSS is a transcription: every token, unchanged. The Compose file is a translation, because
 * Compose has no viewport units and no CSS functions. A token whose unit does not exist on Android
 * is not guessed at — it is left out and named in a comment at the foot of the file, so the next
 * person sees what the platform could not take rather than a value somebody invented.
 */
import { readFileSync, writeFileSync } from 'node:fs'

type Token = { $value: string; $description?: string }
type Group = Record<string, Token>

const tokens = JSON.parse(readFileSync('design/tokens.json', 'utf8')) as Record<string, Group | string>

const groups = Object.entries(tokens).filter(
  (entry): entry is [string, Group] => !entry[0].startsWith('$') && typeof entry[1] !== 'string',
)

// --- web -------------------------------------------------------------------------------------

const css: string[] = ['/* Generated from design/tokens.json by `just tokens`. Do not edit. */', ':root {']

for (const [group, entries] of groups) {
  css.push(`  /* ${group} */`)
  for (const [name, token] of Object.entries(entries)) {
    css.push(`  --${group}-${name}: ${token.$value};`)
  }
}

css.push('}', '')
writeFileSync('web/src/styles/tokens.css', css.join('\n'))

// --- Compose ---------------------------------------------------------------------------------

/** 1rem is 16px, which is what both clients' type scales are written against. */
const ROOT_FONT_PX = 16

const camel = (name: string) => name.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase())

/** A Kotlin expression for a token, or null when the unit has no meaning on Android. */
function kotlin(value: string): { type: string; expression: string } | null {
  const hex = /^#([0-9a-fA-F]{6})$/.exec(value)
  if (hex) return { type: 'Color', expression: `Color(0xFF${hex[1].toUpperCase()})` }

  const px = /^(-?[\d.]+)px$/.exec(value)
  if (px) return { type: 'Dp', expression: `${Number(px[1])}.dp` }

  const rem = /^(-?[\d.]+)rem$/.exec(value)
  if (rem) {
    const size = Number(rem[1]) * ROOT_FONT_PX
    return { type: 'Dp', expression: `${size}.dp` }
  }

  const ms = /^(\d+)ms$/.exec(value)
  if (ms) return { type: 'Int', expression: `${ms[1]}` }

  const em = /^(-?[\d.]+)em$/.exec(value)
  if (em) return { type: 'TextUnit', expression: `${Number(em[1])}f.em` }

  const bezier = /^cubic-bezier\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*\)$/.exec(value)
  if (bezier) {
    const [a, b, c, d] = bezier.slice(1).map(Number)
    return { type: 'Easing', expression: `CubicBezierEasing(${a}f, ${b}f, ${c}f, ${d}f)` }
  }

  return null
}

/**
 * A length token in the type group is a font size, and a font size scales with the reader's setting
 * while a margin does not. So the type group emits sp and everything else dp.
 */
function asFontSize(expression: string): string {
  return expression.replace(/\.dp$/, '.sp')
}

const kt: string[] = [
  '// Generated from design/tokens.json by `just tokens`. Do not edit.',
  'package com.mantel.app.design',
  '',
  'import androidx.compose.animation.core.CubicBezierEasing',
  'import androidx.compose.animation.core.Easing',
  'import androidx.compose.ui.graphics.Color',
  'import androidx.compose.ui.unit.Dp',
  'import androidx.compose.ui.unit.TextUnit',
  'import androidx.compose.ui.unit.dp',
  'import androidx.compose.ui.unit.em',
  'import androidx.compose.ui.unit.sp',
  '',
  '/** The design, as the same values the web client reads. DESIGN.md is the rationale. */',
  'object Tokens {',
]

const skipped: string[] = []

for (const [group, entries] of groups) {
  const lines: string[] = []
  for (const [name, token] of Object.entries(entries)) {
    const emitted = kotlin(token.$value)
    if (!emitted) {
      skipped.push(`${group}.${name}: ${token.$value}`)
      continue
    }
    const expression = group === 'type' && emitted.type === 'Dp' ? asFontSize(emitted.expression) : emitted.expression
    const type = group === 'type' && emitted.type === 'Dp' ? 'TextUnit' : emitted.type
    if (token.$description) {
      if (lines.length > 0) lines.push('')
      lines.push(`        /** ${token.$description} */`)
    }
    lines.push(`        val ${camel(name)}: ${type} = ${expression}`)
  }
  if (lines.length === 0) continue
  kt.push(`    object ${camel(group).replace(/^./, (c) => c.toUpperCase())} {`)
  kt.push(...lines)
  kt.push('    }', '')
}

kt[kt.length - 1] = '}'

if (skipped.length > 0) {
  kt.push(
    '',
    '// Not on Android, because the unit does not exist here. Compose lays these out in code:',
    ...skipped.map((entry) => `//   ${entry}`),
  )
}

kt.push('')
writeFileSync('android/src/androidMain/kotlin/com/mantel/app/design/Tokens.kt', kt.join('\n'))

// The window background is painted by the platform before any Compose code runs, so the colours
// have to exist as Android resources too. Same source, so the launch frame cannot drift.
const xml: string[] = ['<!-- Generated from design/tokens.json by `just tokens`. Do not edit. -->', '<resources>']
for (const [group, entries] of groups) {
  for (const [name, token] of Object.entries(entries)) {
    if (!/^#[0-9a-fA-F]{6}$/.test(token.$value)) continue
    xml.push(`    <color name="${group}_${name.replace(/-/g, '_')}">${token.$value}</color>`)
  }
}
xml.push('</resources>', '')
writeFileSync('android/src/androidMain/res/values/tokens.xml', xml.join('\n'))

console.log('wrote web/src/styles/tokens.css, the Compose theme and the Android colour resources')
