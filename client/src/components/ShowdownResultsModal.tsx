import { useEffect, useRef } from 'react'
import PixelCard from './PixelCard'
import TrophyIcon from './TrophyIcon'
import { formatChips } from '../lib/chips'
import { playSound } from '../lib/sound'
import {
  showdownCardFrame,
  visibleResultHoleCards,
  type ShowdownCardFrame,
} from '../lib/showdownResults'
import type { Game, GameSeat, RoomSnapshot } from '../store/types'
import '../styles/showdown-results.css'

type ShowdownResultsModalProps = {
  room: RoomSnapshot
  game: Game | null
  onClose: () => void
}

function CardStrip({
  cards,
  size,
  frame,
  frameForCard,
}: {
  cards: string[] | null
  size: 'board' | 'hole'
  frame?: ShowdownCardFrame
  frameForCard?: (card: string) => ShowdownCardFrame | undefined
}) {
  if (!cards || cards.length === 0) {
    return <span className="showdown-results__missing">—</span>
  }

  return (
    <div className={`showdown-results__cards showdown-results__cards--${size}`}>
      {cards.map((card, index) => (
        <PixelCard
          key={`${card}-${index}`}
          code={card}
          size={size}
          frame={frameForCard?.(card) ?? frame}
        />
      ))}
    </div>
  )
}

function nameForSeat(room: RoomSnapshot, gameSeat: GameSeat): string {
  return room.members.find((member) => member.userId === gameSeat.userId)?.displayName ??
    `Player ${gameSeat.seat + 1}`
}

function participating(gameSeat: GameSeat): boolean {
  return gameSeat.position !== null || gameSeat.holeCards !== null || gameSeat.finalHandRank !== null
}

export default function ShowdownResultsModal({ room, game, onClose }: ShowdownResultsModalProps) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        playSound('close')
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const awardsBySeat = new Map<number, number>()
  for (const award of game?.awards ?? []) {
    for (const winner of award.winners) {
      awardsBySeat.set(winner.seat, (awardsBySeat.get(winner.seat) ?? 0) + winner.amount)
    }
  }

  const players = game?.seats.filter(participating) ?? []
  const foldWin = game?.completionReason === 'FOLD_WIN'
  const winnerNames = players
    .filter((seat) => awardsBySeat.has(seat.seat))
    .map((seat) => nameForSeat(room, seat))

  return (
    <div
      className="showdown-results-backdrop"
      data-sound="close"
      role="presentation"
      onClick={onClose}
    >
      <section
        id="table-showdown-results"
        className="showdown-results panel"
        data-sound-boundary=""
        role="dialog"
        aria-modal="true"
        aria-label="Last hand results"
        data-testid="showdown-results"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="showdown-results__header">
          <div className="showdown-results__title">
            <div className="showdown-results__kicker">
              <TrophyIcon />
              <span>LAST HAND BREAKDOWN</span>
            </div>
            <h2>{foldWin ? 'Hand results' : 'Showdown results'}</h2>
            <p>
              {game ? `Hand #${game.handId} · ${winnerNames.join(' & ') || 'No winner recorded'}` : 'No completed hand saved yet'}
            </p>
          </div>
          <button
            ref={closeRef}
            type="button"
            className="btn btn-red showdown-results__close"
            data-sound="close"
            aria-label="Close last hand results"
            onClick={onClose}
          >
            ×
          </button>
        </header>

        {!game ? (
          <div className="showdown-results__empty">
            <b>No completed hand yet.</b>
            <span>The trophy will keep the latest completed hand available here.</span>
          </div>
        ) : (
          <div className="showdown-results__body">
            <section className="showdown-results__board" aria-label="Board cards">
              <div className="showdown-results__section-head">
                <span className="label">Board</span>
                <small>shared cards</small>
              </div>
              <CardStrip cards={game.board} size="board" frame="gold" />
            </section>

            <section className="showdown-results__players" aria-label="Player results">
              <div className="showdown-results__section-head">
                <span className="label">Players</span>
                <small>player outcomes</small>
              </div>

              {players.map((player) => {
                const won = awardsBySeat.has(player.seat)
                const amount = awardsBySeat.get(player.seat) ?? 0
                const folded = player.seatStatus === 'FOLDED'
                const final = player.finalHandRank ?? (
                  folded ? 'Folded' : foldWin && won ? 'Won by fold' : 'Not available'
                )
                const bestCombo = folded ? null : player.bestHandCards?.slice(0, 5) ?? null
                return (
                  <article
                    key={player.seat}
                    className={`showdown-results__player${won ? ' showdown-results__player--winner' : ''}`}
                    data-testid={`showdown-result-player-${player.seat}`}
                  >
                    <header className="showdown-results__player-head">
                      <div>
                        <b>{nameForSeat(room, player)}</b>
                        <small>Seat {player.seat + 1}</small>
                      </div>
                      <strong className={won ? 'showdown-results__status--won' : 'showdown-results__status--lost'}>
                        {won ? `WON${amount > 0 ? ` · +${formatChips(amount)}` : ''}` : folded ? 'FOLDED' : 'LOST'}
                      </strong>
                    </header>

                    <div className="showdown-results__player-grid">
                      <div className="showdown-results__field">
                        <span className="label">Cards</span>
                        <CardStrip
                          cards={visibleResultHoleCards(
                            player,
                            game.viewer.seat,
                            game.completionReason === 'SHOWDOWN',
                          )}
                          size="hole"
                          frame="silver"
                        />
                      </div>
                      <div className="showdown-results__field">
                        <span className="label">Best combo (Top 5)</span>
                        <CardStrip
                          cards={bestCombo}
                          size="hole"
                          frameForCard={(card) =>
                            showdownCardFrame(card, game.board, player.holeCards)
                          }
                        />
                      </div>
                    </div>

                    <div className="showdown-results__final">
                      <span className="label">Final</span>
                      <b>{final}</b>
                    </div>
                  </article>
                )
              })}
            </section>
          </div>
        )}
      </section>
    </div>
  )
}
