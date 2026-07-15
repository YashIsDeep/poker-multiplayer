// Runs in the node test environment: there is no window/AudioContext, so this
// covers the preference API and subscription behaviour the settings modal
// drives. Actual audio scheduling + localStorage persistence are verified in
// the browser (localStorage round-trips {muted,volume}); here play() must
// simply no-op safely without an AudioContext.

import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  bindGlobalClickSound,
  isSoundName,
  soundEngine,
  soundForClick,
  playSound,
} from './sound'

function annotatedClick(sound: string): Event {
  const event = new Event('click')
  Object.defineProperty(event, 'composedPath', {
    value: () => [{ getAttribute: (name: string) => (name === 'data-sound' ? sound : null) }],
  })
  return event
}

function unannotatedControlClick(): Event {
  const event = new Event('click')
  Object.defineProperty(event, 'composedPath', {
    value: () => [{ tagName: 'BUTTON', getAttribute: () => null }],
  })
  return event
}

function rangeControlClick(): Event {
  const event = new Event('click')
  Object.defineProperty(event, 'composedPath', {
    value: () => [{
      tagName: 'INPUT',
      getAttribute: (name: string) => (name === 'data-sound-input' ? 'tick' : null),
    }],
  })
  return event
}

afterEach(() => {
  vi.restoreAllMocks()
  // reset to defaults so tests don't leak state between cases
  soundEngine.setMuted(false)
  soundEngine.setVolume(0.7)
})

describe('soundEngine preferences', () => {
  it('accepts only owned recipe names', () => {
    expect(isSoundName('flop')).toBe(true)
    expect(isSoundName('toString')).toBe(false)
    expect(isSoundName(null)).toBe(false)
  })

  it('keeps modal surfaces quiet without swallowing nested controls', () => {
    const boundary = {
      getAttribute: (name: string) => (name === 'data-sound-boundary' ? '' : null),
    }
    const surfaceClick = new Event('click')
    Object.defineProperty(surfaceClick, 'composedPath', { value: () => [boundary] })
    expect(soundForClick(surfaceClick)).toBeNull()

    const inputClick = new Event('click')
    Object.defineProperty(inputClick, 'composedPath', {
      value: () => [{ tagName: 'INPUT', getAttribute: () => null }, boundary],
    })
    expect(soundForClick(inputClick)).toBe('click')
  })

  it('defaults to unmuted at a sensible volume', () => {
    expect(soundEngine.isMuted()).toBe(false)
    expect(soundEngine.getVolume()).toBeGreaterThan(0)
    expect(soundEngine.getVolume()).toBeLessThanOrEqual(1)
  })

  it('toggles and sets mute', () => {
    soundEngine.setMuted(true)
    expect(soundEngine.isMuted()).toBe(true)
    soundEngine.toggleMuted()
    expect(soundEngine.isMuted()).toBe(false)
  })

  it('clamps volume into [0, 1]', () => {
    soundEngine.setVolume(2)
    expect(soundEngine.getVolume()).toBe(1)
    soundEngine.setVolume(-1)
    expect(soundEngine.getVolume()).toBe(0)
    soundEngine.setVolume(0.42)
    expect(soundEngine.getVolume()).toBeCloseTo(0.42)
  })

  it('notifies subscribers on change and stops after unsubscribe', () => {
    const fn = vi.fn()
    const unsubscribe = soundEngine.subscribe(fn)
    soundEngine.setVolume(0.3)
    soundEngine.setMuted(true)
    expect(fn).toHaveBeenCalled()

    fn.mockClear()
    unsubscribe()
    soundEngine.setVolume(0.9)
    expect(fn).not.toHaveBeenCalled()
  })

  it('does not fire redundant mute notifications', () => {
    soundEngine.setMuted(false)
    const fn = vi.fn()
    soundEngine.subscribe(fn)
    soundEngine.setMuted(false) // already false → no-op
    expect(fn).not.toHaveBeenCalled()
  })

  it('play() is a safe no-op without a Web Audio context', () => {
    expect(() => playSound('win')).not.toThrow()
    soundEngine.setMuted(true)
    expect(() => playSound('click')).not.toThrow()
  })

  it('plays one semantic sound per control click and keeps blank surfaces quiet', () => {
    const target = new EventTarget()
    const play = vi.spyOn(soundEngine, 'play').mockImplementation(() => {})
    const unbind = bindGlobalClickSound(target)

    target.dispatchEvent(annotatedClick('confirm'))
    target.dispatchEvent(new Event('click'))
    target.dispatchEvent(unannotatedControlClick())
    target.dispatchEvent(rangeControlClick())

    expect(play).toHaveBeenCalledTimes(2)
    expect(play).toHaveBeenNthCalledWith(1, 'confirm')
    expect(play).toHaveBeenNthCalledWith(2, 'click')

    unbind()
    target.dispatchEvent(annotatedClick('exit'))
    expect(play).toHaveBeenCalledTimes(2)
  })
})
