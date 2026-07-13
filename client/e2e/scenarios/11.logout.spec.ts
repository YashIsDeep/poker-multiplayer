import { expect, test } from '../fixtures/contexts'

test('logout ends the session and returns to the landing page', async ({ alice }) => {
  await alice.getByRole('button', { name: 'Log out' }).click()

  await alice.waitForURL('**/')
  await expect
    .poll(() => alice.evaluate(() => fetch('/api/me').then((response) => response.status)))
    .toBe(401)
})
