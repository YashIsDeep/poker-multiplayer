import { describe, expect, it } from 'vitest'
import { isNewerTab } from './tabArbiter'

describe('isNewerTab', () => {
  it('prefers the later connection timestamp', () => {
    expect(isNewerTab(101, 'tab-a', 100, 'tab-z')).toBe(true)
    expect(isNewerTab(99, 'tab-z', 100, 'tab-a')).toBe(false)
  })

  it('uses the tab id as a deterministic same-millisecond tie-breaker', () => {
    expect(isNewerTab(100, 'tab-z', 100, 'tab-a')).toBe(true)
    expect(isNewerTab(100, 'tab-a', 100, 'tab-z')).toBe(false)
  })
})
