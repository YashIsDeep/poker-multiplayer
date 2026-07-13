import { createTable, expect, RUN_ID, takeSeat, test } from '../fixtures/contexts'

test('newest same-session tab wins and the older tab can reclaim control', async ({ alice }) => {
  await createTable(alice, `Tabs ${RUN_ID}`, 2)
  await takeSeat(alice, 0)
  const roomUrl = alice.url()

  const newer = await alice.context().newPage()
  await newer.goto(roomUrl)
  await expect(newer.getByTestId('room')).toBeVisible({ timeout: 15_000 })
  await expect(alice.getByTestId('modal-takeover')).toBeVisible({ timeout: 10_000 })
  await expect(alice.getByTestId('actionbar-reconnecting')).toBeVisible()

  await alice.getByRole('button', { name: 'Use here instead' }).click()
  await expect(newer.getByTestId('modal-takeover')).toBeVisible({ timeout: 10_000 })
  await expect(alice.getByTestId('modal-takeover')).toBeHidden()
})
