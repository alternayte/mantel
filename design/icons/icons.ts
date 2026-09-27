/**
 * Lucide icons as Compose ImageVector source.
 *
 * The app ships with R8 off, so an icon library would ship whole: thousands of glyphs for the dozen
 * the app draws. Instead the SVGs it uses are kept in `design/icons/lucide/`, at the version below,
 * and this writes one Kotlin file from them. The stroke width is not Lucide's: it is `icon.stroke` in
 * design/tokens.json, the value gauntlet run C set, so every icon is drawn at the weight the design
 * chose (DESIGN.md).
 *
 *   bun run design/icons/icons.ts            regenerate the Kotlin
 *   bun run design/icons/icons.ts add <name> fetch one more icon, then regenerate
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'

const LUCIDE = '1.48.0'
const SOURCE = 'design/icons/lucide'
const TARGET = 'android/src/androidMain/kotlin/com/mantel/app/design/Icons.kt'

const [mode, ...names] = process.argv.slice(2)
if (mode === 'add') {
  for (const name of names) {
    const response = await fetch(`https://cdn.jsdelivr.net/npm/lucide-static@${LUCIDE}/icons/${name}.svg`)
    if (!response.ok) throw new Error(`lucide-static ${LUCIDE} has no icon called ${name}`)
    writeFileSync(`${SOURCE}/${name}.svg`, await response.text())
  }
} else if (mode !== undefined) {
  throw new Error(`unknown mode ${mode}: run with no arguments, or with "add <name>"`)
}

const tokens = JSON.parse(readFileSync('design/tokens.json', 'utf8'))
const stroke = Number(tokens.icon.stroke.$value)

type Attributes = Record<string, string>

function attributesOf(tag: string): Attributes {
  const out: Attributes = {}
  for (const match of tag.matchAll(/([a-zA-Z0-9-]+)="([^"]*)"/g)) out[match[1]] = match[2]
  return out
}

const n = (value: string | undefined) => Number(value ?? 0)

/** Every SVG shape Lucide uses, as path data, so the Kotlin needs one kind of node. */
function pathOf(element: string, a: Attributes): string {
  switch (element) {
    case 'path':
      return a.d
    case 'line':
      return `M${n(a.x1)} ${n(a.y1)}L${n(a.x2)} ${n(a.y2)}`
    case 'polyline':
    case 'polygon': {
      const points = a.points.trim().split(/[\s,]+/).map(Number)
      const pairs: string[] = []
      for (let i = 0; i < points.length; i += 2) pairs.push(`${points[i]} ${points[i + 1]}`)
      return `M${pairs.join('L')}${element === 'polygon' ? 'Z' : ''}`
    }
    case 'circle':
      return ellipse(n(a.cx), n(a.cy), n(a.r), n(a.r))
    case 'ellipse':
      return ellipse(n(a.cx), n(a.cy), n(a.rx), n(a.ry))
    case 'rect': {
      const [x, y, w, h] = [n(a.x), n(a.y), n(a.width), n(a.height)]
      const rx = Math.min(n(a.rx ?? a.ry), w / 2)
      const ry = Math.min(n(a.ry ?? a.rx), h / 2)
      if (rx === 0 && ry === 0) return `M${x} ${y}h${w}v${h}h${-w}Z`
      return (
        `M${x + rx} ${y}h${w - 2 * rx}a${rx} ${ry} 0 0 1 ${rx} ${ry}v${h - 2 * ry}` +
        `a${rx} ${ry} 0 0 1 ${-rx} ${ry}h${-(w - 2 * rx)}a${rx} ${ry} 0 0 1 ${-rx} ${-ry}` +
        `v${-(h - 2 * ry)}a${rx} ${ry} 0 0 1 ${rx} ${-ry}Z`
      )
    }
    default:
      throw new Error(`an icon uses <${element}>, which this does not translate`)
  }
}

function ellipse(cx: number, cy: number, rx: number, ry: number): string {
  return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0Z`
}

const pascal = (name: string) => name.replace(/(^|-)([a-z0-9])/g, (_, __, c: string) => c.toUpperCase())

const icons = readdirSync(SOURCE)
  .filter((file) => file.endsWith('.svg'))
  .sort()
  .map((file) => {
    const svg = readFileSync(`${SOURCE}/${file}`, 'utf8')
    const body = svg.slice(svg.indexOf('>', svg.indexOf('<svg')) + 1)
    const paths = [...body.matchAll(/<([a-z]+)\s([^>]*?)\/?>/g)]
      .filter(([, element]) => element !== 'svg')
      .map(([, element, rest]) => pathOf(element, attributesOf(rest)))
    return { name: pascal(file.replace(/\.svg$/, '')), file, paths }
  })

const kt: string[] = [
  `// Generated from design/icons/lucide/ by \`just icons\`. Do not edit. Lucide ${LUCIDE}, ISC (design/icons/LICENSE).`,
  'package com.mantel.app.design',
  '',
  'import androidx.compose.ui.graphics.Color',
  'import androidx.compose.ui.graphics.SolidColor',
  'import androidx.compose.ui.graphics.StrokeCap',
  'import androidx.compose.ui.graphics.StrokeJoin',
  'import androidx.compose.ui.graphics.vector.ImageVector',
  'import androidx.compose.ui.graphics.vector.addPathNodes',
  'import androidx.compose.ui.unit.dp',
  '',
  `/** Every icon the app draws, at the stroke width the design sets (${stroke}). */`,
  'object Icons {',
]
for (const icon of icons) {
  kt.push(`    /** ${icon.file} */`)
  kt.push(`    val ${icon.name}: ImageVector by lazy {`)
  kt.push(`        lucide(`)
  kt.push(`            "${icon.name}",`)
  for (const path of icon.paths) kt.push(`            "${path}",`)
  kt.push('        )')
  kt.push('    }', '')
}
kt[kt.length - 1] = '}'
kt.push(
  '',
  '/** A 24-unit Lucide glyph, stroked and not filled. The colour is a tint applied where it is drawn. */',
  'private fun lucide(',
  '    name: String,',
  '    vararg paths: String,',
  '): ImageVector =',
  '    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)',
  '        .apply {',
  '            paths.forEach { data ->',
  '                addPath(',
  '                    pathData = addPathNodes(data),',
  '                    stroke = SolidColor(Color.White),',
  '                    strokeLineWidth = Tokens.Icon.stroke,',
  '                    strokeLineCap = StrokeCap.Round,',
  '                    strokeLineJoin = StrokeJoin.Round,',
  '                )',
  '            }',
  '        }.build()',
  '',
)
writeFileSync(TARGET, kt.join('\n'))
console.log(`wrote ${icons.length} icons to ${TARGET}`)
