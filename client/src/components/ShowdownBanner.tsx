// T8: center gold banner announcing showdown winners (banner-in entrance).

import { useGameStore } from '../store/gameStore'
import { formatChips, formatChipsFull } from '../lib/chips'

export default function ShowdownBanner() {
  const room = useGameStore((state) => state.room)
  const game = useGameStore((state) => state.game)
  if (!room || !game || game.phase !== 'COMPLETE' || game.awards.length === 0) return null

  const winnerAwards = game.awards.flatMap((award) => award.winners)
  const winnerSeats = [...new Set(winnerAwards.map((winner) => winner.seat))]
  const names = winnerSeats.map((seat) => {
    const occupant = room.seats.find((candidate) => candidate.seat === seat)
    return occupant
      ? (room.members.find((member) => member.userId === occupant.userId)?.displayName ?? 'PLAYER')
      : 'PLAYER'
  })
  const amount = winnerAwards.reduce((total, winner) => total + winner.amount, 0)
  return (
    <div className="showdown panel" data-testid="showdown-banner">
      <div className="sd-who">{names.join(' & ').toUpperCase()} WIN</div>
      <div className="sd-amt num" title={`${formatChipsFull(amount)} chips`}>
        {formatChips(amount)} AWARDED
      </div>
      <div className="sd-hand">
        {game.awards.length === 1 ? 'MAIN POT' : `${game.awards.length} POTS SETTLED`}
      </div>
    </div>
  )
}
