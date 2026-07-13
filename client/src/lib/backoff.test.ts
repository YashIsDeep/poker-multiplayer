import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createBackoff, delayForAttempt, INITIAL_DELAY_MS, MAX_ATTEMPTS, MAX_DELAY_MS } from './backoff'

describe('delayForAttempt', () => {
  it('follows the 500ms → 30s doubling schedule with a cap', () => {
    expect(delayForAttempt(1)).toBe(500)
    expect(delayForAttempt(2)).toBe(1_000)
    expect(delayForAttempt(3)).toBe(2_000)
    expect(delayForAttempt(4)).toBe(4_000)
    expect(delayForAttempt(5)).toBe(8_000)
    expect(delayForAttempt(6)).toBe(16_000)
    expect(delayForAttempt(7)).toBe(30_000)
    expect(delayForAttempt(8)).toBe(30_000)
    expect(delayForAttempt(20)).toBe(MAX_DELAY_MS)
  })
})

describe('createBackoff', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('fires the scheduled attempt after the exponential delay', () => {
    const backoff = createBackoff()
    const fn = vi.fn()
    expect(backoff.schedule(fn)).toBe(true)
    vi.advanceTimersByTime(INITIAL_DELAY_MS - 1)
    expect(fn).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(fn).toHaveBeenCalledOnce()

    expect(backoff.schedule(fn)).toBe(true)
    vi.advanceTimersByTime(1_000)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('refuses to schedule beyond MAX_ATTEMPTS until reset', () => {
    const backoff = createBackoff()
    for (let i = 0; i < MAX_ATTEMPTS; i++) {
      expect(backoff.schedule(() => {})).toBe(true)
    }
    expect(backoff.schedule(() => {})).toBe(false)
    backoff.reset()
    expect(backoff.schedule(() => {})).toBe(true)
  })

  it('cancel stops the pending attempt without resetting the counter', () => {
    const backoff = createBackoff()
    const fn = vi.fn()
    backoff.schedule(fn)
    backoff.cancel()
    vi.advanceTimersByTime(60_000)
    expect(fn).not.toHaveBeenCalled()
    expect(backoff.attempts).toBe(1)
  })
})
