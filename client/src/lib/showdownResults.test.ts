import { describe, expect, it } from 'vitest'
import type { Game } from '../store/types'
import {
  isCompletedHandResult,
  showdownCardFrame,
  visibleResultHoleCards,
} from './showdownResults'

const awardedHand: Game['awards'] = [{ potIndex: 0, potAmount: 20, winners: [] }]

function completedHand(completionReason: NonNullable<Game['completionReason']>) {
  return { phase: 'COMPLETE' as const, completionReason, awards: awardedHand }
}

describe('isCompletedHandResult', () => {
  it('accepts both showdown and fold-win results', () => {
    expect(isCompletedHandResult(completedHand('SHOWDOWN'))).toBe(true)
    expect(isCompletedHandResult(completedHand('FOLD_WIN'))).toBe(true)
  })

  it('does not treat an active or unsettled hand as a result', () => {
    expect(
      isCompletedHandResult({ phase: 'RIVER', completionReason: null, awards: awardedHand }),
    ).toBe(false)
    expect(
      isCompletedHandResult({ phase: 'COMPLETE', completionReason: null, awards: [] }),
    ).toBe(false)
    expect(isCompletedHandResult(null)).toBe(false)
  })
})

describe('visibleResultHoleCards', () => {
  it('hides cards for a folded/mucked player', () => {
    expect(
      visibleResultHoleCards({ seat: 1, seatStatus: 'FOLDED', holeCards: ['As', 'Kd'] }, 0, true),
    ).toBeNull()
  })

  it('keeps the viewer cards visible after the viewer folds', () => {
    expect(
      visibleResultHoleCards({ seat: 0, seatStatus: 'FOLDED', holeCards: ['As', 'Kd'] }, 0, true),
    ).toEqual(['As', 'Kd'])
  })

  it('keeps non-folded cards available when the viewer is allowed to see them', () => {
    expect(
      visibleResultHoleCards({ seat: 1, seatStatus: 'ACTIVE', holeCards: ['As', 'Kd'] }, 0, true),
    ).toEqual(['As', 'Kd'])
  })

  it('shows face-down cards for a showdown participant whose cards are redacted', () => {
    expect(
      visibleResultHoleCards({ seat: 1, seatStatus: 'ACTIVE', holeCards: null }, 0, true),
    ).toEqual(['back', 'back'])
  })

  it('does not show face-down cards for a non-showdown result', () => {
    expect(
      visibleResultHoleCards({ seat: 1, seatStatus: 'ACTIVE', holeCards: null }, 0, false),
    ).toBeNull()
  })
})

describe('showdownCardFrame', () => {
  it('frames board cards gold and player cards silver', () => {
    const board = ['6d', 'Th', 'Qh', 'Qs', '7c']
    const player = ['Kc', 'Qc']

    expect(showdownCardFrame('Qh', board, player)).toBe('gold')
    expect(showdownCardFrame('Qc', board, player)).toBe('silver')
    expect(showdownCardFrame('As', board, player)).toBeUndefined()
  })

  it('prefers the player frame when card sets overlap', () => {
    expect(showdownCardFrame('Qc', ['Qc'], ['Qc'])).toBe('silver')
  })
})
