/**
 * Generates web/src/styles/tokens.css from design/tokens.json.
 *
 * The JSON is the source. A second client reads the same file and emits its own theme (SDD.md 10),
 * which is only possible while the values live somewhere that is not a stylesheet.
 */
import { readFileSync, writeFileSync } from 'node:fs'

type Token = { $value: string; $description?: string }
type Group = Record<string, Token>

const tokens = JSON.parse(readFileSync('design/tokens.json', 'utf8')) as Record<string, Group | string>

const lines: string[] = [
  '/* Generated from design/tokens.json by `just tokens`. Do not edit. */',
  ':root {',
]

for (const [group, entries] of Object.entries(tokens)) {
  if (group.startsWith('$') || typeof entries === 'string') continue
  lines.push(`  /* ${group} */`)
  for (const [name, token] of Object.entries(entries)) {
    lines.push(`  --${group}-${name}: ${token.$value};`)
  }
}

lines.push('}', '')
writeFileSync('web/src/styles/tokens.css', lines.join('\n'))
console.log(`wrote web/src/styles/tokens.css`)
