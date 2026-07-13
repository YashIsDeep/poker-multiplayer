// T10: pinned top banner — amber while reconnecting, red when retries are
// exhausted (manual Retry restarts the backoff cycle).

import { useGameStore } from '../../store/gameStore'

export default function ConnectionBanner() {
  const connection = useGameStore((s) => s.connection)
  const readOnly = useGameStore((s) => s.readOnly)
  const manualRetry = useGameStore((s) => s.manualRetry)

  if (readOnly || connection === 'connected' || connection === 'connecting') return null

  if (connection === 'reconnecting') {
    return (
      <div className="connbanner" role="status" data-testid="banner-reconnecting">
        <span className="spin" aria-hidden="true" />
        Reconnecting… holding your seat, controls locked
      </div>
    )
  }

  return (
    <div className="connbanner offline" role="alert" data-testid="banner-offline">
      Offline — couldn&#39;t reach the table
      <button className="retry" data-sound="retry" onClick={manualRetry}>
        Retry
      </button>
    </div>
  )
}
