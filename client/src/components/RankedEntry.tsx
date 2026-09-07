import { useState } from 'react'
import { useGameStore } from '../store/gameStore'
import RankIcon from './RankIcon'
import LeaderboardLink from './LeaderboardLink'
import '../styles/ranked.css'

export default function RankedEntry() {
  const queue = useGameStore(s => s.rankedQueue)
  const connection = useGameStore(s => s.connection)
  const join = useGameStore(s => s.joinRanked)
  const cancel = useGameStore(s => s.cancelRanked)
  const toast = useGameStore(s => s.pushToast)
  const [pendingAction, setPendingAction] = useState<'join' | 'cancel' | null>(null)
  const busy = pendingAction !== null
  const queued = queue?.status === 'QUEUED'
  const assigned = queue?.status === 'ASSIGNED'
  const searching = busy || queued || assigned
  const reconnecting = searching && connection !== 'connected'
  const canCancel = queued && !busy && !reconnecting
  const actionLabel = reconnecting ? 'Reconnecting…'
    : pendingAction === 'cancel' ? 'Leaving queue…'
    : assigned ? 'Table found!'
    : queued ? 'Finding a table…'
    : busy ? 'Joining queue…' : 'Play Ranked'
  const status = reconnecting ? 'Restoring your queue connection…'
    : pendingAction === 'cancel' ? 'Cancelling your ranked search…'
    : assigned ? 'Your seat is ready. Opening the table…'
    : queued ? `Queue position ${queue.position} · ${queue.waitingPlayers} waiting`
    : busy ? 'Sending your ranked request…'
    : queue && !queue.enabled ? 'Ranked is currently unavailable.' : '3–6 players · 100 BB buy-in'
  const act = async () => {
    setPendingAction(queued ? 'cancel' : 'join')
    try { await (queued ? cancel() : join()) }
    catch { toast('warn', 'Ranked request failed', 'Reconnect and try again.') }
    finally { setPendingAction(null) }
  }
  return <section className="ranked-entry" aria-label="Ranked play">
    <div className="ranked-entry-controls">
      <button data-sound="confirm" className={`btn ranked-action ranked-play-button${searching ? ' is-searching' : ''}`} onClick={() => void act()}
        aria-label={canCancel ? 'Cancel queue' : actionLabel} aria-busy={busy || assigned}
        aria-describedby="ranked-queue-status"
        disabled={busy || assigned || connection !== 'connected' || (!queued && !queue?.enabled)}>
        <span className={`ranked-action-icon${searching ? ' ranked-search-icon' : ''}`}><RankIcon /></span>
        <span className="ranked-action-copy">
          <span className="ranked-action-heading">
            <span className="ranked-action-title">{actionLabel}</span>
            {!searching && <span className="ranked-beta-badge" aria-hidden="true">Beta</span>}
          </span>
          <span className="ranked-action-description">{canCancel
            ? <span className="ranked-cancel-label"><span aria-hidden="true">×</span> Cancel queue</span>
            : reconnecting ? 'Your queue status will update shortly'
            : pendingAction === 'cancel' ? 'Finishing your request'
            : assigned ? 'Taking you to your seat'
            : searching ? 'Getting you ready to play' : 'Find your next table'}</span>
        </span>
        {!searching && <span className="ranked-action-arrow" aria-hidden="true">→</span>}
      </button>
      <LeaderboardLink />
    </div>
    <div className="ranked-entry-footer">
      <p id="ranked-queue-status" className={`ranked-queue-status${searching ? ' is-queued' : ''}`} role="status" aria-atomic="true">{status}</p>
      <details className="ranked-help" onKeyDown={event => {
        if (event.key === 'Escape') {
          event.currentTarget.open = false
          event.currentTarget.querySelector('summary')?.focus()
        }
      }}>
        <summary aria-label="Ranked rules"><span aria-hidden="true">?</span>Ranked rules</summary>
        <div className="ranked-help-content panel">
          <h3>Ranked rules</h3>
          <p>3–6 players · 100 BB buy-in · 30 seconds per turn.</p>
          <p>Stacks carry forward. Manual rebuys add 100 BB after a five-hand wait;
            priority offers can unlock sooner when the table needs players.</p>
          <p>Ranked results use net BB/100. Qualify for the leaderboard at 1,000 hands.
            Folds and timeouts count; buy-ins and rebuys add no earnings.</p>
          <p className="ranked-muted">Observed results vary with luck, table size and opponent strength.</p>
        </div>
      </details>
    </div>
  </section>
}
