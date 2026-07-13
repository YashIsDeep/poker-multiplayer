import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { isSoundName } from './sound'

const SOURCE_ROOT = fileURLToPath(new URL('../', import.meta.url))

type OpeningTag = { name: string; source: string; offset: number }

function tsxFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return tsxFiles(fullPath)
    return entry.isFile() && entry.name.endsWith('.tsx') ? [fullPath] : []
  })
}

// Scan JSX opening tags while respecting quoted strings and expression braces,
// so arrow functions and comparison operators inside props cannot end a tag.
function openingTags(source: string): OpeningTag[] {
  const tags: OpeningTag[] = []

  for (let start = 0; start < source.length; start += 1) {
    if (source[start] !== '<' || !/[A-Za-z]/.test(source[start + 1] ?? '')) continue

    let cursor = start + 1
    while (/[A-Za-z0-9_.:-]/.test(source[cursor] ?? '')) cursor += 1
    const name = source.slice(start + 1, cursor)
    let quote: "'" | '"' | '`' | null = null
    let braceDepth = 0

    for (; cursor < source.length; cursor += 1) {
      const character = source[cursor]
      if (quote) {
        if (character === '\\') cursor += 1
        else if (character === quote) quote = null
        continue
      }
      if (character === "'" || character === '"' || character === '`') {
        quote = character
      } else if (character === '{') {
        braceDepth += 1
      } else if (character === '}') {
        braceDepth = Math.max(0, braceDepth - 1)
      } else if (character === '>' && braceDepth === 0) {
        tags.push({ name, source: source.slice(start, cursor + 1), offset: start })
        start = cursor
        break
      }
    }
  }

  return tags
}

describe('semantic button sound coverage', () => {
  it('requires every button, link, and role=button control to declare a valid sound', () => {
    const missing: string[] = []
    const invalid: string[] = []

    for (const file of tsxFiles(SOURCE_ROOT)) {
      const source = readFileSync(file, 'utf8')
      for (const tag of openingTags(source)) {
        const roleButton = /\brole\s*=\s*["']button["']/.test(tag.source)
        if (tag.name !== 'button' && tag.name !== 'a' && !roleButton) continue

        const line = source.slice(0, tag.offset).split('\n').length
        const location = `${path.relative(SOURCE_ROOT, file)}:${line}`
        const soundAttribute = tag.source.match(/\bdata-sound\s*=\s*["']([^"']+)["']/)
        const hasDynamicSound = /\bdata-sound\s*=\s*{/.test(tag.source)

        if (!soundAttribute && !hasDynamicSound) {
          missing.push(location)
        } else if (soundAttribute && !isSoundName(soundAttribute[1])) {
          invalid.push(`${location} (${soundAttribute[1]})`)
        }
      }
    }

    expect(missing, `controls missing data-sound:\n${missing.join('\n')}`).toEqual([])
    expect(invalid, `controls with invalid data-sound:\n${invalid.join('\n')}`).toEqual([])
  })
})
