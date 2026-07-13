import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { describeHistoryEvent, fetchHandHistories } from '../lib/handHistory'
import { formatChips, formatChipsFull } from '../lib/chips'
import type { GamePhase, HandHistory } from '../store/types'
import '../styles/hand-history.css'

const STREET_ORDER: GamePhase[] = ['PREFLOP', 'FLOP', 'TURN', 'RIVER', 'COMPLETE']

function signedChips(value: number) {
  if (value === 0) return '—'
  return `${value > 0 ? '+' : '−'}${formatChips(Math.abs(value))}`
}

function seatRole(hand: HandHistory, seat: number) {
  const roles = []
  if (seat === hand.buttonSeat) roles.push('BTN')
  if (seat === hand.smallBlindSeat) roles.push('SB')
  if (seat === hand.bigBlindSeat) roles.push('BB')
  return roles.length > 0 ? ` · ${roles.join('/')}` : ''
}

const SUIT_GLYPHS: Record<string, string> = {
  c: '♣',
  d: '♦',
  h: '♥',
  s: '♠',
}

function cardFace(code: string) {
  const suitCode = code.slice(-1).toLowerCase()
  return {
    rank: code.slice(0, -1).toUpperCase(),
    suit: SUIT_GLYPHS[suitCode] ?? suitCode.toUpperCase(),
    red: suitCode === 'h' || suitCode === 'd',
  }
}

function CardCodes({ cards }: { cards: string[] | null }) {
  if (cards === null) return <span className="history-mucked">mucked</span>
  if (cards.length === 0) return <span className="history-mucked">—</span>
  return (
    <span className="history-cards" aria-label={cards.join(' ')}>
      {cards.map((card) => {
        const face = cardFace(card)

        return (
          <span
            key={card}
            className={`history-card${face.red ? ' red' : ''}`}
            title={card}
          >
            <b>{face.rank}</b>
            <small aria-hidden="true">{face.suit}</small>
          </span>
        )
      })}
    </span>
  )
}

export default function HandHistoryDrawer({
  roomId,
  onClose,
}: {
  roomId: string
  onClose: () => void
}) {
  const [hands, setHands] = useState<HandHistory[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const closeRef = useRef<HTMLButtonElement>(null)

  const load = useCallback(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    fetchHandHistories(roomId, controller.signal)
      .then((nextHands) => {
        setHands(nextHands)
        setSelectedId((current) =>
          current !== null && nextHands.some((hand) => hand.handId === current)
            ? current
            : (nextHands[0]?.handId ?? null),
        )
      })
      .catch((reason: unknown) => {
        if (reason instanceof DOMException && reason.name === 'AbortError') return
        setError(reason instanceof Error ? reason.message : 'Could not load hand histories')
      })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [roomId])

  useEffect(load, [load])
  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const selected = hands.find((hand) => hand.handId === selectedId) ?? null
  const groupedEvents = useMemo(() => {
    if (!selected) return []
    return STREET_ORDER.map((phase) => ({
      phase,
      events: selected.events.filter((event) => event.phase === phase),
    })).filter((group) => group.events.length > 0)
  }, [selected])

  return (
    <div className="history-backdrop" role="presentation" onClick={onClose}>
      <aside
        className="history-drawer panel"
        role="dialog"
        aria-modal="true"
        aria-label="Hand histories"
        data-testid="hand-history"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="history-head">
          <div>
            <span className="label">Table archive</span>
            <h2>Hand histories</h2>
          </div>
          <div className="history-head-actions">
            <button
              className="btn btn-dev"
              type="button"
              data-sound="retry"
              disabled={loading}
              onClick={load}
            >
              Refresh
            </button>
            <button
              ref={closeRef}
              className="btn btn-red history-close"
              type="button"
              data-sound="close"
              aria-label="Close hand histories"
              onClick={onClose}
            >
              ×
            </button>
          </div>
        </header>

        {loading && hands.length === 0 && <div className="history-state">Loading hands…</div>}
        {error && hands.length === 0 && (
          <div className="history-state history-error">
            <b>Could not load the archive.</b>
            <span>{error}</span>
            <button className="btn btn-orange" type="button" data-sound="retry" onClick={load}>
              Try again
            </button>
          </div>
        )}
        {!loading && !error && hands.length === 0 && (
          <div className="history-state">
            <b>No hands yet.</b>
            <span>Completed and in-progress hands will appear here.</span>
          </div>
        )}

        {hands.length > 0 && (
          <div className="history-layout">
            <nav className="history-list" aria-label="Hands">
              {hands.map((hand) => (
                <button
                  key={hand.handId}
                  type="button"
                  className={`history-list-item${hand.handId === selectedId ? ' active' : ''}`}
                  data-sound="open"
                  aria-current={hand.handId === selectedId ? 'true' : undefined}
                  onClick={() => setSelectedId(hand.handId)}
                >
                  <span>
                    <b className="num">#{hand.handId}</b>
                    <small>{new Date(hand.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</small>
                  </span>
                  <em>{hand.completedAt ? hand.completionReason?.replace('_', ' ') : 'live'}</em>
                </button>
              ))}
            </nav>

            {selected && (
              <article className="history-detail">
                <section className="history-summary">
                  <div>
                    <span className="label">Hand</span>
                    <b className="num">#{selected.handId}</b>
                  </div>
                  <div>
                    <span className="label">Blinds</span>
                    <b className="num">{formatChips(selected.smallBlind)} / {formatChips(selected.bigBlind)}</b>
                  </div>
                  <div>
                    <span className="label">Button</span>
                    <b>Seat {selected.buttonSeat + 1}</b>
                  </div>
                  <div>
                    <span className="label">Status</span>
                    <b>{selected.completedAt ? selected.completionReason?.replace('_', ' ') : selected.phase}</b>
                  </div>
                </section>

                <section className="history-board-row">
                  <span className="label">Board</span>
                  <CardCodes cards={selected.board} />
                </section>

                <section className="history-players">
                  <div className="history-section-title label">Players & results</div>
                  {selected.players.map((player) => (
                    <div className="history-player" key={player.userId}>
                      <div className="history-player-name">
                        <b>{player.displayName}</b>
                        <small>Seat {player.seat + 1}{seatRole(selected, player.seat)}</small>
                      </div>
                      <CardCodes cards={player.holeCards} />
                      <span className="num" title={`Started with ${formatChipsFull(player.startingStack)}`}>
                        {formatChips(player.startingStack)} → {formatChips(player.endingStack)}
                      </span>
                      <b className={`num history-net ${player.netChange > 0 ? 'win' : player.netChange < 0 ? 'loss' : ''}`}>
                        {signedChips(player.netChange)}
                      </b>
                      <small className="history-rank">
                        {[
                          player.finalHandRank,
                          player.folded ? 'folded' : null,
                          player.won > 0 ? `won ${formatChips(player.won)}` : null,
                        ].filter(Boolean).join(' · ')}
                      </small>
                    </div>
                  ))}
                </section>

                <section className="history-actions">
                  <div className="history-section-title label">Action log</div>
                  {groupedEvents.map((group) => (
                    <div className="history-street" key={group.phase}>
                      <h3>{group.phase === 'COMPLETE' ? 'Result' : group.phase}</h3>
                      <ol>
                        {group.events.map((event) => (
                          <li key={event.sequence}>
                            <span>{describeHistoryEvent(event, selected)}</span>
                            {event.stackAfter !== null && (
                              <small className="num">{formatChips(event.stackAfter)} behind</small>
                            )}
                          </li>
                        ))}
                      </ol>
                    </div>
                  ))}
                </section>
              </article>
            )}
          </div>
        )}
      </aside>
    </div>
  )
}
