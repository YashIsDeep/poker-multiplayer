// One seat pod on the felt: name / stack / actions, position chips (dealer/SB/BB),
// status badges, hole cards above, and the timer ring when acting.

import { useEffect, useRef, useState } from 'react'
import PixelCard from './PixelCard'
import TimerRing from './TimerRing'
import type { ChatMessage, GameSeat, LastAction, RoomMemberView } from '../store/types'
import type { AnimatedAction } from '../hooks/useAnimationQueue'
import { useTableChipFormat } from '../hooks/useTableChipFormat'

const CHAT_BUBBLE_TTL_MS = 6_000
const CHAT_EMOJI_TTL_MS = 3_000
const EMOJI_ONLY_PATTERN = /^[\p{Extended_Pictographic}\p{Emoji_Presentation}\p{Emoji_Modifier}\uFE0F\u200D]+$/u

export type SeatPodProps = {
  seat: number
  member: RoomMemberView | null
  gameSeat: GameSeat | null
  isYou: boolean
  isActor: boolean
  isWinner: boolean
  isDealer: boolean
  isSmallBlind: boolean
  isBigBlind: boolean
  showdown: boolean
  deadline: number | null
  budgetMs: number
  canTakeSeat: boolean
  onTakeSeat: (seat: number) => void
  onStatusChipClick: (message: string) => void
  chatMessage?: ChatMessage | null
  chatBubbleBottomRow?: boolean
  /** T8 deal stagger for freshly dealt hole cards. */
  dealDelayMs?: number
  revealDelayMs?: number
  dealFrom?: { x: number; y: number; rotate?: number }
  handCleanup?: boolean
  action?: AnimatedAction | null
}

function actionLabel(action: LastAction, formatChips: (value: number) => string): string {
  const verb = action.action === 'RAISE_TO' ? 'raise' : action.action.toLowerCase()
  return action.amount !== null && action.amount > 0
    ? `${verb} ${formatChips(action.amount)}`
    : verb
}

function ChatBubble({ message, bottomRow }: { message: ChatMessage; bottomRow: boolean }) {
  const [visible, setVisible] = useState(true)
  const emojiOnly = EMOJI_ONLY_PATTERN.test(message.message.trim())

  useEffect(() => {
    const timer = window.setTimeout(
      () => setVisible(false),
      emojiOnly ? CHAT_EMOJI_TTL_MS : CHAT_BUBBLE_TTL_MS,
    )
    return () => window.clearTimeout(timer)
  }, [message.id])

  if (!visible) return null

  return (
    <div
      className={`chat-bubble${emojiOnly ? ' chat-bubble--emoji' : ''}${bottomRow ? ' chat-bubble--bottom-row' : ''}`}
      role="status"
      aria-live="polite"
    >
      <p>{message.message}</p>
    </div>
  )
}

/** Only players captured in the current hand receive face-down hole cards. */
export function displayedHoleCards(
  gameSeat: GameSeat | null,
  showdown: boolean,
): (string | null)[] | null {
  const status = gameSeat?.seatStatus
  const cannotHoldCards =
    status === 'FOLDED' || status === 'SITTING_OUT' || status === 'BUSTED'
  if (gameSeat?.holeCards?.length && !cannotHoldCards) return gameSeat.holeCards
  if (gameSeat?.position !== null && gameSeat?.position !== undefined && !cannotHoldCards && !showdown) {
    return ['back', 'back']
  }
  return null
}

export type ShowdownHandLabel = {
  category: string
  detail: string | null
}

/** Presentation-only split of the authoritative server hand description. */
export function splitShowdownHand(description: string): ShowdownHandLabel {
  const highCard = /^(.+)-high (Straight Flush|Straight|Flush)$/.exec(description)
  if (highCard) return { category: highCard[2], detail: `${highCard[1]}-high` }

  const comma = description.indexOf(', ')
  if (comma >= 0) {
    return { category: description.slice(0, comma), detail: description.slice(comma + 2) }
  }

  const grouped = /^(Pair|Three of a Kind|Four of a Kind) of (.+)$/.exec(description)
  if (grouped) return { category: grouped[1], detail: grouped[2] }

  const fullHouse = /^(.+) full of (.+)$/.exec(description)
  if (fullHouse) return { category: 'Full House', detail: `${fullHouse[1]} full of ${fullHouse[2]}` }

  return { category: description, detail: null }
}

type HoleCardsProps = {
  cards: (string | null)[] | null
  isWinner: boolean
  showdown: boolean
  bestHandCards?: string[] | null
  showdownHand: ShowdownHandLabel | null
  dealDelayMs?: number
  revealDelayMs?: number
  dealFrom?: { x: number; y: number; rotate?: number }
  handCleanup: boolean
}

function HoleCards({
  cards,
  isWinner,
  showdown,
  bestHandCards,
  showdownHand,
  dealDelayMs,
  revealDelayMs,
  dealFrom,
  handCleanup,
}: HoleCardsProps) {
  const [renderedCards, setRenderedCards] = useState(cards)
  const [mucking, setMucking] = useState(false)
  const renderedRef = useRef(renderedCards)
  renderedRef.current = renderedCards
  const signature = cards?.join('|') ?? ''

  useEffect(() => {
    if (cards) {
      setRenderedCards(cards)
      setMucking(false)
      return
    }
    if (!renderedRef.current) return
    setMucking(true)
    const timer = window.setTimeout(() => {
      setRenderedCards(null)
      setMucking(false)
    }, 480)
    return () => window.clearTimeout(timer)
  }, [signature]) // card identity, not array allocation, drives lifecycle

  if (!renderedCards) return null
  return (
    <div
      className={[
        'mini-cards',
        showdownHand ? 'has-rank' : '',
        mucking ? 'is-mucking' : '',
        handCleanup ? 'is-cleaning' : '',
      ].filter(Boolean).join(' ')}
    >
      {renderedCards.map((card, index) => (
        <PixelCard
          key={`hole-${index}`}
          code={card}
          size="hole"
          win={Boolean(isWinner && showdown && card !== null && bestHandCards?.includes(card))}
          dealDelayMs={dealDelayMs !== undefined ? dealDelayMs + index * 140 : undefined}
          revealDelayMs={revealDelayMs !== undefined ? revealDelayMs + index * 120 : undefined}
          dealFrom={dealFrom ? { ...dealFrom, rotate: (dealFrom.rotate ?? -8) + index * 6 } : undefined}
          motion={handCleanup ? 'cleanup' : mucking ? 'muck' : undefined}
        />
      ))}
    </div>
  )
}

export default function SeatPod(props: SeatPodProps) {
  const { formatChips, formatChipsFull } = useTableChipFormat()
  const {
    seat,
    member,
    gameSeat,
    isYou,
    isActor,
    isWinner,
    isDealer,
    isSmallBlind,
    isBigBlind,
    showdown,
    chatMessage,
    chatBubbleBottomRow = false,
  } = props

  if (!member) {
    return (
      <div className="seat empty" data-seat={seat}>
        <button
          className="pod pod-empty"
          data-sound="seat"
          disabled={!props.canTakeSeat}
          onClick={() => props.onTakeSeat(seat)}
          aria-label={`Take seat ${seat + 1}`}
        >
          <div className="pname">SEAT {seat + 1}</div>
          <div className="plast">{props.canTakeSeat ? 'sit here' : 'empty'}</div>
        </button>
      </div>
    )
  }

  const status = gameSeat?.seatStatus
  const folded = status === 'FOLDED'
  const sitout = status === 'SITTING_OUT'
  const busted = status === 'BUSTED'
  const allin = status === 'ALL_IN'
  const offline = member.connected === false
  const roundActions = gameSeat?.actions?.length
    ? gameSeat.actions
    : gameSeat?.lastAction
      ? [gameSeat.lastAction]
      : []

  const seatClasses = [
    'seat',
    isYou ? 'you' : '',
    folded ? 'folded' : '',
    sitout || busted ? 'sitout' : '',
    isActor ? 'actor' : '',
    isWinner ? 'winner' : '',
    offline ? 'offline' : '',
  ]
    .filter(Boolean)
    .join(' ')

  // `position` is assigned only to the immutable participant set captured
  // when the hand starts. A player taking a live table seat mid-hand must wait.
  const cards = displayedHoleCards(gameSeat, showdown)
  // The boolean fallback keeps older snapshots readable while the server/client
  // contract rolls out the precise count.
  const rebuyCount = gameSeat?.rebuyCount ?? (gameSeat?.rebought ? 1 : 0)
  const livesUsed = rebuyCount + 1
  const rebuyTimes = rebuyCount === 1 ? 'time' : 'times'
  const showRoleToast = (role: string) => {
    props.onStatusChipClick(`${member.displayName} is the ${role} for this round`)
  }
  const showdownHand = showdown && gameSeat?.finalHandRank
    ? splitShowdownHand(gameSeat.finalHandRank)
    : null

  return (
    <div className={seatClasses} data-seat={seat}>
      {chatMessage && (
        <ChatBubble key={chatMessage.id} message={chatMessage} bottomRow={chatBubbleBottomRow} />
      )}
      <HoleCards
        cards={cards}
        isWinner={isWinner}
        showdown={showdown}
        bestHandCards={gameSeat?.bestHandCards}
        showdownHand={showdownHand}
        dealDelayMs={props.dealDelayMs}
        revealDelayMs={props.revealDelayMs}
        dealFrom={props.dealFrom}
        handCleanup={Boolean(props.handCleanup)}
      />
      {showdownHand && gameSeat?.finalHandRank && (
        <div
          className={`showdown-rank${isWinner ? ' winner' : ''}`}
          data-testid={`showdown-rank-${seat}`}
          aria-label={`Showdown hand: ${gameSeat.finalHandRank}`}
        >
          <span className="showdown-rank__category">{showdownHand.category}</span>
          {showdownHand.detail && (
            <span className="showdown-rank__detail">({showdownHand.detail})</span>
          )}
        </div>
      )}
      {props.action?.seat === seat && (
        <div key={props.action.key} className={`action-callout action-${props.action.tone}`}>
          {props.action.label}
        </div>
      )}
      <div className="pod">
        {(member.admin || rebuyCount > 0) && (
          <div className="corner-chip-stack" aria-label="Player status chips">
            {member.admin && (
              <button
                className="badge position-chip admin-chip"
                type="button"
                data-sound="notify"
                title="Table admin"
                aria-label="Table admin"
                onClick={() => props.onStatusChipClick(`${member.displayName} has admin controls`)}
              >
                A
              </button>
            )}
            {rebuyCount > 0 && (
              <button
                className="badge position-chip rebuy-chip num"
                type="button"
                data-sound="notify"
                title={`Rebought ${rebuyCount} ${rebuyTimes}`}
                aria-label={`Player has rebought ${rebuyCount} ${rebuyTimes}`}
                onClick={() => props.onStatusChipClick(`${member.displayName} rebought ${rebuyCount} ${rebuyTimes}`)}
              >
                x{livesUsed}
              </button>
            )}
          </div>
        )}
        <div className="badge-row">
          {allin && <span className="badge allin">All-in</span>}
          {sitout && <span className="badge sitout-b">Sitting out</span>}
          {busted && (
            <span className="badge sitout-b">
              Busted{(gameSeat?.rebuyHandsRemaining ?? 0) > 0 ? ` · ${gameSeat?.rebuyHandsRemaining}H` : ''}
            </span>
          )}
          {offline && <span className="badge offline-b">Offline</span>}
        </div>
        {(isDealer || isSmallBlind || isBigBlind) && (
          <div className="position-chip-row" aria-label="Table positions">
            {isDealer && (
              <button
                className="badge position-chip dealer"
                type="button"
                data-sound="notify"
                aria-label="Dealer button"
                onClick={() => showRoleToast('dealer')}
              >
                D
              </button>
            )}
            {isSmallBlind && (
              <button
                className="badge position-chip small-blind"
                type="button"
                data-sound="notify"
                aria-label="Small blind"
                onClick={() => showRoleToast('small blind')}
              >
                SB
              </button>
            )}
            {isBigBlind && (
              <button
                className="badge position-chip big-blind"
                type="button"
                data-sound="notify"
                aria-label="Big blind"
                onClick={() => showRoleToast('big blind')}
              >
                BB
              </button>
            )}
          </div>
        )}
        {isActor && props.deadline !== null && (
          <TimerRing deadline={props.deadline} budgetMs={props.budgetMs} />
        )}
        <div className="pidentity">
          <span className="pname">
            {member.displayName.toUpperCase()}
            {isYou ? ' (YOU)' : ''}
          </span>
        </div>
        <div
          key={gameSeat?.stack ?? 'no-stack'}
          className="pstack num stack-change"
          title={gameSeat ? formatChipsFull(gameSeat.stack) : undefined}
        >
          {gameSeat ? (allin ? 'ALL-IN' : formatChips(gameSeat.stack)) : '—'}
        </div>
        <div className="pactions" aria-label="Actions this betting round">
          {roundActions.map((action, index) => (
            <span key={`${index}-${action.action}-${action.amount ?? ''}`}>
              {index > 0 && <span className="action-separator"> · </span>}
              {actionLabel(action, formatChips)}
            </span>
          ))}
        </div>
      </div>
    </div>
  )
}
