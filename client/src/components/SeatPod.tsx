// One seat pod on the felt: name / stack / last action, badges (dealer, all-in,
// sitting out, busted, offline), hole cards above, timer ring when acting.

import PixelCard from './PixelCard'
import TimerRing from './TimerRing'
import type { GameSeat, RoomMemberView } from '../store/types'

export type SeatPodProps = {
  seat: number
  member: RoomMemberView | null
  gameSeat: GameSeat | null
  isYou: boolean
  isActor: boolean
  isWinner: boolean
  isDealer: boolean
  showdown: boolean
  deadline: number | null
  budgetMs: number
  canTakeSeat: boolean
  onTakeSeat: (seat: number) => void
  /** T8 deal stagger for freshly dealt hole cards. */
  dealDelayMs?: number
}

function lastActionLabel(gameSeat: GameSeat | null): string {
  const last = gameSeat?.lastAction
  if (!last) return ''
  const verb = last.action.replaceAll('_', ' ').toLowerCase()
  return last.amount !== null && last.amount > 0
    ? `${verb} ${last.amount.toLocaleString()}`
    : verb
}

export default function SeatPod(props: SeatPodProps) {
  const { seat, member, gameSeat, isYou, isActor, isWinner, isDealer, showdown } = props

  if (!member) {
    return (
      <div className="seat empty" data-seat={seat}>
        <button
          className="pod pod-empty"
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

  // Hole cards: own face-up, others face-down while live, revealed at showdown.
  let cards: (string | null)[] | null = null
  if (gameSeat?.holeCards && gameSeat.holeCards.length > 0 && !folded && !sitout && !busted) {
    cards = gameSeat.holeCards
  } else if (gameSeat && !folded && !sitout && !busted && status !== undefined && !showdown) {
    cards = ['back', 'back']
  }

  return (
    <div className={seatClasses} data-seat={seat}>
      {cards && (
        <div className="mini-cards">
          {cards.map((c, i) => (
            <PixelCard
              key={`${gameSeat?.holeCards ? 'face' : 'back'}-${i}`}
              code={c}
              size="hole"
              win={isWinner && showdown}
              dealDelayMs={props.dealDelayMs !== undefined ? props.dealDelayMs + i * 120 : undefined}
            />
          ))}
        </div>
      )}
      <div className="pod">
        {allin && <span className="badge allin">All-in</span>}
        {sitout && <span className="badge sitout-b">Sitting out</span>}
        {busted && <span className="badge sitout-b">Busted</span>}
        {offline && <span className="badge offline-b">Offline</span>}
        {isDealer && <span className="badge dealer">D</span>}
        {isActor && props.deadline !== null && (
          <TimerRing deadline={props.deadline} budgetMs={props.budgetMs} />
        )}
        <div className="pname">
          {member.displayName.toUpperCase()}
          {isYou ? ' (YOU)' : ''}
        </div>
        <div className="pstack num">
          {gameSeat ? (allin ? 'ALL-IN' : gameSeat.stack.toLocaleString()) : '—'}
        </div>
        <div className="plast">
          {showdown && gameSeat?.finalHandRank ? gameSeat.finalHandRank : lastActionLabel(gameSeat)}
        </div>
      </div>
    </div>
  )
}
