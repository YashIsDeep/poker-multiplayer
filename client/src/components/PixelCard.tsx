// Pixel-style playing card (T7 structure, T8 skin via table.css/animations.css).
// code: 'Kh' face | 'back' face-down | null empty slot.

import { useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { parseCard, suitGlyph } from '../lib/cards'

type PixelCardProps = {
  code: string | null
  size?: 'board' | 'hole'
  win?: boolean
  frame?: 'gold' | 'silver'
  dead?: boolean
  /** T8 deal-stagger entrance delay in ms; undefined = no entrance animation. */
  dealDelayMs?: number
  revealDelayMs?: number
  dealFrom?: { x: number; y: number; rotate?: number }
  motion?: 'muck' | 'cleanup'
}

const REVEAL_ANIMATION_MS = 900

export default function PixelCard({
  code,
  size = 'board',
  win,
  frame,
  dead,
  dealDelayMs,
  revealDelayMs,
  dealFrom,
  motion,
}: PixelCardProps) {
  const previousCode = useRef(code)
  const [revealing, setRevealing] = useState(false)
  useEffect(() => {
    const reveal = previousCode.current === 'back' && code !== null && code !== 'back'
    previousCode.current = code
    if (!reveal) {
      setRevealing(false)
      return
    }

    setRevealing(true)
    const timer = window.setTimeout(
      () => setRevealing(false),
      REVEAL_ANIMATION_MS + (revealDelayMs ?? 0),
    )
    return () => window.clearTimeout(timer)
  }, [code, revealDelayMs])
  // Stable phase avoids screenshot jitter while keeping board cards from moving in lockstep.
  const bobDelay = useMemo(() => {
    const seed = [...(code ?? 'slot')].reduce((total, char) => total + char.charCodeAt(0), 0)
    return `${((seed % 24) / 10).toFixed(1)}s`
  }, [code])
  const sizeClass = size === 'hole' ? 'sm' : ''

  if (code === null) {
    return <span className={`card slot ${sizeClass}`.trim()} />
  }

  const classes = ['card', sizeClass]
  const style: CSSProperties & Record<string, string> = {}
  if (win) classes.push('win')
  if (frame) classes.push(`frame-${frame}`)
  if (dead) classes.push('dead')
  if (motion) classes.push(motion)
  if (revealing) classes.push('reveal')
  if (dealFrom) {
    style['--deal-x'] = `${dealFrom.x}px`
    style['--deal-y'] = `${dealFrom.y}px`
    style['--deal-rotate'] = `${dealFrom.rotate ?? -8}deg`
  }
  if (dealDelayMs !== undefined) {
    classes.push('deal')
    style['--deal-delay'] = `${dealDelayMs}ms`
  } else if (!win && !dead && size === 'board') {
    style.animationDelay = bobDelay
  }
  if (revealing && revealDelayMs !== undefined) {
    style['--reveal-delay'] = `${revealDelayMs}ms`
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
