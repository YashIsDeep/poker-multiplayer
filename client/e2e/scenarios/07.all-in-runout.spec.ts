import { createTable, expect, joinTableByName, RUN_ID, takeSeat, test, waitForTurn } from '../fixtures/contexts'

test('all-in and call run the board to showdown without further input', async ({ alice, bob }) => {
  const table = `Allin ${RUN_ID}`
  await createTable(alice, table, 2)
  await joinTableByName(bob, table)
  await takeSeat(alice, 0)
  await takeSeat(bob, 1)

  const raiser = await waitForTurn([alice, bob])
  await raiser.getByRole('button', { name: 'All-in' }).click()
  // The all-in button pulses continuously; exercise its keyboard activation.
  await raiser.getByRole('button', { name: /^Go all-in to/ }).press('Enter')
  const caller = await waitForTurn(raiser === alice ? [bob] : [alice])
  await caller.getByRole('button', { name: /Call/ }).click()

  await expect(alice.getByTestId('showdown-banner')).toBeVisible({ timeout: 25_000 })
  await expect(alice.locator('[data-testid="board"] .card[data-card]')).toHaveCount(5)
})
