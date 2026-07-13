import type { HandHistory, HandHistoryEvent, HandHistoryResponse } from '../store/types'
import { formatChips } from './chips'

export async function fetchHandHistories(
  roomId: string,
  signal?: AbortSignal,
): Promise<HandHistory[]> {
  const response = await fetch(`/api/rooms/${encodeURIComponent(roomId)}/hand-histories`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw new Error(`History request failed (${response.status})`)
  const body = (await response.json()) as HandHistoryResponse
  return body.hands
}

export function describeHistoryEvent(event: HandHistoryEvent, hand: HandHistory): string {
  const player = (seat: number | null) =>
    seat === null
      ? 'Player'
      : (hand.players.find((candidate) => candidate.seat === seat)?.displayName ??
        `Seat ${seat + 1}`)
  const chips = (amount: number | null) => formatChips(amount ?? 0)

  switch (event.type) {
    case 'BLIND_POSTED':
      return `${player(event.seat)} posts ${event.action === 'SMALL_BLIND' ? 'small' : 'big'} blind ${chips(event.amount)}`
    case 'PLAYER_ACTED':
      if (event.action === 'FOLD') return `${player(event.seat)} folds`
      if (event.action === 'CHECK') return `${player(event.seat)} checks`
      if (event.action === 'CALL') return `${player(event.seat)} calls ${chips(event.amount)}`
      return `${player(event.seat)} raises to ${chips(event.total)}`
    case 'UNCALLED_BET_REFUNDED':
      return `${chips(event.amount)} returned to ${player(event.seat)}`
    case 'STREET_DEALT':
      return `${event.phase.toLowerCase()} dealt · ${event.cards.join(' ')}`
    case 'POT_AWARDED':
      return `${event.potIndex === 0 ? 'Main pot' : `Side pot ${event.potIndex}`} · ${event.winners
        .map((winner) => `${player(winner.seat)} wins ${formatChips(winner.amount)}`)
        .join(' · ')}`
    case 'HAND_COMPLETED':
      return event.action === 'SHOWDOWN' ? 'Showdown complete' : 'Hand won by fold'
  }
}
