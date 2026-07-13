import { createTable, devLogin, expect, joinTableByName, RUN_ID, takeSeat, test } from '../fixtures/contexts'

test('closing a tab and logging in again restores the same seat', async ({ alice, bob, browser }) => {
  const table = `Rejoin ${RUN_ID}`
  await createTable(alice, table, 2)
  await joinTableByName(bob, table)
  await takeSeat(alice, 0)
  await takeSeat(bob, 1)

  const meResponse = await bob.request.get('/api/me')
  const me = (await meResponse.json()) as { displayName: string }
  await bob.context().close()

  const rejoinContext = await browser.newContext()
  const rejoined = await devLogin(rejoinContext, me.displayName)
  await expect(rejoined.getByTestId('room')).toBeVisible({ timeout: 15_000 })
  await expect(rejoined.locator('.seat.you .pname')).toContainText(me.displayName, { ignoreCase: true })
  await expect(rejoined.locator('[data-seat="0"] .pname')).toBeVisible()
  await rejoinContext.close()
})
