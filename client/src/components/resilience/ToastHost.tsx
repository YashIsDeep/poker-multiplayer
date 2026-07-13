// T10: bottom-right toast stack. Auto-dismiss handled by the store; ✕ dismisses.

import { useGameStore } from '../../store/gameStore'

export default function ToastHost() {
  const toasts = useGameStore((s) => s.toasts)
  const dismissToast = useGameStore((s) => s.dismissToast)

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
