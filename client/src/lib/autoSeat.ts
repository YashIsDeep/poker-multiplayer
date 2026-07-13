import type { RoomSnapshot } from '../store/types'

type SeatInventory = Pick<RoomSnapshot, 'maxSeats' | 'seats'>

export function availableSeatNumbers(room: SeatInventory): number[] {
  const occupied = new Set(
    room.seats.filter((seat) => seat.userId !== null).map((seat) => seat.seat),
  )
  return Array.from({ length: room.maxSeats }, (_, seat) => seat).filter(
    (seat) => !occupied.has(seat),
  )
}

export function chooseAutoSeat(
  room: SeatInventory,
  random: () => number = Math.random,
): number | null {
  const available = availableSeatNumbers(room)
  if (available.length === 0) return null

  const randomIndex = Math.min(
    available.length - 1,
    Math.max(0, Math.floor(random() * available.length)),
  )
  return available[randomIndex]
}
