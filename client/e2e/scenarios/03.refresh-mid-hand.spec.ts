// Scenario 03 — hard refresh: session cookie re-auths, state.sync restores the
// same room and seat (rejoin-as-resync). Hole-card restoration is gated on T6.

import { createTable, expect, GAME_ENABLED, joinTableByName, RUN_ID, takeSeat, test } from '../fixtures/contexts'

test('hard refresh restores membership, room, and seat', async ({ alice, bob }) => {
  await createTable(alice, `Refresh ${RUN_ID}`, 6)
  await joinTableByName(bob, `Refresh ${RUN_ID}`)
  await takeSeat(alice, 0)
  await takeSeat(bob, 1)

  await expect(alice.locator('.seat.you .pname')).toContainText('ALICE', { ignoreCase: true })

  await alice.reload()

  // Same room, same seat, without re-joining manually.
  await expect(alice.locator('[data-testid="room"]')).toBeVisible({ timeout: 10_000 })
  await expect(alice.locator('.seat.you .pname')).toContainText('ALICE', { ignoreCase: true })
  await expect(alice.locator('[data-seat="1"] .pname')).toContainText('BOB', { ignoreCase: true })

  if (GAME_ENABLED) {
    // Mid-hand: own hole cards restored after refresh.
    await expect(alice.locator('.seat.you .mini-cards .card[data-card]').first()).toBeVisible({
      timeout: 15_000,
    })
  }
})
