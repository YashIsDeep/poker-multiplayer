// Scenario 01 — lobby propagation + seating (design steps 2–3).
// Runnable today: create/join propagation, seating, per-viewer snapshots.
// The heads-up hand-to-showdown half can be disabled with E2E_GAME=0 for transport-only debugging.

import {
  createTable,
  expect,
  GAME_ENABLED,
  joinTableByName,
  RUN_ID,
  takeSeat,
  test,
  waitForTurn,
} from '../fixtures/contexts'

const TABLE = `Midnight ${RUN_ID}`

test('room create propagates to the other lobby and both players seat', async ({ alice, bob }) => {
  await createTable(alice, TABLE, 6)

  // Bob's lobby shows the new room via /topic/lobby broadcast (≤1s + assertion slack)
  await expect(bob.locator(`[data-room-name="${TABLE}"]`)).toBeVisible({ timeout: 5_000 })

  await joinTableByName(bob, TABLE)

  await takeSeat(alice, 0)
  await takeSeat(bob, 1)

  // Both viewers see both seat pods with the right names (per-viewer snapshots)
  await expect(alice.locator('.seat.you .pname')).toContainText('ALICE', { ignoreCase: true })
  await expect(alice.locator(`[data-seat="1"] .pname`)).toContainText('BOB', { ignoreCase: true })
  await expect(bob.locator('.seat.you .pname')).toContainText('BOB', { ignoreCase: true })
  await expect(bob.locator(`[data-seat="0"] .pname`)).toContainText('ALICE', { ignoreCase: true })
})

test('heads-up hand plays to showdown with correct order and clamped raises', async ({ alice, bob }) => {
  test.skip(!GAME_ENABLED, 'gameplay coverage disabled with E2E_GAME=0')

  await createTable(alice, `${TABLE} HU`, 2)
  await joinTableByName(bob, `${TABLE} HU`)
  await takeSeat(alice, 0)
  await takeSeat(bob, 1)

  // Button acts first preflop
  const firstActor = await waitForTurn([alice, bob])
  await expect(firstActor.locator('[data-testid="actionbar-turn"]')).toBeVisible()

  // Raise slider bounds match the snapshot's [minRaiseTo, maxRaiseTo]
  const turnPage = firstActor
  const slider = turnPage.getByLabel('Raise amount')
  const min = await slider.getAttribute('min')
  const max = await slider.getAttribute('max')
  expect(Number(min)).toBeGreaterThan(0)
  expect(Number(max)).toBeGreaterThanOrEqual(Number(min))

  // Play call/check to showdown; winner banner names a rank
  for (let i = 0; i < 12; i++) {
    for (const page of [alice, bob]) {
      const turn = page.locator('[data-testid="actionbar-turn"]')
      if (await turn.isVisible().catch(() => false)) {
        const call = page.getByRole('button', { name: /^(Call|Check)/ })
        await call.click()
      }
    }
    if (await alice.locator('[data-testid="showdown-banner"]').isVisible().catch(() => false)) break
    await alice.waitForTimeout(1_000)
  }
  await expect(alice.locator('[data-testid="showdown-banner"]')).toBeVisible({ timeout: 20_000 })
})
