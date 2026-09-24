// Pure key -> action resolution for the ActionBar keyboard shortcuts, kept
// free of DOM access so it can be unit tested without rendering a component.
// Keymap: F = fold, C = check/call, R = confirm raise, A = all-in,
// 1-4 = jump the raise-to value to the Min/½ Pot/Pot/All-in presets.

import type { LegalActions } from '../store/types'

export type RaisePresets = { min: number; half: number; pot: number; allin: number }

export type ShortcutContext = {
  /** Tag name of document.activeElement, e.g. "INPUT". Null if none/body. */
  activeElementTag: string | null
  /** Whether document.activeElement is contenteditable. */
  activeElementEditable: boolean
  /** Whether a modal (Settings/TableSettings/HandHistory/Showdown/etc) is open. */
  modalOpen: boolean
}

export type ShortcutAction =
  | { type: 'fold' }
  | { type: 'check' }
  | { type: 'call' }
  | { type: 'raiseTo'; amount: number }
  | { type: 'allin'; amount: number }
  | { type: 'selectPreset'; amount: number }

const FOCUSABLE_INPUT_TAGS = new Set(['INPUT', 'TEXTAREA', 'SELECT'])

/** True when the shortcut handler should be suppressed entirely. */
export function isShortcutSuppressed(
  event: Pick<KeyboardEvent, 'ctrlKey' | 'metaKey' | 'altKey'>,
  context: ShortcutContext,
): boolean {
  if (event.ctrlKey || event.metaKey || event.altKey) return true
  if (context.modalOpen) return true
  if (context.activeElementEditable) return true
  if (context.activeElementTag !== null && FOCUSABLE_INPUT_TAGS.has(context.activeElementTag)) {
    return true
  }
  return false
}

/**
 * Resolves a keydown event to a table action, given the player's current
 * legal actions, raise presets, and the raise-to value currently staged on
 * the slider (used by the "R" confirm-raise shortcut). Returns null when the
 * key has no binding, the action is illegal right now, or the event should
 * be suppressed (modifier held, a modal is open, or an input is focused).
 */
export function resolveShortcut(
  event: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey'>,
  context: ShortcutContext,
  legal: LegalActions | null,
  presets: RaisePresets | null,
  currentRaiseValue: number,
): ShortcutAction | null {
  if (legal === null) return null
  if (isShortcutSuppressed(event, context)) return null

  switch (event.key.toLowerCase()) {
    case 'f':
      return legal.canFold ? { type: 'fold' } : null
    case 'c':
      if (legal.canCall) return { type: 'call' }
      if (legal.canCheck) return { type: 'check' }
      return null
    case 'r':
      return legal.canRaise && legal.maxRaiseTo > 0
        ? { type: 'raiseTo', amount: currentRaiseValue }
        : null
    case 'a':
      return legal.canRaise && legal.maxRaiseTo > 0
        ? { type: 'allin', amount: legal.maxRaiseTo }
        : null
    case '1':
      return presets && legal.canRaise ? { type: 'selectPreset', amount: presets.min } : null
    case '2':
      return presets && legal.canRaise ? { type: 'selectPreset', amount: presets.half } : null
    case '3':
      return presets && legal.canRaise ? { type: 'selectPreset', amount: presets.pot } : null
    case '4':
      return presets && legal.canRaise ? { type: 'selectPreset', amount: presets.allin } : null
    default:
      return null
  }
}
