import type { Game, GameSeat } from '../store/types'

export type ShowdownCardFrame = 'gold' | 'silver'

/** A fold-win is still a completed hand result, even though it has no showdown. */
export function isCompletedHandResult(
  game: Pick<Game, 'phase' | 'completionReason' | 'awards'> | null,
): boolean {
  return game?.phase === 'COMPLETE' && game.awards.length > 0
}

/**
 * Folded opponents stay redacted, but the viewer can always see their own cards.
 * A showdown participant whose cards are redacted is represented by face-down
 * cards so the result still communicates that they reached the showdown.
 */
export function visibleResultHoleCards(
  player: Pick<GameSeat, 'seat' | 'seatStatus' | 'holeCards'>,
  viewerSeat: number | null,
  showdown: boolean,
): string[] | null {
  if (player.seat === viewerSeat) return player.holeCards
  if (player.seatStatus === 'FOLDED') return null
  return player.holeCards ?? (showdown ? ['back', 'back'] : null)
}

/** Player ownership wins if malformed data ever places the same card in both sets. */
export function showdownCardFrame(
  card: string,
  boardCards: readonly string[],
  playerCards: readonly string[] | null,
): ShowdownCardFrame | undefined {
  if (playerCards?.includes(card)) return 'silver'
  if (boardCards.includes(card)) return 'gold'
  return undefined
}
