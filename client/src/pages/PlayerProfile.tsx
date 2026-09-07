import { Link, useParams } from 'react-router-dom'
import SwirlBackground from '../components/SwirlBackground'
import { useRankedStats } from '../hooks/useRankedStats'
import { formatBB } from '../lib/ranked'
import type { RankedPlayer } from '../lib/ranked'
import '../styles/ranked.css'

export default function PlayerProfile() {
  const { userId } = useParams()
  const { data: player, error } = useRankedStats<RankedPlayer>(userId ? `/api/players/${encodeURIComponent(userId)}` : null)
  return <div className="stage ranked-stage"><SwirlBackground />
    <main className="ranked-page">
      <nav className="ranked-nav"><Link data-sound="confirm" to="/leaderboard">← Leaderboard</Link><Link data-sound="confirm" to="/">Play poker →</Link></nav>
      <span className="ranked-eyebrow">PLAYER PROFILE</span>
      {error && <p className="panel ranked-board" role="alert">{error}</p>}
      {!player && !error && <p role="status">Loading player…</p>}
      {player && <>
        <h1>{player.displayName}</h1><p className="ranked-subtitle">All-time ranked · 3–6 players</p>
        <section className="panel ranked-board" data-testid="ranked-profile">
          <div className="ranked-board-intro"><h2>{player.eligible ? `Rank #${player.rank}` : 'Provisional player'}</h2>
            <span>{player.handCount.toLocaleString()} ranked hands</span></div>
          <div className="ranked-stats-grid">
            <div><span>BB / 100 hands</span><strong>{formatBB(player.bbPer100)}</strong></div>
            <div><span>Net big blinds</span><strong>{formatBB(player.netBB)}</strong></div>
          </div>
          {!player.eligible && <div className="ranked-progress">
            <label htmlFor="qualification">{player.handCount.toLocaleString()} / 1,000 hands to qualify</label>
            <progress id="qualification" value={player.handCount} max={1000} />
            <p>{(1000 - player.handCount).toLocaleString()} more hands until leaderboard eligibility.</p>
          </div>}
          <p className="ranked-muted">BB/100 measures observed net earnings, including every fold and timeout.
            Buy-ins and rebuys do not count as earnings. This is not an Elo rating or a measure of GTO accuracy.</p>
        </section>
      </>}
    </main>
  </div>
}
