import { describe, expect, it } from 'vitest'
import { createEMA, getServerNow, updateEMA } from './clockOffsetEMA'

describe('clockOffsetEMA', () => {
  it('snaps to the first sample instead of converging from zero', () => {
    const ema = createEMA(0.1)
    updateEMA(ema, 1_000_000, 0) // server is 1,000,000ms ahead
    expect(ema.smoothedOffsetMS).toBe(1_000_000)
    expect(getServerNow(ema, 500)).toBe(1_000_500)
  })

  it('smooths subsequent jitter with the EMA weight', () => {
    const ema = createEMA(0.1)
    updateEMA(ema, 5_000, 0) // offset 5000
    updateEMA(ema, 6_100, 1_000) // raw offset 5100 → +10% of delta
    expect(ema.smoothedOffsetMS).toBeCloseTo(5_010, 5)
  })

  it('converges toward a stable offset over many samples', () => {
    const ema = createEMA(0.1)
    const trueOffset = 2_500
    let clientNow = 0
    updateEMA(ema, clientNow + trueOffset, clientNow)
    for (let i = 0; i < 100; i++) {
      clientNow += 1_000
      // ±40ms network jitter around the true offset
      const jitter = i % 2 === 0 ? 40 : -40
      updateEMA(ema, clientNow + trueOffset + jitter, clientNow)
    }
    expect(Math.abs(ema.smoothedOffsetMS - trueOffset)).toBeLessThan(50)
  })

  it('tracks countdown agreement within tolerance over 30s of drifting samples', () => {
    // Acceptance criterion: countdown agrees with the server deadline within
    // ±2s over 30 seconds.
    const ema = createEMA(0.1)
    const trueOffset = -1_200
    for (let t = 0; t <= 30_000; t += 1_000) {
      updateEMA(ema, t + trueOffset + (t % 3_000 === 0 ? 80 : -80), t)
      const deadline = 60_000 + trueOffset // fixed server-time deadline
      const remaining = deadline - getServerNow(ema, t)
      const trueRemaining = deadline - (t + trueOffset)
      expect(Math.abs(remaining - trueRemaining)).toBeLessThan(2_000)
    }
  })
})
