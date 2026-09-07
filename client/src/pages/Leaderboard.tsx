import { useState } from 'react'
import { Link } from 'react-router-dom'
import SwirlBackground from '../components/SwirlBackground'
import { useRankedStats } from '../hooks/useRankedStats'
import { formatSignedBB } from '../lib/ranked'
import type { RankedBoard, RankedPlayer } from '../lib/ranked'
import '../styles/ranked.css'

const PAGE_SIZE = 50

/** Bars stay comparable within a page: scale every row against the widest score on it. */
function peakScore(players: RankedPlayer[]): number {
  return players.reduce((peak, p) => Math.max(peak, Math.abs(p.bbPer100 ?? 0)), 0)
}

function Avatar({ player }: { player: RankedPlayer }) {
  if (player.avatarUrl) return <img className="ranked-avatar" src={player.avatarUrl} alt="" loading="lazy" />
  return <span className="ranked-avatar ranked-avatar-blank" aria-hidden="true">{[...player.displayName][0] ?? '?'}</span>
}

export default function Leaderboard() {
  const [page, setPage] = useState(0)
  const { data, error } = useRankedStats<RankedBoard>(`/api/leaderboard?page=${page}&pageSize=${PAGE_SIZE}`)
  const peak = data ? peakScore(data.players) : 0
  const firstOnPage = page * PAGE_SIZE + 1
  const lastOnPage = Math.min(firstOnPage + (data?.players.length ?? 0) - 1, data?.totalPlayers ?? 0)
  return <div className="stage ranked-stage"><SwirlBackground />
    <main className="ranked-page">
      <nav className="ranked-nav ranked-nav-end"><Link data-sound="confirm" to="/">Play poker →</Link></nav>
      <span className="ranked-eyebrow">THE LEADERBOARD</span>
      <h1>Let the hands speak.</h1>
      <p className="ranked-subtitle">All-time ranked · 3–6 players</p>
      <section className="panel ranked-board">
        <div className="ranked-board-intro">
          <div><h2>Net BB / 100 hands</h2>
            <p className="ranked-muted">Actual earnings per 100 hands. Opponent strength and table size vary; luck can move scores even over many hands.</p></div>
          <span className="ranked-qualify-chip">{(data?.qualifyingHands ?? 1000).toLocaleString()} hands to qualify</span>
        </div>
        {error && <p className="ranked-notice" role="alert">{error}</p>}
        {!data && !error && <div className="ranked-skeleton" role="status" aria-label="Loading leaderboard">
          {Array.from({ length: 6 }, (_, i) => <span key={i} />)}
        </div>}
        {data && data.totalPlayers === 0 && <div className="ranked-empty"><h2>The first spot is waiting.</h2>
          <p>No players have completed 1,000 ranked hands yet. Provisional results appear on player profiles.</p>
          <Link data-sound="confirm" className="btn btn-green" to="/">Find a ranked table</Link></div>}
        {data && data.players.length > 0 && <div className="ranked-table-scroll"><table>
          <thead><tr><th className="ranked-col-rank">Rank</th><th>Player</th><th>BB/100</th><th>Hands</th><th>Net BB</th></tr></thead>
          <tbody>{data.players.map(p => {
            const score = p.bbPer100 ?? 0
            const down = score < 0
            return <tr key={p.userId} className={p.rank !== null && p.rank <= 3 ? `ranked-row ranked-row-podium ranked-row-${p.rank}` : 'ranked-row'}>
              <td className="ranked-col-rank"><span className="ranked-place">{p.rank}</span></td>
              <td className="ranked-col-player"><Link className="ranked-player" data-sound="confirm" to={`/players/${p.userId}`}>
                <Avatar player={p} />
                <span className="ranked-player-name">{p.displayName}
                  <span className="ranked-player-detail">{p.handCount.toLocaleString()} hands</span></span>
              </Link></td>
              <td className="ranked-col-score">
                <span className={down ? 'ranked-negative' : 'ranked-positive'}>{formatSignedBB(p.bbPer100)}</span>
                <span className={`ranked-bar${down ? ' is-down' : ''}`} aria-hidden="true">
                  <span style={{ width: `${peak > 0 ? Math.max(4, (Math.abs(score) / peak) * 100) : 0}%` }} />
                </span>
              </td>
              <td className="ranked-col-dim">{p.handCount.toLocaleString()}</td>
              <td className="ranked-col-dim">{formatSignedBB(p.netBB)}</td>
            </tr>
          })}</tbody>
        </table></div>}
        {data && data.totalPlayers > 0 && <div className="ranked-pagination">
          <button data-sound="confirm" className="btn btn-blue" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>{firstOnPage}–{lastOnPage} of {data.totalPlayers} qualified players</span>
          <button data-sound="confirm" className="btn btn-blue" disabled={(page + 1) * PAGE_SIZE >= data.totalPlayers} onClick={() => setPage(page + 1)}>Next</button>
        </div>}
      </section>
    </main>
  </div>
}
