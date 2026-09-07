import { useEffect, useState } from 'react'
import { RANKED_SETTLED_EVENT } from '../lib/ranked'

/** Refresh visible stats only; cancel stale requests when navigating or settling a hand. */
export function useRankedStats<T>(url: string | null, refreshKey?: string | number | null) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    setData(null)
    setError(null)
    if (!url) return
    const controller = new AbortController()
    let pending = false
    const refresh = async () => {
      if (pending || document.hidden) return
      pending = true
      try {
        const response = await fetch(url, { signal: controller.signal })
        if (!response.ok) throw new Error(response.status === 404 ? 'Player not found.' : 'Statistics are temporarily unavailable.')
        const value = await response.json() as T
        if (!controller.signal.aborted) { setData(value); setError(null) }
      } catch (e) {
        if (!controller.signal.aborted) setError(e instanceof Error ? e.message : 'Unable to load statistics.')
      } finally { pending = false }
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), 15_000)
    const visible = () => { if (!document.hidden) void refresh() }
    document.addEventListener('visibilitychange', visible)
    window.addEventListener(RANKED_SETTLED_EVENT, visible)
    return () => {
      controller.abort()
      clearInterval(timer)
      document.removeEventListener('visibilitychange', visible)
      window.removeEventListener(RANKED_SETTLED_EVENT, visible)
    }
  }, [url, refreshKey])
  return { data, error }
}
