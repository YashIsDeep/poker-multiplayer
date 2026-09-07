import { Link } from 'react-router-dom'
import TrophyIcon from './TrophyIcon'
import '../styles/ranked.css'

export default function LeaderboardLink({ compact = false }: { compact?: boolean }) {
  return (
    <Link
      to="/leaderboard"
      className={`btn ranked-action ranked-leaderboard-button${compact ? ' ranked-leaderboard-compact' : ''}`}
      data-sound="confirm"
      aria-label={compact ? 'View leaderboard' : 'Leaderboard'}
    >
      <span className="ranked-action-icon"><TrophyIcon /></span>
      <span className="ranked-action-copy">
        <span className="ranked-action-title">{compact ? 'View leaderboard' : 'Leaderboard'}</span>
        {!compact && <span className="ranked-action-description">All-time standings</span>}
      </span>
      <span className="ranked-action-arrow" aria-hidden="true">→</span>
    </Link>
  )
}
