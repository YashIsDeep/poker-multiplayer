// The green-felt stadium table (T7 layout from tasks/task7/design/table-green.html).
// Superellipse projection pushes seats onto the stadium rim; your seat is rotated
// to bottom-center. Board, pot, committed bet chips render at center/inner curve.

import { useMemo } from 'react'
import PixelCard from './PixelCard'
import SeatPod from './SeatPod'
import { useGameStore } from '../store/gameStore'
import { playSound } from '../lib/sound'
import type { AnimationEvents } from '../hooks/useAnimationQueue'
import type { Game, RoomSnapshot } from '../store/types'

const K = 0.72 // superellipse power

function superellipse(t: number, radius: number): number {
  return radius * Math.sign(t) * Math.pow(Math.abs(t), K)
}

export function seatPosition(viewIndex: number, totalSeats: number) {
  const ang = Math.PI / 2 + (viewIndex * 2 * Math.PI) / totalSeats
  const cos = Math.cos(ang)
  const sin = Math.sin(ang)
  // Dense 8/9-seat tables need a tighter rim so pods do not collide with the
  // top bar or viewport edges. Smaller tables can use the more dramatic oval.
  const rimX = totalSeats >= 8 ? 43 : 46
  const rimY = totalSeats >= 8 ? 44 : 56
  return {
    x: 50 + superellipse(cos, rimX),
    y: 50 + superellipse(sin, rimY),
    betX: 50 + superellipse(cos, 30),
    betY: 50 + superellipse(sin, 36),
  }
}

type TableProps = {
  room: RoomSnapshot
  game: Game | null
  animations: AnimationEvents
}

const PHASE_LABEL: Record<string, string> = {
  PREFLOP: 'preflop',
  FLOP: 'flop',
  TURN: 'turn',
  RIVER: 'river',
  COMPLETE: 'complete',
}

export default function Table({ room, game, animations }: TableProps) {
  const takeSeat = useGameStore((s) => s.takeSeat)
  const pushToast = useGameStore((s) => s.pushToast)
  const yourSeat = room.viewer.seat

  const membersBySeat = useMemo(() => {
    const map = new Map<number, string>()
    for (const seatView of room.seats) map.set(seatView.seat, seatView.userId)
    return map
  }, [room.seats])

  const memberById = useMemo(() => {
    const map = new Map(room.members.map((m) => [m.userId, m]))
    return map
  }, [room.members])

  const gameSeatBySeat = useMemo(() => {
    const map = new Map<number, Game['seats'][number]>()
    if (game) for (const gs of game.seats) map.set(gs.seat, gs)
    return map
  }, [game])

  const totalSeats = room.maxSeats
  // Rotate so your seat (or seat 0 when unseated) renders bottom-center.
  const anchor = yourSeat ?? 0

  const board: (string | null)[] = useMemo(() => {
    const cards = game?.board ?? []
    return Array.from({ length: 5 }, (_, i) => cards[i] ?? null)
  }, [game])

  const winnerSeats = new Set(
    game?.awards.flatMap((award) => award.winners.map((winner) => winner.seat)) ?? [],
  )
  const winningCards = new Set(
    game?.seats
      .filter((seat) => winnerSeats.has(seat.seat))
      .flatMap((seat) => seat.holeCards ?? []) ?? [],
  )
  const isShowdown = game?.phase === 'COMPLETE' && game.completionReason === 'SHOWDOWN'
  const potMain = game?.pots[0]?.amount ?? 0
  const potSide = game?.pots.slice(1).reduce((total, pot) => total + pot.amount, 0) || undefined
  const potTotal = potMain + (potSide ?? 0)

  const handleTakeSeat = (seat: number) => {
    playSound('click')
    takeSeat(seat).then(
      (ack) => {
        if (ack.ok) pushToast('ok', `Took seat ${seat + 1}`)
      },
      () => {},
    )
  }

  return (
    <div className="table-wrap" data-testid="table" data-seat-count={totalSeats}>
      <div className="felt" />
      <div className="table-logo">
        H<span className="lt-o">O</span>LD&#39;EM
      </div>

      <div className="pot-wrap">
        <div className="pot">
          <span className="label">pot</span>
          <b className="num pop" key={animations.potPopKey} data-testid="pot">
            {potTotal.toLocaleString()}
          </b>
        </div>
        {potSide !== undefined && potSide > 0 && (
          <span className="pot-side num">
            main {potMain.toLocaleString()} · side {potSide.toLocaleString()}
          </span>
        )}
        {game && <div className="street label">{game.phase ? (PHASE_LABEL[game.phase] ?? '') : ''}</div>}
      </div>

      <div className="board" data-testid="board">
        {board.map((code, i) => (
          <PixelCard
            key={game ? `${game.handId}-${i}-${code ?? 'slot'}` : `empty-${i}`}
            code={code}
            win={isShowdown && code !== null && winningCards.has(code)}
            dealDelayMs={
              animations.boardDealFrom !== null && code !== null && i >= animations.boardDealFrom
                ? (i - animations.boardDealFrom) * 120
                : undefined
            }
          />
        ))}
      </div>

      <div className="seats">
        {Array.from({ length: totalSeats }, (_, seat) => {
          const viewIndex = (seat - anchor + totalSeats) % totalSeats
          const pos = seatPosition(viewIndex, totalSeats)
          const userId = membersBySeat.get(seat)
          const member = userId ? (memberById.get(userId) ?? null) : null
          const gameSeat = gameSeatBySeat.get(seat) ?? null
          const isActor = game !== null && game.actingSeat === seat && !isShowdown
          return (
            <div
              key={seat}
              className="seat-anchor"
              style={{ left: `${pos.x}%`, top: `${pos.y}%` }}
            >
              <SeatPod
                seat={seat}
                member={member}
                gameSeat={gameSeat}
                isYou={seat === yourSeat}
                isActor={isActor}
                isWinner={isShowdown && winnerSeats.has(seat)}
                isDealer={game?.buttonSeat === seat}
                showdown={Boolean(isShowdown)}
                deadline={isActor && game ? game.deadline : null}
                budgetMs={room.config.actionTimeoutSeconds * 1000}
                canTakeSeat={room.viewer.canTakeSeat && !member}
                onTakeSeat={handleTakeSeat}
                dealDelayMs={animations.holeDeal ? viewIndex * 90 : undefined}
              />
            </div>
          )
        })}
      </div>

      <div className="bets">
        {game &&
          !isShowdown &&
          game.seats
            .filter((gs) => (gs.committed ?? 0) > 0)
            .map((gs) => {
              const viewIndex = (gs.seat - anchor + totalSeats) % totalSeats
              const pos = seatPosition(viewIndex, totalSeats)
              return (
                <div
                  key={gs.seat}
                  className={`betchip${animations.chipFlySeats.has(gs.seat) ? ' fly' : ''}`}
                  style={{ left: `${pos.betX}%`, top: `${pos.betY}%` }}
                >
                  <span className="chip" />
                  <b className="num">{(gs.committed ?? 0).toLocaleString()}</b>
                </div>
              )
            })}
      </div>
    </div>
  )
}
