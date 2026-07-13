// Exponential backoff scheduler for socket reconnects (T10).
// Schedule: 500ms, 1s, 2s, 4s, 8s, 16s, 30s (cap). After MAX_ATTEMPTS failed
// attempts the caller should transition to 'offline' and wait for manual retry.

export const INITIAL_DELAY_MS = 500
export const MAX_DELAY_MS = 30_000
export const MAX_ATTEMPTS = 8

export function delayForAttempt(attempt: number): number {
  // attempt is 1-based
  const delay = INITIAL_DELAY_MS * 2 ** (attempt - 1)
  return Math.min(delay, MAX_DELAY_MS)
}

export type BackoffController = {
  /** 0 when idle; count of attempts started since last reset. */
  readonly attempts: number
  /** Schedule the next attempt. Returns false when attempts are exhausted. */
  schedule(fn: () => void): boolean
  /** Cancel any pending attempt without resetting the attempt counter. */
  cancel(): void
  /** Cancel and reset the schedule to the initial delay. */
  reset(): void
}

export function createBackoff(
  setTimer: typeof setTimeout = setTimeout,
  clearTimer: typeof clearTimeout = clearTimeout,
): BackoffController {
  let attempts = 0
  let handle: ReturnType<typeof setTimeout> | null = null

  return {
    get attempts() {
      return attempts
    },
    schedule(fn: () => void): boolean {
      if (attempts >= MAX_ATTEMPTS) return false
      attempts += 1
      const delay = delayForAttempt(attempts)
      if (handle !== null) clearTimer(handle)
      handle = setTimer(() => {
        handle = null
        fn()
      }, delay)
      return true
    },
    cancel() {
      if (handle !== null) {
        clearTimer(handle)
        handle = null
      }
    },
    reset() {
      this.cancel()
      attempts = 0
    },
  }
}
