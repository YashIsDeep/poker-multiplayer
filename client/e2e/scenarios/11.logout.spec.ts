import { expect, test, waitForLobby } from '../fixtures/contexts'

test('logout ends the session and leaves the signed-out lobby', async ({ alice }) => {
  await alice.getByRole('button', { name: 'Log out' }).click()

  await waitForLobby(alice)
  await expect(alice.getByRole('link', { name: 'Sign In', exact: true })).toBeVisible()
  await expect
    .poll(() => alice.evaluate(() => fetch('/api/me').then((response) => response.status)))
    .toBe(401)
})
