import { describe, expect, it } from 'vitest'
import { availableSeatNumbers, chooseAutoSeat } from './autoSeat'

describe('auto seat selection', () => {
  it('returns every currently open seat', () => {
    expect(
      availableSeatNumbers({
        maxSeats: 6,
        seats: [
          { seat: 0, userId: 'u-1' },
          { seat: 1, userId: null },
          { seat: 2, userId: 'u-2' },
          { seat: 3, userId: null },
          { seat: 4, userId: null },
          { seat: 5, userId: 'u-3' },
        ],
      }),
    ).toEqual([1, 3, 4])
  })

  it('uses the random value within the open-seat list', () => {
    const room = {
      maxSeats: 6,
      seats: [
        { seat: 0, userId: 'u-1' },
        { seat: 1, userId: null },
        { seat: 2, userId: 'u-2' },
        { seat: 3, userId: null },
        { seat: 4, userId: null },
        { seat: 5, userId: 'u-3' },
      ],
    }

    expect(chooseAutoSeat(room, () => 0)).toBe(1)
    expect(chooseAutoSeat(room, () => 0.5)).toBe(3)
    expect(chooseAutoSeat(room, () => 0.999)).toBe(4)
  })

  it('returns null when the table is full', () => {
    expect(
      chooseAutoSeat({
        maxSeats: 2,
        seats: [
          { seat: 0, userId: 'u-1' },
          { seat: 1, userId: 'u-2' },
        ],
      }),
    ).toBeNull()
  })
})
