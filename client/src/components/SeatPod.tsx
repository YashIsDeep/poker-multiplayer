// One seat pod on the felt: name / stack / actions, position chips (dealer/SB/BB),
// status badges, hole cards above, and the timer ring when acting.

import PixelCard from './PixelCard'
import TimerRing from './TimerRing'
import type { GameSeat, LastAction, RoomMemberView } from '../store/types'
import { formatChips, formatChipsFull } from '../lib/chips'

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
  /** T8 deal stagger for freshly dealt hole cards. */
  dealDelayMs?: number
}

function actionLabel(action: LastAction): string {
  const verb = action.action === 'RAISE_TO' ? 'raise' : action.action.toLowerCase()
  return action.amount !== null && action.amount > 0
    ? `${verb} ${formatChips(action.amount)}`
    : verb
}

export default function SeatPod(props: SeatPodProps) {
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
        <div className="badge-row">
          {allin && <span className="badge allin">All-in</span>}
          {sitout && <span className="badge sitout-b">Sitting out</span>}
          {busted && (
            <span className="badge sitout-b">
              Busted{(gameSeat?.rebuyRoundsRemaining ?? 0) > 0 ? ` · ${gameSeat?.rebuyRoundsRemaining}R` : ''}
            </span>
          )}
          {offline && <span className="badge offline-b">Offline</span>}
        </div>
        {(isDealer || isSmallBlind || isBigBlind) && (
          <div className="position-chip-row" aria-label="Table positions">
            {isDealer && <span className="badge position-chip dealer" aria-label="Dealer button">D</span>}
            {isSmallBlind && <span className="badge position-chip small-blind" aria-label="Small blind">SB</span>}
            {isBigBlind && <span className="badge position-chip big-blind" aria-label="Big blind">BB</span>}
          </div>
        )}
        {isActor && props.deadline !== null && (
          <TimerRing deadline={props.deadline} budgetMs={props.budgetMs} />
        )}
        <div className="pidentity">
          {gameSeat?.position && <span className="position-label">{gameSeat.position}</span>}
          <span className="pname">
            {member.displayName.toUpperCase()}
            {isYou ? ' (YOU)' : ''}
          </span>
          {member.admin && (
            <span className="admin-shield" title="Table admin" aria-label="Table admin">A</span>
          )}
        </div>
        {gameSeat?.rebought && (
          <div className="rebought-label" title="This player has rebought" aria-label="Player has rebought">
            ↻ REBOUGHT
          </div>
        )}
        <div
          className="pstack num"
          title={gameSeat ? `${formatChipsFull(gameSeat.stack)} chips` : undefined}
        >
          {gameSeat ? (allin ? 'ALL-IN' : formatChips(gameSeat.stack)) : '—'}
        </div>
        <div className="pactions" aria-label="Actions this betting round">
          {roundActions.map((action, index) => (
            <span key={`${index}-${action.action}-${action.amount ?? ''}`}>
              {index > 0 && <span className="action-separator"> · </span>}
              {actionLabel(action)}
            </span>
          ))}
        </div>
        {showdown && gameSeat?.finalHandRank && <div className="prank">{gameSeat.finalHandRank}</div>}
      </div>
    </div>
  )
}
