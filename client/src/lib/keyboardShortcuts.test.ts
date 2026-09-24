import { describe, expect, it } from 'vitest'
import { isShortcutSuppressed, resolveShortcut } from './keyboardShortcuts'
import type { LegalActions } from '../store/types'

const context = (overrides: Partial<Parameters<typeof isShortcutSuppressed>[1]> = {}) => ({
  activeElementTag: null,
  activeElementEditable: false,
  modalOpen: false,
  ...overrides,
})

const key = (k: string, mods: Partial<{ ctrlKey: boolean; metaKey: boolean; altKey: boolean }> = {}) => ({
  key: k,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  ...mods,
})

const presets = { min: 20, half: 60, pot: 120, allin: 200 }

const legalFacingBet = (overrides: Partial<LegalActions> = {}): LegalActions => ({
  canFold: true,
  canCheck: false,
  canCall: true,
  callAmount: 20,
  toCall: 20,
  canRaise: true,
  minRaiseTo: 40,
  maxRaiseTo: 200,
  ...overrides,
})

const legalCheckable = (overrides: Partial<LegalActions> = {}): LegalActions => ({
  canFold: true,
  canCheck: true,
  canCall: false,
  callAmount: 0,
  toCall: 0,
  canRaise: true,
  minRaiseTo: 20,
  maxRaiseTo: 200,
  ...overrides,
})

describe('resolveShortcut', () => {
  it('resolves F to fold when legal', () => {
    expect(resolveShortcut(key('f'), context(), legalFacingBet(), presets, 40)).toEqual({
      type: 'fold',
    })
  })

  it('does not fold when illegal', () => {
    expect(resolveShortcut(key('F'), context(), legalFacingBet({ canFold: false }), presets, 40)).toBeNull()
  })

  it('resolves C to call when facing a bet', () => {
    expect(resolveShortcut(key('c'), context(), legalFacingBet(), presets, 40)).toEqual({
      type: 'call',
    })
  })

  it('resolves C to check when nothing to call', () => {
    expect(resolveShortcut(key('c'), context(), legalCheckable(), presets, 20)).toEqual({
      type: 'check',
    })
  })

  it('resolves R to raiseTo at the currently staged raise value', () => {
    expect(resolveShortcut(key('r'), context(), legalFacingBet(), presets, 75)).toEqual({
      type: 'raiseTo',
      amount: 75,
    })
  })

  it('does not raise when raising is illegal', () => {
    expect(
      resolveShortcut(key('r'), context(), legalFacingBet({ canRaise: false }), presets, 75),
    ).toBeNull()
  })

  it('resolves A to an immediate all-in at maxRaiseTo', () => {
    expect(resolveShortcut(key('a'), context(), legalFacingBet(), presets, 75)).toEqual({
      type: 'allin',
      amount: 200,
    })
  })

  it('does not all-in when raising is illegal', () => {
    expect(
      resolveShortcut(key('a'), context(), legalFacingBet({ canRaise: false }), presets, 75),
    ).toBeNull()
  })

  it.each([
    ['1', presets.min],
    ['2', presets.half],
    ['3', presets.pot],
    ['4', presets.allin],
  ])('resolves %s to the matching preset amount', (digit, amount) => {
    expect(resolveShortcut(key(digit), context(), legalFacingBet(), presets, 75)).toEqual({
      type: 'selectPreset',
      amount,
    })
  })

  it('ignores preset digits when raising is illegal', () => {
    expect(
      resolveShortcut(key('2'), context(), legalFacingBet({ canRaise: false }), presets, 75),
    ).toBeNull()
  })

  it('returns null for unbound keys', () => {
    expect(resolveShortcut(key('x'), context(), legalFacingBet(), presets, 75)).toBeNull()
  })

  it('returns null when legal actions are absent (not your turn)', () => {
    expect(resolveShortcut(key('f'), context(), null, presets, 75)).toBeNull()
  })

  it('ignores the key when a modifier is held', () => {
    expect(
      resolveShortcut(key('f', { ctrlKey: true }), context(), legalFacingBet(), presets, 75),
    ).toBeNull()
    expect(
      resolveShortcut(key('f', { metaKey: true }), context(), legalFacingBet(), presets, 75),
    ).toBeNull()
    expect(
      resolveShortcut(key('f', { altKey: true }), context(), legalFacingBet(), presets, 75),
    ).toBeNull()
  })

  it('ignores the key when a modal is open', () => {
    expect(
      resolveShortcut(key('f'), context({ modalOpen: true }), legalFacingBet(), presets, 75),
    ).toBeNull()
  })

  it('ignores the key when an input element is focused', () => {
    expect(
      resolveShortcut(
        key('f'),
        context({ activeElementTag: 'INPUT' }),
        legalFacingBet(),
        presets,
        75,
      ),
    ).toBeNull()
    expect(
      resolveShortcut(
        key('f'),
        context({ activeElementTag: 'TEXTAREA' }),
        legalFacingBet(),
        presets,
        75,
      ),
    ).toBeNull()
  })

  it('ignores the key when a contenteditable element is focused', () => {
    expect(
      resolveShortcut(
        key('f'),
        context({ activeElementEditable: true }),
        legalFacingBet(),
        presets,
        75,
      ),
    ).toBeNull()
  })
})

describe('isShortcutSuppressed', () => {
  it('is false for a bare key with no focused input and no open modal', () => {
    expect(isShortcutSuppressed(key('f'), context())).toBe(false)
  })

  it('allows Shift as a non-suppressing modifier', () => {
    expect(isShortcutSuppressed({ ...key('f'), shiftKey: true } as never, context())).toBe(false)
  })
})
