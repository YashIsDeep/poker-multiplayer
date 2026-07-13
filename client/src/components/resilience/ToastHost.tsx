// T10: bottom-right toast stack. Auto-dismiss handled by the store; ✕ dismisses.

import { useEffect, useRef } from 'react'
import { useGameStore } from '../../store/gameStore'
import { playSound } from '../../lib/sound'
import type { ToastType } from '../../store/gameStore'

const TOAST_SOUND: Partial<Record<ToastType, 'error' | 'notify'>> = {
  error: 'error',
  warn: 'error',
  ok: 'notify',
}

export default function ToastHost() {
  const toasts = useGameStore((s) => s.toasts)
  const dismissToast = useGameStore((s) => s.dismissToast)

  // Announce each newly-arrived toast once, by tracking the highest id seen.
  const lastSeenId = useRef(0)
  useEffect(() => {
    for (const toast of toasts) {
      if (toast.id > lastSeenId.current) {
        lastSeenId.current = toast.id
        const sound = TOAST_SOUND[toast.type]
        if (sound) playSound(sound)
      }
    }
  }, [toasts])

  if (toasts.length === 0) return null

  return (
    <div className="toasts" data-testid="toasts">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast ${toast.type}`} role="status">
          <div>
            {toast.message}
            {toast.detail && <small>{toast.detail}</small>}
          </div>
          <span
            className="x"
            role="button"
            tabIndex={0}
            aria-label="Dismiss"
            onClick={() => dismissToast(toast.id)}
            onKeyDown={(e) => e.key === 'Enter' && dismissToast(toast.id)}
          >
            ✕
          </span>
        </div>
      ))}
    </div>
  )
}
