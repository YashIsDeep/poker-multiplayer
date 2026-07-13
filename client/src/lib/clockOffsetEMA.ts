// Server-clock offset tracking. Every snapshot carries serverNow; we smooth the
// (server - client) delta with an EMA so countdowns tick against the server's
// clock without jittering on network latency.

export type ClockOffsetEMA = {
  smoothedOffsetMS: number
  alpha: number
  samples: number
}

export function createEMA(alpha = 0.1): ClockOffsetEMA {
  return { smoothedOffsetMS: 0, alpha, samples: 0 }
}

/**
 * Feed one serverNow observation. The first sample snaps the offset (no smoothing)
 * so a fresh page doesn't spend seconds converging from zero.
 * Returns the updated smoothed offset in ms.
 */
export function updateEMA(ema: ClockOffsetEMA, serverNowMS: number, clientNowMS: number): number {
  const rawOffset = serverNowMS - clientNowMS
  if (ema.samples === 0) {
    ema.smoothedOffsetMS = rawOffset
  } else {
    ema.smoothedOffsetMS += ema.alpha * (rawOffset - ema.smoothedOffsetMS)
  }
  ema.samples += 1
  return ema.smoothedOffsetMS
}

export function getServerNow(ema: ClockOffsetEMA, clientNowMS: number): number {
  return clientNowMS + ema.smoothedOffsetMS
}
