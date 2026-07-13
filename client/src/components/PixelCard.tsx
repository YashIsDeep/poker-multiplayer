// Pixel-style playing card (T7 structure, T8 skin via table.css/animations.css).
// code: 'Kh' face | 'back' face-down | null empty slot.

import { useMemo } from 'react'
import type { CSSProperties } from 'react'
import { parseCard, suitGlyph } from '../lib/cards'

type PixelCardProps = {
  code: string | null
  size?: 'board' | 'hole'
  win?: boolean
  dead?: boolean
  /** T8 deal-stagger entrance delay in ms; undefined = no entrance animation. */
  dealDelayMs?: number
}

export default function PixelCard({ code, size = 'board', win, dead, dealDelayMs }: PixelCardProps) {
  // random bob phase per mounted card, stable across re-renders
  const bobDelay = useMemo(() => `${(Math.random() * 2.4).toFixed(2)}s`, [])
  const sizeClass = size === 'hole' ? 'sm' : ''

  if (code === null) {
    return <span className={`card slot ${sizeClass}`.trim()} />
  }

  const classes = ['card', sizeClass]
  const style: CSSProperties & Record<string, string> = {}
  if (win) classes.push('win')
  if (dead) classes.push('dead')
  if (dealDelayMs !== undefined) {
    classes.push('deal')
    style['--deal-delay'] = `${dealDelayMs}ms`
  } else if (!win && !dead && size === 'board') {
    style.animationDelay = bobDelay
  }

  if (code === 'back') {
    classes.push('back')
    return <span className={classes.join(' ').trim()} style={style} />
  }

  const parsed = parseCard(code)
  if (!parsed) {
    return <span className={`card slot ${sizeClass}`.trim()}>?</span>
  }
  if (parsed.red) classes.push('r')

  return (
    <span className={classes.join(' ').trim()} style={style} data-card={code}>
      <span className="c-rank">{parsed.rank}</span>
      <span className="c-suit">{suitGlyph(parsed.suit)}</span>
    </span>
  )
}
