// The green-felt stadium table (T7 layout from tasks/task7/design/table-green.html).
// Superellipse projection pushes seats onto the stadium rim; your seat is rotated
// to bottom-center. Board, pot, committed bet chips render at center/inner curve.

import { useLayoutEffect, useMemo, useRef } from 'react'
import type { CSSProperties, RefObject } from 'react'
import PixelCard from './PixelCard'
import SeatPod from './SeatPod'
import ChipStack from './ChipStack'
import { useGameStore } from '../store/gameStore'
import type { AnimationEvents } from '../hooks/useAnimationQueue'
import { settledPotAmount } from '../hooks/useAnimationQueue'
import type { ChatMessage, Game, RoomSnapshot } from '../store/types'
import { formatChips, formatChipsFull } from '../lib/chips'

const K = 0.72 // superellipse power
// Keep the visible deck in the open lane to the pot's right. These coordinates
// mirror .deck-shoe so dealt cards visibly originate from the same place.
const DECK_ORIGIN = { x: 63, y: 26 }
// This is the single physical chip pile beside POT. Street collection and
// winner payouts both use it so chips never teleport through the pill.
const POT_CHIP_ORIGIN = { x: 42, y: 26 }

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
  // Even tables put a seat dead-center above the pot, whose top pod + committed
  // chip otherwise crowd the pot readout (top:26%) at the narrow scale phones
  // use. A larger vertical rim opens that band; dense 8/9 tables keep a tighter
  // rim (they'd hit the top bar / rescale too small otherwise) but still need
  // more than the old 44 to lift the top pod clear.
  const rimY = totalSeats >= 8 ? 62 : totalSeats === 2 ? 66 : 68
  // Committed chips ride the inner curve, but the top and bottom seats need
  // opposite vertical insets. Upper seats (sin<0) push their chip toward the pod
  // so it clears the pot; the denser 8/9 rim sits the pod lower, so its chip
  // takes a smaller inset. Lower seats (sin>0) pull their chip toward center so
  // it clears the hole cards that rise from the pod.
  const betRadiusY = sin < 0 ? (totalSeats >= 8 ? 41 : 43) : 28
  return {
    x: 50 + superellipse(cos, rimX),
    y: 50 + superellipse(sin, rimY),
    betX: 50 + superellipse(cos, 30),
    betY: 50 + superellipse(sin, betRadiusY),
  }
}

// Clearance for bits that stick out past a seat's layout box (turn timer
// below, status badges to the sides) plus breathing room against hard edges.
const FIT_PAD = { top: 8, bottom: 42, side: 14 }

// The composition's layout size comes from the CSS width breakpoints, which are
// laptop-sized; a desktop leaves that box floating in dead space with pod names,
// stacks and cards reading tiny. The fit below may therefore scale UP as well as
// down, capped so a 4K screen does not blow the pixel art into slabs. It also
// keeps browser zoom honest: zooming shrinks the CSS viewport, which can trip a
// narrower width breakpoint into a smaller layout box, and without headroom
// above 1 the table would come out smaller than it was at 100%.
const MAX_FIT_SCALE = 1.6

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
      const scale = Math.min(MAX_FIT_SCALE, availW / (box.right - box.left), availH / (box.bottom - box.top))
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
  const chatMessages = useGameStore((s) => s.chatMessages)
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

  const latestChatBySender = useMemo(() => {
    const map = new Map<string, ChatMessage>()
    for (const message of chatMessages) map.set(message.senderId, message)
    return map
  }, [chatMessages])

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
      .flatMap((seat) => seat.bestHandCards ?? []) ?? [],
  )
  const isShowdown = game?.phase === 'COMPLETE' && game.completionReason === 'SHOWDOWN'
  const potMain = game?.pots[0]?.amount ?? 0
  const potSide = game?.pots.slice(1).reduce((total, pot) => total + pot.amount, 0) || undefined
  const potTotal = game?.potTotal ?? 0
  const settledPot = settledPotAmount(game, animations.collectingBets)

  const handleTakeSeat = (seat: number) => {
    takeSeat(seat).then(
      (ack) => {
        if (ack.ok) pushToast('ok', `Took seat ${seat + 1}`)
      },
      () => {},
    )
  }

  const handleStatusChipClick = (message: string) => {
    pushToast('info', message)
  }

  return (
    <div className="table-wrap" ref={wrapRef} data-testid="table" data-seat-count={totalSeats}>
      <div className="felt" />
      <div className="table-logo">
        H<span className="lt-o">O</span>LD&#39;EM
      </div>

      {game && !animations.handCleanup && game.phase !== 'COMPLETE' && (
        <div className="deck-shoe" aria-hidden="true">
          <span className="deck-card deck-card-back" />
          <span className="deck-card deck-card-front" />
        </div>
      )}

      {settledPot > 0 && (
        <div
          className="pot-chip-visual"
          style={{ left: `${POT_CHIP_ORIGIN.x}%`, top: `${POT_CHIP_ORIGIN.y}%` }}
          data-testid="settled-pot-chips"
        >
          <ChipStack amount={settledPot} compact />
        </div>
      )}

      <div className="pot-wrap">
        <div className={`pot${animations.awardActive ? ' is-awarding' : ''}`}>
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
            motion={animations.handCleanup && code !== null ? 'cleanup' : undefined}
            dealDelayMs={
              animations.boardDealFrom !== null && code !== null && i >= animations.boardDealFrom
                ? (i - animations.boardDealFrom) * 135
                : undefined
            }
            dealFrom={{ x: 280 - i * 74, y: -122, rotate: -12 + i * 4 }}
          />
        ))}
      </div>

      <div className="seats">
        {Array.from({ length: totalSeats }, (_, seat) => {
          const viewIndex = (seat - anchor + totalSeats) % totalSeats
          const pos = seatPosition(viewIndex, totalSeats)
          const isBottomRow = pos.y >= 72
          const userId = membersBySeat.get(seat)
          const member = userId ? (memberById.get(userId) ?? null) : null
          const chatMessage = member ? (latestChatBySender.get(member.userId) ?? null) : null
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
                onStatusChipClick={handleStatusChipClick}
                chatMessage={chatMessage}
                chatBubbleBottomRow={isBottomRow}
                dealDelayMs={animations.holeDeal ? viewIndex * 85 : undefined}
                revealDelayMs={isShowdown ? viewIndex * 180 : undefined}
                dealFrom={{
                  x: (DECK_ORIGIN.x - pos.x) * 10.2,
                  y: (DECK_ORIGIN.y - pos.y) * 5.1,
                  rotate: viewIndex % 2 === 0 ? -10 : 10,
                }}
                handCleanup={animations.handCleanup}
                action={animations.action}
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
            .filter((gs) => !animations.collectingBets.some((bet) => bet.seat === gs.seat))
            .map((gs) => {
              const viewIndex = (gs.seat - anchor + totalSeats) % totalSeats
              const pos = seatPosition(viewIndex, totalSeats)
              return (
                <div
                  key={gs.seat}
                  className={`chip-motion chip-motion--bet${animations.chipFlySeats.has(gs.seat) ? ' is-flying' : ''}`}
                  style={
                    {
                      '--motion-x': `${pos.x - pos.betX}%`,
                      '--motion-y': `${pos.y - pos.betY}%`,
                    } as CSSProperties
                  }
                >
                  <div
                    className="betchip chip-payload"
                    style={{ left: `${pos.betX}%`, top: `${pos.betY}%` }}
                  >
                    <ChipStack amount={gs.committed ?? 0} compact />
                    <b className="num" title={formatChipsFull(gs.committed ?? 0)}>
                      {formatChips(gs.committed ?? 0)}
                    </b>
                  </div>
                </div>
              )
            })}
        {animations.collectingBets.map((bet) => {
          const viewIndex = (bet.seat - anchor + totalSeats) % totalSeats
          const pos = seatPosition(viewIndex, totalSeats)
          return (
            <div
              key={`collect-${game?.handId ?? 0}-${bet.seat}-${bet.amount}`}
              className="chip-motion chip-motion--collect"
              style={
                {
                  '--motion-x': `${POT_CHIP_ORIGIN.x - pos.betX}%`,
                  '--motion-y': `${POT_CHIP_ORIGIN.y - pos.betY}%`,
                } as CSSProperties
              }
            >
              <div
                className="betchip chip-payload"
                style={{ left: `${pos.betX}%`, top: `${pos.betY}%` }}
              >
                <ChipStack amount={bet.amount} compact />
                <b className="num">{formatChips(bet.amount)}</b>
              </div>
            </div>
          )
        })}
      </div>

      {game?.phase === 'COMPLETE' && animations.awardActive && !animations.handCleanup && game.awards.flatMap((award) =>
        award.winners.map((winner, winnerIndex) => {
          const viewIndex = (winner.seat - anchor + totalSeats) % totalSeats
          const pos = seatPosition(viewIndex, totalSeats)
          return (
            <div
              key={`award-${animations.awardKey}-${award.potIndex}-${winner.seat}`}
              className="chip-motion chip-motion--award"
              style={
                {
                  '--motion-x': `${pos.x - POT_CHIP_ORIGIN.x}%`,
                  '--motion-y': `${pos.y - POT_CHIP_ORIGIN.y}%`,
                  '--motion-delay': `${award.potIndex * 160 + winnerIndex * 110}ms`,
                } as CSSProperties
              }
            >
              <div
                className="award-flight chip-payload"
                style={{ left: `${POT_CHIP_ORIGIN.x}%`, top: `${POT_CHIP_ORIGIN.y}%` }}
              >
                <ChipStack amount={winner.amount} />
                <b className="num">+{formatChips(winner.amount)}</b>
              </div>
            </div>
          )
        }),
      )}

      {animations.phaseFlash && (
        <div key={animations.phaseFlashKey} className="street-flash" role="status">
          <span>{animations.phaseFlash}</span>
        </div>
      )}
    </div>
  )
}
