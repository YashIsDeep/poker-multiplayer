// T10: shown on the older tab when the same user opens the game in a newer tab.
// The socket is already closed; "Use here instead" re-grabs the session.

import { useGameStore } from '../../store/gameStore'

export default function TabTakeoverModal() {
  const takenOver = useGameStore((s) => s.takenOver)
  const claimThisTab = useGameStore((s) => s.claimThisTab)

  if (!takenOver) return null

  return (
    <div className="modalback" data-testid="modal-takeover">
      <div className="modal panel">
        <h3>Opened in another tab</h3>
        <p>
          Your game is now active in a newer tab. This one is read-only to avoid double-acting on
          the same seat.
        </p>
        <div className="row">
          <button className="btn btn-blue" data-sound="confirm" onClick={claimThisTab}>
            Use here instead
          </button>
          <button className="btn btn-dev" data-sound="exit" onClick={() => window.close()}>
            Close tab
          </button>
        </div>
      </div>
    </div>
  )
}
