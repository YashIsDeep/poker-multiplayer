// Presentation bridge between authoritative server snapshots and the table.
// The store remains truthful immediately; this hook creates a short, bounded
// visual timeline so cards, chips, streets, and hand cleanup have causality.

import { useEffect, useRef, useState } from 'react'
import type { Game, LastAction } from '../store/types'

export type AnimatedBet = { seat: number; amount: number }

export type AnimatedAction = {
  seat: number
  label: string
  tone: 'fold' | 'check' | 'call' | 'raise' | 'allin'
  key: string
}

export type AnimationEvents = {
  /** The intentionally paced game shown by the table; controls still use store truth. */
  displayGame: Game | null
  boardDealFrom: number | null
  potPopKey: number
  chipFlySeats: ReadonlySet<number>
  collectingBets: readonly AnimatedBet[]
  holeDeal: boolean
  handCleanup: boolean
  phaseFlash: string | null
  phaseFlashKey: number
  action: AnimatedAction | null
  awardKey: number
  awardActive: boolean
}

const EMPTY_SET: ReadonlySet<number> = new Set()
const HAND_CLEANUP_MS = 560
const STREET_STEP_MS = 560
const TRANSIENT_MS = 1_050
const SHOWDOWN_COLLECT_MS = 900
const SHOWDOWN_READING_MS = 3_200
const AWARD_FLIGHT_MS = 1_350
// Matched to .chip-motion--bet and .chip-motion--collect in table.css.
const BET_FLIGHT_MS = 520
const BET_COLLECTION_MS = 720

/** Chips that have already left the felt and physically belong beside the pot. */
export function settledPotAmount(
  game: Game | null,
  collectingBets: readonly AnimatedBet[] = [],
): number {
  if (!game) return 0
  const collectingBySeat = new Map(collectingBets.map((bet) => [bet.seat, bet.amount]))
  const exposedOnFelt = game.seats.reduce(
    (total, seat) => total + Math.max(seat.committed ?? 0, collectingBySeat.get(seat.seat) ?? 0),
    0,
  )
  return Math.max(0, game.potTotal - exposedOnFelt)
}

function potTotal(game: Game | null): number {
  return game?.potTotal ?? 0
}

function actionSignature(action: LastAction | null | undefined): string {
  return action ? `${action.action}:${action.amount ?? ''}` : ''
}

function animatedAction(
  seat: number,
  action: LastAction,
  actionSeq: number,
  allIn: boolean,
): AnimatedAction {
  const amount = action.amount !== null && action.amount > 0 ? ` ${action.amount.toLocaleString('en-US')}` : ''
  if (allIn) {
    return { seat, label: `ALL-IN${amount}`, tone: 'allin', key: `${actionSeq}-${seat}-allin-${amount}` }
  }
  switch (action.action) {
    case 'FOLD':
      return { seat, label: 'FOLD', tone: 'fold', key: `${actionSeq}-${seat}-fold` }
    case 'CHECK':
      return { seat, label: 'CHECK', tone: 'check', key: `${actionSeq}-${seat}-check` }
    case 'CALL':
      return { seat, label: `CALL${amount}`, tone: 'call', key: `${actionSeq}-${seat}-call-${amount}` }
    case 'RAISE_TO':
      return { seat, label: `RAISE TO${amount}`, tone: 'raise', key: `${actionSeq}-${seat}-raise-${amount}` }
  }
}

export type AnimationDiff = {
  boardDealFrom: number | null
  potChanged: boolean
  chipFlySeats: ReadonlySet<number>
  collectingBets: readonly AnimatedBet[]
  holeDeal: boolean
  handChanged: boolean
  phaseFlash: string | null
  action: AnimatedAction | null
  awardsStarted: boolean
}

export function deriveAnimationDiff(prev: Game | null, game: Game | null): AnimationDiff {
  if (!game) {
    return {
      boardDealFrom: null,
      potChanged: false,
      chipFlySeats: EMPTY_SET,
      collectingBets: [],
      holeDeal: false,
      handChanged: false,
      phaseFlash: null,
      action: null,
      awardsStarted: false,
    }
  }

  const sameHand = prev !== null && prev.handId === game.handId
  const handChanged = prev !== null && !sameHand
  const prevBoard = sameHand && prev ? prev.board.length : 0
  const boardDealFrom = game.board.length > prevBoard ? prevBoard : null
  const potChanged = prev !== null && potTotal(prev) !== potTotal(game)
  const chipFlySeats = new Set<number>()
  const collectingBets: AnimatedBet[] = []
  const changedActions: AnimatedAction[] = []

  if (sameHand && prev) {
    for (const seat of game.seats) {
      const beforeSeat = prev.seats.find((candidate) => candidate.seat === seat.seat)
      const before = beforeSeat?.committed ?? 0
      const after = seat.committed ?? 0
      if (after > before) chipFlySeats.add(seat.seat)
      if (
        prev.phase !== game.phase &&
        before > 0 &&
        after === 0
      ) {
        collectingBets.push({ seat: seat.seat, amount: before })
      }

      if (
        seat.lastAction &&
        actionSignature(seat.lastAction) !== actionSignature(beforeSeat?.lastAction)
      ) {
        changedActions.push(animatedAction(
          seat.seat,
          seat.lastAction,
          game.actionSeq,
          seat.seatStatus === 'ALL_IN',
        ))
      }
    }
  } else if (handChanged) {
    // A live hand boundary contains freshly posted blinds. Treat those as new
    // wagers so they travel from the responsible seats after cleanup.
    for (const seat of game.seats) {
      if ((seat.committed ?? 0) > 0) chipFlySeats.add(seat.seat)
    }
  }

  // Bulk state replacement during recovery can change several seats at once;
  // suppress a misleading callout rather than pretending one was the live act.
  const action = changedActions.length === 1 ? changedActions[0] : null
  const phaseFlash = sameHand && prev?.phase !== game.phase
    ? game.phase === 'COMPLETE'
      ? 'SHOWDOWN'
      : game.phase
    : null

  return {
    boardDealFrom,
    potChanged,
    chipFlySeats: chipFlySeats.size > 0 ? chipFlySeats : EMPTY_SET,
    collectingBets,
    holeDeal: handChanged,
    handChanged,
    phaseFlash,
    action,
    awardsStarted:
      prev !== null &&
      game.phase === 'COMPLETE' &&
      prev.phase !== 'COMPLETE' &&
      game.awards.length > 0,
  }
}

/** Hold the visible pot at its current value while chips are still moving. */
export function stagePotUntilArrival(displayed: Game | null, game: Game): Game {
  if (!displayed || displayed.handId !== game.handId) return game
  return {
    ...game,
    potTotal: displayed.potTotal,
    pots: displayed.pots,
  }
}

/** Keep the pot and winner stacks at their pre-award values until chips land. */
export function stageAwardsUntilArrival(displayed: Game | null, game: Game): Game {
  if (!displayed || displayed.handId !== game.handId) return game
  const displayedSeats = new Map(displayed.seats.map((seat) => [seat.seat, seat]))
  return {
    ...game,
    potTotal: displayed.potTotal,
    pots: displayed.pots,
    seats: game.seats.map((seat) => {
      const prior = displayedSeats.get(seat.seat)
      return prior ? { ...seat, stack: prior.stack } : seat
    }),
  }
}

/** Keep the old street and board visible while its final wagers travel inward. */
export function stageBoardUntilBetsCollected(displayed: Game | null, game: Game): Game {
  if (!displayed || displayed.handId !== game.handId) return game
  return {
    ...game,
    phase: displayed.phase,
    completionReason: displayed.completionReason,
    board: displayed.board,
    awards: [],
  }
}

/** Build any missing live street frames when one snapshot contains a runout. */
export function buildStreetFrames(prev: Game | null, game: Game): Game[] {
  if (!prev || prev.handId !== game.handId) return [game]

  // COMPLETE has already emptied the pot and credited winners. Runout frames
  // must retain the prior chip state until the eventual award flight lands.
  const source = game.phase === 'COMPLETE' && game.awards.length > 0
    ? stageAwardsUntilArrival(prev, game)
    : game
  if (game.board.length <= prev.board.length) return [source]

  const frames: Game[] = []
  const previousCount = prev.board.length
  if (previousCount < 3 && game.board.length >= 3) {
    frames.push({ ...source, phase: 'FLOP', board: game.board.slice(0, 3), awards: [] })
  }
  if (previousCount < 4 && game.board.length >= 4) {
    frames.push({ ...source, phase: 'TURN', board: game.board.slice(0, 4), awards: [] })
  }
  if (previousCount < 5 && game.board.length >= 5) {
    frames.push({ ...source, phase: 'RIVER', board: game.board.slice(0, 5), awards: [] })
  }
  if (game.phase === 'COMPLETE' && frames.at(-1)?.phase !== 'COMPLETE') frames.push(source)
  return frames.length > 0 ? frames : [source]
}

/** Keep opponents face-down while the last bets physically arrive at the pot. */
export function buildShowdownPrelude(prev: Game | null, game: Game): Game {
  const staged = stageAwardsUntilArrival(prev, game)
  const previousSeats = new Map(
    prev?.handId === game.handId
      ? prev.seats.map((seat) => [seat.seat, seat] as const)
      : [],
  )
  const viewerSeat = game.viewer.seat

  return {
    ...staged,
    phase: 'RIVER',
    completionReason: null,
    awards: [],
    seats: staged.seats.map((seat) => {
      const previous = previousSeats.get(seat.seat)
      return {
        ...seat,
        holeCards: seat.seat === viewerSeat ? seat.holeCards : (previous?.holeCards ?? null),
        finalHandRank: null,
        bestHandCards: undefined,
      }
    }),
  }
}

function initialEvents(game: Game | null): AnimationEvents {
  return {
    displayGame: game,
    boardDealFrom: null,
    potPopKey: 0,
    chipFlySeats: EMPTY_SET,
    collectingBets: [],
    holeDeal: false,
    handCleanup: false,
    phaseFlash: null,
    phaseFlashKey: 0,
    action: null,
    awardKey: 0,
    awardActive: false,
  }
}

export function useAnimationQueue(game: Game | null, reducedMotion: boolean): AnimationEvents {
  const sourceRef = useRef<Game | null>(null)
  const latestRef = useRef<Game | null>(game)
  const awardBaselineRef = useRef<Game | null>(null)
  const timersRef = useRef<number[]>([])
  const [events, setEvents] = useState<AnimationEvents>(() => initialEvents(game))
  const eventsRef = useRef(events)

  useEffect(() => {
    eventsRef.current = events
  }, [events])

  useEffect(() => {
    return () => {
      for (const timer of timersRef.current) window.clearTimeout(timer)
    }
  }, [])

  useEffect(() => {
    latestRef.current = game
    const prev = sourceRef.current
    sourceRef.current = game
    for (const timer of timersRef.current) window.clearTimeout(timer)
    timersRef.current = []

    if (!game || reducedMotion) {
      awardBaselineRef.current = null
      setEvents((current) => ({
        ...initialEvents(game),
        potPopKey: current.potPopKey,
        phaseFlashKey: current.phaseFlashKey,
        awardKey: current.awardKey,
      }))
      return
    }

    const diff = deriveAnimationDiff(prev, game)
    const sameHand = prev !== null && prev.handId === game.handId
    const awardCountIncreased = sameHand && game.awards.length > prev.awards.length
    if (awardCountIncreased && !awardBaselineRef.current) {
      awardBaselineRef.current = eventsRef.current.displayGame ?? prev
    } else if (game.awards.length === 0 && game.phase !== 'COMPLETE') {
      awardBaselineRef.current = null
    }

    // Give the previous hand a physical exit before presenting the next one.
    if (diff.handChanged && prev?.phase === 'COMPLETE') {
      setEvents((current) => ({
        ...current,
        displayGame: prev,
        boardDealFrom: null,
        chipFlySeats: EMPTY_SET,
        collectingBets: [],
        holeDeal: false,
        handCleanup: true,
        phaseFlash: null,
        action: null,
        awardActive: false,
      }))
      const cleanupTimer = window.setTimeout(() => {
        const next = latestRef.current
        setEvents((current) => ({
          ...current,
          displayGame: next,
          boardDealFrom: null,
          chipFlySeats: new Set(
            next?.seats.filter((seat) => (seat.committed ?? 0) > 0).map((seat) => seat.seat) ?? [],
          ),
          holeDeal: true,
          handCleanup: false,
          phaseFlash: 'NEW HAND',
          phaseFlashKey: current.phaseFlashKey + 1,
          awardKey: current.awardKey,
          awardActive: false,
        }))
      }, HAND_CLEANUP_MS)
      const clearTimer = window.setTimeout(() => {
        setEvents((current) => ({ ...current, holeDeal: false, phaseFlash: null }))
      }, HAND_CLEANUP_MS + TRANSIENT_MS)
      timersRef.current.push(cleanupTimer, clearTimer)
      return
    }

    const presentationPrev = awardBaselineRef.current ?? prev
    const frames = buildStreetFrames(presentationPrev, game)
    const showdownIndex = frames.findIndex((frame) =>
      frame.phase === 'COMPLETE' &&
      frame.completionReason === 'SHOWDOWN' &&
      frame.awards.length > 0,
    )
    const showdownFrame = showdownIndex >= 0 ? frames[showdownIndex] : null
    const firstFrameRaw = showdownIndex === 0 && showdownFrame
      ? buildShowdownPrelude(presentationPrev, showdownFrame)
      : frames[0]
    const stagesSettlement = awardBaselineRef.current !== null && game.awards.length > 0
    const firstFrameBase = stagesSettlement
      ? stageAwardsUntilArrival(awardBaselineRef.current, firstFrameRaw)
      : firstFrameRaw
    const chipMotionMs = diff.collectingBets.length > 0
      ? BET_COLLECTION_MS
      : diff.chipFlySeats.size > 0
        ? BET_FLIGHT_MS
        : 0
    const stagesPot = !diff.awardsStarted && diff.potChanged && chipMotionMs > 0
    const firstFrame = stagesPot
      ? stagePotUntilArrival(eventsRef.current.displayGame ?? prev, firstFrameBase)
      : firstFrameBase
    // A street transition is two distinct visual events: wager chips leave the
    // felt, then the next board card(s) are dealt. COMPLETE-at-river already
    // uses its showdown prelude as the collection frame, so it needs no extra
    // initial stage.
    const collectionDelay = diff.collectingBets.length > 0 ? BET_COLLECTION_MS : 0
    const delaysFirstFrame = collectionDelay > 0 && showdownIndex !== 0
    const timelineOffset = delaysFirstFrame ? collectionDelay : 0
    const immediateFrame = delaysFirstFrame
      ? stageBoardUntilBetsCollected(presentationPrev, firstFrame)
      : firstFrame
    const firstPhase = showdownIndex === 0
      ? null
      : frames.length > 1
        ? firstFrame.phase === 'COMPLETE' ? 'SHOWDOWN' : firstFrame.phase
        : diff.phaseFlash
    const firstBoardFrom = prev && prev.handId === game.handId ? prev.board.length : diff.boardDealFrom

    setEvents((current) => ({
      ...current,
      displayGame: immediateFrame,
      boardDealFrom: delaysFirstFrame ? null : firstBoardFrom,
      potPopKey: diff.potChanged && !stagesPot && !stagesSettlement && !diff.awardsStarted
        ? current.potPopKey + 1
        : current.potPopKey,
      chipFlySeats: diff.chipFlySeats,
      collectingBets: diff.collectingBets,
      holeDeal: diff.holeDeal,
      handCleanup: false,
      phaseFlash: delaysFirstFrame ? null : firstPhase,
      phaseFlashKey: !delaysFirstFrame && firstPhase ? current.phaseFlashKey + 1 : current.phaseFlashKey,
      action: diff.action,
      awardKey:
        !delaysFirstFrame && diff.awardsStarted && firstFrame.phase === 'COMPLETE' && showdownIndex < 0
          ? current.awardKey + 1
          : current.awardKey,
      awardActive:
        !delaysFirstFrame && diff.awardsStarted && firstFrame.phase === 'COMPLETE' && showdownIndex < 0,
    }))

    if (delaysFirstFrame) {
      const firstFrameTimer = window.setTimeout(() => {
        setEvents((current) => ({
          ...current,
          displayGame: firstFrame,
          boardDealFrom: firstBoardFrom,
          chipFlySeats: EMPTY_SET,
          collectingBets: [],
          phaseFlash: firstPhase,
          phaseFlashKey: firstPhase ? current.phaseFlashKey + 1 : current.phaseFlashKey,
          action: null,
          awardKey:
            diff.awardsStarted && firstFrame.phase === 'COMPLETE' && showdownIndex < 0
              ? current.awardKey + 1
              : current.awardKey,
          awardActive: diff.awardsStarted && firstFrame.phase === 'COMPLETE' && showdownIndex < 0,
        }))
      }, collectionDelay)
      timersRef.current.push(firstFrameTimer)
    } else if (showdownIndex === 0 && collectionDelay > 0) {
      // Let the last river chips visibly land in the stack before any hole
      // cards turn over.
      const collectionTimer = window.setTimeout(() => {
        setEvents((current) => ({ ...current, collectingBets: [] }))
      }, collectionDelay)
      timersRef.current.push(collectionTimer)
    }

    if (stagesPot) {
      const potTimer = window.setTimeout(() => {
        setEvents((current) => {
          if (!current.displayGame || current.displayGame.handId !== game.handId) return current
          return {
            ...current,
            displayGame: {
              ...current.displayGame,
              potTotal: game.potTotal,
              pots: game.pots,
            },
            potPopKey: current.potPopKey + 1,
          }
        })
      }, chipMotionMs)
      timersRef.current.push(potTimer)
    }

    const lastImmediateFrame = showdownIndex >= 0 ? showdownIndex : frames.length
    for (let index = 1; index < lastImmediateFrame; index += 1) {
      const frame = frames[index]
      const previousLength = frames[index - 1].board.length
      const timer = window.setTimeout(() => {
        setEvents((current) => ({
          ...current,
          displayGame: frame,
          boardDealFrom: previousLength,
          chipFlySeats: EMPTY_SET,
          collectingBets: [],
          phaseFlash: frame.phase === 'COMPLETE' ? 'SHOWDOWN' : frame.phase,
          phaseFlashKey: current.phaseFlashKey + 1,
          action: null,
          awardKey:
            frame.phase === 'COMPLETE' && frame.awards.length > 0
              ? current.awardKey + 1
              : current.awardKey,
          awardActive: frame.phase === 'COMPLETE' && frame.awards.length > 0,
        }))
      }, timelineOffset + index * STREET_STEP_MS)
      timersRef.current.push(timer)
    }

    if (showdownFrame && showdownIndex >= 0) {
      const preludeAt = timelineOffset + showdownIndex * STREET_STEP_MS
      if (showdownIndex > 0) {
        const preludeTimer = window.setTimeout(() => {
          setEvents((current) => ({
            ...current,
            displayGame: buildShowdownPrelude(presentationPrev, showdownFrame),
            boardDealFrom: null,
            chipFlySeats: EMPTY_SET,
            collectingBets: [],
            phaseFlash: null,
            action: null,
            awardActive: false,
          }))
        }, preludeAt)
        timersRef.current.push(preludeTimer)
      }

      const revealAt = preludeAt + SHOWDOWN_COLLECT_MS
      const revealTimer = window.setTimeout(() => {
        setEvents((current) => ({
          ...current,
          displayGame: showdownFrame,
          boardDealFrom: null,
          chipFlySeats: EMPTY_SET,
          collectingBets: [],
          phaseFlash: 'SHOWDOWN',
          phaseFlashKey: current.phaseFlashKey + 1,
          action: null,
          awardActive: false,
        }))
      }, revealAt)

      const awardAt = revealAt + SHOWDOWN_READING_MS
      const awardTimer = window.setTimeout(() => {
        setEvents((current) => ({
          ...current,
          phaseFlash: null,
          awardKey: current.awardKey + 1,
          awardActive: true,
        }))
      }, awardAt)

      const clearTimer = window.setTimeout(() => {
        awardBaselineRef.current = null
        setEvents((current) => ({
          ...current,
          displayGame: game,
          boardDealFrom: null,
          potPopKey: current.potPopKey + 1,
          chipFlySeats: EMPTY_SET,
          collectingBets: [],
          holeDeal: false,
          phaseFlash: null,
          action: null,
          awardActive: false,
        }))
      }, awardAt + AWARD_FLIGHT_MS)
      timersRef.current.push(revealTimer, awardTimer, clearTimer)
      return
    }

    const clearTimer = window.setTimeout(() => {
      if (diff.awardsStarted) awardBaselineRef.current = null
      setEvents((current) => ({
        ...current,
        displayGame: diff.awardsStarted ? game : current.displayGame,
        boardDealFrom: null,
        potPopKey: diff.awardsStarted ? current.potPopKey + 1 : current.potPopKey,
        chipFlySeats: EMPTY_SET,
        collectingBets: [],
        holeDeal: false,
        phaseFlash: null,
        action: null,
        awardActive: false,
      }))
    }, Math.max(
      TRANSIENT_MS,
      timelineOffset + (frames.length - 1) * STREET_STEP_MS + TRANSIENT_MS,
      diff.awardsStarted ? timelineOffset + AWARD_FLIGHT_MS : 0,
    ))
    timersRef.current.push(clearTimer)
  }, [game, reducedMotion])

  return events
}
