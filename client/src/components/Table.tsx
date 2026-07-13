// The green-felt stadium table (T7 layout from tasks/task7/design/table-green.html).
// Superellipse projection pushes seats onto the stadium rim; your seat is rotated
// to bottom-center. Board, pot, committed bet chips render at center/inner curve.

import { useLayoutEffect, useMemo, useRef } from 'react'
import type { RefObject } from 'react'
import PixelCard from './PixelCard'
import SeatPod from './SeatPod'
import { useGameStore } from '../store/gameStore'
import type { AnimationEvents } from '../hooks/useAnimationQueue'
import type { Game, RoomSnapshot } from '../store/types'
import { formatChips, formatChipsFull } from '../lib/chips'

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

// Clearance for bits that stick out past a seat's layout box (turn timer
// below, status badges to the sides) plus breathing room against hard edges.
const FIT_PAD = { top: 8, bottom: 42, side: 14 }

// T11 fit-to-viewport: the composition (felt + overhanging pods) has a fixed
// layout size from the CSS width breakpoints; this hook scales and positions
// .table-wrap so every pod stays inside the box the room chrome leaves free
// (--chrome-top/--chrome-bottom/--chrome-right on .room-stage). The felt may
// still bleed past the side edges on phones — that is the T8 look — but pods,
// board, and pot must never hide under the topbar or action bar.
function useTableFit(wrapRef: RefObject<HTMLDivElement | null>, totalSeats: number) {
  useLayoutEffect(() => {
    const wrap = wrapRef.current
    const stage = wrap?.parentElement
    if (!wrap || !stage) return

    const fit = () => {
      const wrapW = wrap.offsetWidth
      const wrapH = wrap.offsetHeight
      if (!wrapW || !wrapH) return
      let podW = 0
      let podH = 0
      for (const el of wrap.querySelectorAll<HTMLElement>('.seat')) {
        podW = Math.max(podW, el.offsetWidth)
        podH = Math.max(podH, el.offsetHeight)
      }
      let minX = 50
      let maxX = 50
      let minY = 50
      let maxY = 50
      for (let i = 0; i < totalSeats; i++) {
        const p = seatPosition(i, totalSeats)
        minX = Math.min(minX, p.x)
        maxX = Math.max(maxX, p.x)
        minY = Math.min(minY, p.y)
        maxY = Math.max(maxY, p.y)
      }
      // Pod bounding box in unscaled wrap coordinates.
      const box = {
        left: (minX / 100) * wrapW - podW / 2 - FIT_PAD.side,
        right: (maxX / 100) * wrapW + podW / 2 + FIT_PAD.side,
        top: (minY / 100) * wrapH - podH / 2 - FIT_PAD.top,
        bottom: (maxY / 100) * wrapH + podH / 2 + FIT_PAD.bottom,
      }
      const styles = getComputedStyle(stage)
      const chrome = (name: string, fallback: number) => {
        const v = parseFloat(styles.getPropertyValue(name))
        return Number.isFinite(v) ? v : fallback
      }
      const chromeTop = chrome('--chrome-top', 64)
      const chromeBottom = chrome('--chrome-bottom', 196)
      const chromeRight = chrome('--chrome-right', 0)
      const availW = stage.clientWidth - chromeRight - 20
      const availH = stage.clientHeight - chromeTop - chromeBottom
      const scale = Math.min(1, availW / (box.right - box.left), availH / (box.bottom - box.top))
      // Wrap center such that the pod box lands centered in the free area
      // (scale() is about the wrap center thanks to translate(-50%, -50%)).
      const boxMidX = (box.left + box.right) / 2
      const boxMidY = (box.top + box.bottom) / 2
      const centerX = 10 + availW / 2 + (wrapW / 2 - boxMidX) * scale
      const centerY = chromeTop + availH / 2 + (wrapH / 2 - boxMidY) * scale
      wrap.style.left = `${Math.round(centerX)}px`
      wrap.style.top = `${Math.round(centerY)}px`
      wrap.style.transform = `translate(-50%, -50%) scale(${scale.toFixed(4)})`
    }

    fit()
    const observer = new ResizeObserver(fit)
    observer.observe(stage) // window resizes + chrome-var media flips
    observer.observe(wrap) // width breakpoint changes
    for (const el of wrap.querySelectorAll<HTMLElement>('.seat')) observer.observe(el) // pod content growth
    window.addEventListener('resize', fit) // belt & braces where RO delivery is throttled
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', fit)
    }
  }, [wrapRef, totalSeats])
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
  const wrapRef = useRef<HTMLDivElement>(null)
  useTableFit(wrapRef, room.maxSeats)

  const membersBySeat = useMemo(() => {
    const map = new Map<number, string>()
    for (const seatView of room.seats) {
      if (seatView.userId) map.set(seatView.seat, seatView.userId)
    }
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
  const potTotal = game?.potTotal ?? 0

  const handleTakeSeat = (seat: number) => {
    takeSeat(seat).then(
      (ack) => {
        if (ack.ok) pushToast('ok', `Took seat ${seat + 1}`)
      },
      () => {},
    )
  }

  return (
    <div className="table-wrap" ref={wrapRef} data-testid="table" data-seat-count={totalSeats}>
      <div className="felt" />
      <div className="table-logo">
        H<span className="lt-o">O</span>LD&#39;EM
      </div>

      <div className="pot-wrap">
        <div className="pot">
          <span className="label">pot</span>
          <b className="num pop" key={animations.potPopKey} data-testid="pot" title={formatChipsFull(potTotal)}>
            {formatChips(potTotal)}
          </b>
        </div>
        {potSide !== undefined && potSide > 0 && (
          <span className="pot-side num">
            main {formatChips(potMain)} · side {formatChips(potSide)}
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
                isSmallBlind={game?.smallBlindSeat === seat}
                isBigBlind={game?.bigBlindSeat === seat}
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
                  <b className="num" title={formatChipsFull(gs.committed ?? 0)}>
                    {formatChips(gs.committed ?? 0)}
                  </b>
                </div>
              )
            })}
      </div>
    </div>
  )
}
