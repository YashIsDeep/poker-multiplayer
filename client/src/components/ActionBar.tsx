// Action-area state machine (T7 §ActionArea, T8 pill/slider skin):
// reconnecting → turn → waiting → showdown → busted → sitting-out → idle.

import { useEffect, useMemo, useState } from 'react'
import { useGameStore } from '../store/gameStore'
import { clampRaise, raisePresets } from '../lib/cards'
import { formatCountdown, useServerCountdown } from '../hooks/useServerCountdown'
import type { CSSProperties } from 'react'
import { formatChips, formatChipsFull } from '../lib/chips'

export default function ActionBar() {
  const connection = useGameStore((s) => s.connection)
  const readOnly = useGameStore((s) => s.readOnly)
  const room = useGameStore((s) => s.room)
  const game = useGameStore((s) => s.game)
  const yourSeat = useGameStore((s) => s.yourSeat)
  const pendingAction = useGameStore((s) => s.pendingAction)
  const act = useGameStore((s) => s.act)
  const rebuy = useGameStore((s) => s.rebuy)
  const sitBack = useGameStore((s) => s.sitBack)
  const stand = useGameStore((s) => s.stand)

  const legalActions = game?.viewer.legalActions ?? null
  const isYourTurn =
    game !== null &&
    yourSeat !== null &&
    game.actingSeat === yourSeat &&
    game.viewer.canAct &&
    legalActions !== null
  const countdown = useServerCountdown(
    isYourTurn && game ? game.deadline : null,
    (room?.config.actionTimeoutSeconds ?? 30) * 1000,
  )

  const [raiseTo, setRaiseTo] = useState(0)
  const minRaiseTo = legalActions?.minRaiseTo ?? 0
  const maxRaiseTo = legalActions?.maxRaiseTo ?? 0

  // Re-anchor the slider whenever a new decision arrives.
  const actionKey = game ? `${game.handId}:${game.actionSeq}` : 'none'
  useEffect(() => {
    setRaiseTo(minRaiseTo)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionKey])

  const potTotal = game?.potTotal ?? 0
  const presets = useMemo(
    () =>
      raisePresets({
        pot: potTotal,
        toCall: legalActions?.toCall ?? 0,
        minRaiseTo,
        maxRaiseTo,
      }),
    [potTotal, legalActions?.toCall, minRaiseTo, maxRaiseTo],
  )

  // ----- state selection -----

  if (connection !== 'connected' || readOnly) {
    return (
      <div className="actionbar panel wait" data-testid="actionbar-reconnecting">
        <h3 className="ab-head">Action</h3>
        <p className="waiting">
          Connection lost — reconnecting and re-syncing… controls are locked until the snapshot
          returns.
        </p>
      </div>
    )
  }

  if (!room) return null

  const viewerSeated = room.viewer.seated
  const gameSeat =
    game && yourSeat !== null ? (game.seats.find((s) => s.seat === yourSeat) ?? null) : null

  if (!viewerSeated && room.viewer.busted) {
    return (
      <BustedBar
        amount={room.viewer.rebuyAmount}
        enabled={room.config.rebuysEnabled}
        handsRemaining={room.viewer.rebuyHandsRemaining ?? 0}
        offerDeadline={room.viewer.rebuyOfferDeadline}
        disabled={pendingAction !== null}
        offTable
        onRebuy={rebuy}
      />
    )
  }

  if (!game || !viewerSeated) {
    return (
      <div className="actionbar panel wait" data-testid="actionbar-idle">
        <p className="waiting">
          {viewerSeated
            ? room.seats.length === 1
              ? 'Waiting for players to join'
              : 'Waiting for the next hand to start…'
            : 'You are spectating — take a seat to play.'}
        </p>
      </div>
    )
  }

  if (gameSeat?.seatStatus === 'BUSTED') {
    return (
      <BustedBar
        amount={game.viewer.rebuyAmount}
        enabled={room.config.rebuysEnabled ?? true}
        handsRemaining={game.viewer.rebuyHandsRemaining ?? gameSeat.rebuyHandsRemaining}
        offerDeadline={null}
        disabled={pendingAction !== null}
        offTable={false}
        onRebuy={rebuy}
      />
    )
  }

  if (gameSeat?.seatStatus === 'SITTING_OUT') {
    return (
      <div className="actionbar panel wait" data-testid="actionbar-sitout">
        <h3 className="ab-head">Sitting out</h3>
        <p className="waiting">You are sitting out — dealt out of hands.</p>
        <div className="ab-buttons">
          <button className="btn btn-blue" data-sound="seat" onClick={sitBack}>
            I&#39;m back (sit in)
          </button>
          <button className="btn btn-red" data-sound="exit" onClick={() => void stand()}>
            Stand up
          </button>
        </div>
      </div>
    )
  }

  if (game.phase === 'COMPLETE') {
    return (
      <div className="actionbar panel wait" data-testid="actionbar-showdown">
        <h3 className="ab-head">Showdown</h3>
        <ShowdownLine />
      </div>
    )
  }

  if (!isYourTurn) {
    return (
      <div className="waitbar panel" data-testid="actionbar-waiting">
        Waiting for other players
        <span className="dots" />
      </div>
    )
  }

  // ----- your turn -----

  const legal = legalActions!
  const locked = pendingAction !== null
  const callAmount = legal.callAmount
  const canRaise = legal.canRaise && maxRaiseTo >= minRaiseTo && maxRaiseTo > 0
  const raiseValue = clampRaise(raiseTo, minRaiseTo, maxRaiseTo)
  const fillPct =
    maxRaiseTo > minRaiseTo ? ((raiseValue - minRaiseTo) / (maxRaiseTo - minRaiseTo)) * 100 : 100
  const sliderStyle = { '--fill': `${fillPct}%` } as CSSProperties

  return (
    <div className="actionbar panel" data-testid="actionbar-turn">
      <h3 className="ab-head">
        Your turn — act within{' '}
        <span className={`countdown num${countdown.low ? ' low' : ''}`}>
          {formatCountdown(countdown.secondsRemaining)}
        </span>
      </h3>
      <div className="ab-buttons">
        {legal.canFold && !legal.canCheck && (
          <button
            className="btn btn-red"
            data-sound="fold"
            disabled={locked}
            onClick={() => act('fold')}
          >
            Fold
          </button>
        )}
        {legal.canCall ? (
          <button
            className="btn btn-blue"
            data-sound="call"
            disabled={locked}
            onClick={() => act('call')}
          >
            Call <span className="num" title={formatChipsFull(callAmount)}>{formatChips(callAmount)}</span>
          </button>
        ) : legal.canCheck ? (
          <button
            className="btn btn-blue"
            data-sound="check"
            disabled={locked}
            onClick={() => act('check')}
          >
            Check
          </button>
        ) : null}
        {canRaise && (
          <button
            className="btn btn-orange"
            data-sound="raise"
            disabled={locked}
            onClick={() => act('raiseTo', raiseValue)}
          >
            Raise to <span className="num" title={formatChipsFull(raiseValue)}>{formatChips(raiseValue)}</span>
          </button>
        )}
      </div>
      {canRaise && (
        <div className="raise-row">
          <div className="raise-pills">
            {(['min', 'half', 'pot', 'allin'] as const).map((key) => (
              <button
                key={key}
                className="pill"
                data-sound="tick"
                disabled={locked}
                onClick={() => setRaiseTo(presets[key])}
              >
                {key === 'min' ? 'Min' : key === 'half' ? '½ Pot' : key === 'pot' ? 'Pot' : 'All-in'}
              </button>
            ))}
          </div>
          <input
            type="range"
            min={minRaiseTo}
            max={maxRaiseTo}
            step={1}
            value={raiseValue}
            disabled={locked}
            style={sliderStyle}
            onChange={(e) => setRaiseTo(clampRaise(Number(e.target.value), minRaiseTo, maxRaiseTo))}
            aria-label="Raise amount"
          />
          <span className="raise-amt num" title={formatChipsFull(raiseValue)}>{formatChips(raiseValue)}</span>
        </div>
      )}
    </div>
  )
}

function BustedBar(props: {
  amount: number | null
  enabled: boolean
  handsRemaining: number
  offerDeadline: number | null
  disabled: boolean
  offTable: boolean
  onRebuy: (amount: number) => void
}) {
  const offer = useServerCountdown(props.offerDeadline, 5_000)
  const emergencyOffer = props.offerDeadline !== null && offer.secondsRemaining > 0
  const locked = !props.enabled || (!emergencyOffer && props.handsRemaining > 0)
    || props.amount === null || !props.offTable
  return (
    <div className="actionbar panel" data-testid="actionbar-busted">
      <h3 className="ab-head">You busted out</h3>
      {props.enabled ? (
        <div className="rebuy-cooldown" aria-live="polite">
          <div className="rebuy-round-track" aria-hidden="true">
            {Array.from({ length: 5 }, (_, index) => (
              <span
                key={index}
                className={index < Math.min(5, props.handsRemaining) ? 'round-chip remaining' : 'round-chip cleared'}
              />
            ))}
          </div>
          <p className="waiting">
            {!props.offTable
              ? 'You will move off the table after this hand.'
              : emergencyOffer
                ? `Priority rebuy window: ${offer.secondsRemaining}s`
                : props.handsRemaining > 0
                  ? `Rebuy unlocks after ${props.handsRemaining} more completed ${props.handsRemaining === 1 ? 'hand' : 'hands'}.`
                  : 'Cooldown complete — rebuy is unlocked.'}
          </p>
        </div>
      ) : (
        <p className="waiting">Rebuys are disabled at this table.</p>
      )}
      {props.enabled && (
        <div className="fixed-rebuy">
          <span>Rebuy stack</span>
          <strong className="num" title={props.amount === null ? undefined : formatChipsFull(props.amount)}>
            {props.amount === null ? '—' : formatChips(props.amount)}
          </strong>
        </div>
      )}
      <div className="ab-buttons">
        {props.enabled && (
          <button
            className="btn btn-orange"
            data-sound="rebuy"
            disabled={props.disabled || locked}
            onClick={() => props.amount !== null && props.onRebuy(props.amount)}
          >
            {emergencyOffer
              ? 'Priority rebuy'
              : props.handsRemaining > 0 ? `Rebuy locked · ${props.handsRemaining}H` : 'Rebuy'}{' '}
            {props.amount !== null && <span className="num">{formatChips(props.amount)}</span>}
          </button>
        )}
      </div>
    </div>
  )
}

function ShowdownLine() {
  const room = useGameStore((s) => s.room)
  const game = useGameStore((s) => s.game)
  if (!game || !room || game.awards.length === 0) {
    return <p className="waiting">Revealing hands…</p>
  }
  return (
    <div className="showdown-awards">
      {game.awards.map((award) => {
        const winners = award.winners.map((winner) => {
          const occupant = room.seats.find((candidate) => candidate.seat === winner.seat)
          const name = occupant
            ? (room.members.find((member) => member.userId === occupant.userId)?.displayName ?? 'player')
            : 'player'
          const rank = game.seats.find((seat) => seat.seat === winner.seat)?.finalHandRank
          return `${name} +${formatChips(winner.amount)}${rank ? ` — ${rank}` : ''}`
        })
        return (
          <p className="waiting" key={award.potIndex}>
            <b>{award.potIndex === 0 ? 'Main pot' : `Side pot ${award.potIndex}`}</b>
            {' · '}
            {winners.join(' · ')}
          </p>
        )
      })}
    </div>
  )
}
