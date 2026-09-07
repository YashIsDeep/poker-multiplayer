export const RANKED_SETTLED_EVENT = 'poker:ranked-settled'

export type RankedPlayer = {
  userId: string
  displayName: string
  avatarUrl: string | null
  bbPer100: number | null
  netBB: number
  handCount: number
  eligible: boolean
  rank: number | null
}

export type RankedBoard = {
  players: RankedPlayer[]
  page: number
  pageSize: number
  totalPlayers: number
  qualifyingHands: number
}

export function formatBB(value: number | null): string {
  return value === null ? '—' : value.toLocaleString('en-US', {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
  })
}

/** Leaderboard columns read as gains and losses, so keep the direction explicit. */
export function formatSignedBB(value: number | null): string {
  return value === null || value <= 0 ? formatBB(value) : `+${formatBB(value)}`
}
