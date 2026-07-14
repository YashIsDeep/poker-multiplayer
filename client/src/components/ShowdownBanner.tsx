// T8: compact result banner above the showdown action area (banner-in entrance).

import type { Ref } from 'react'
import { useGameStore } from '../store/gameStore'
import { formatChips, formatChipsFull } from '../lib/chips'
import TrophyIcon from './TrophyIcon'
import WinConfetti from './WinConfetti'

export default function ShowdownBanner({ trophyRef }: { trophyRef?: Ref<HTMLSpanElement> }) {
  const room = useGameStore((state) => state.room)
  const game = useGameStore((state) => state.game)
  const yourSeat = useGameStore((state) => state.yourSeat)
  if (!room || !game || game.phase !== 'COMPLETE' || game.awards.length === 0) return null

  const winnerAwards = game.awards.flatMap((award) => award.winners)
  const winnerSeats = [...new Set(winnerAwards.map((winner) => winner.seat))]
  const viewerWon = yourSeat !== null && winnerSeats.includes(yourSeat)
  const names = winnerSeats.map((seat) => {
    const occupant = room.seats.find((candidate) => candidate.seat === seat)
    return occupant
      ? (room.members.find((member) => member.userId === occupant.userId)?.displayName ?? 'PLAYER')
      : 'PLAYER'
  })
  const amount = winnerAwards.reduce((total, winner) => total + winner.amount, 0)
  return (
    <div className="showdown panel" data-testid="showdown-banner">
      {viewerWon && <WinConfetti />}
      <div className="sd-who">
        <span ref={trophyRef} className="sd-trophy" aria-hidden="true">
          <TrophyIcon />
        </span>
        <span>{names.join(' & ').toUpperCase()} WIN</span>
      </div>
      <div className="sd-amt num" title={`${formatChipsFull(amount)} chips`}>
        {formatChips(amount)} AWARDED
      </div>
      <div className="sd-hand">
        {game.awards.length === 1 ? 'MAIN POT' : `${game.awards.length} POTS SETTLED`}
      </div>
    </div>
  )
}
