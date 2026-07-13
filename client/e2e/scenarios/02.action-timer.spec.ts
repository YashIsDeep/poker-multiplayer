// Scenario 02 — action timer expiry → auto-check/fold, player marked sitting out.
// Can be disabled with E2E_GAME=0 when debugging only room transport.

import {
  createTable,
  expect,
  GAME_ENABLED,
  joinTableByName,
  RUN_ID,
  takeSeat,
  test,
  waitForVisibleOnAny,
} from '../fixtures/contexts'

test('idling past the action timer auto-folds and marks the player sitting out', async ({ alice, bob }) => {
  test.skip(!GAME_ENABLED, 'gameplay coverage disabled with E2E_GAME=0')

  await createTable(alice, `Timer ${RUN_ID}`, 2)
  await joinTableByName(bob, `Timer ${RUN_ID}`)
  await takeSeat(alice, 0)
  await takeSeat(bob, 1)

  // Neither player acts; wait past the timer budget.
  await alice.waitForTimeout(35_000)

  // The idler is auto-folded/checked and flagged sitting out.
  const idler = await waitForVisibleOnAny([alice, bob], '.badge.sitout-b', 40_000)
  await expect(idler.locator('.badge.sitout-b')).toBeVisible()
})
