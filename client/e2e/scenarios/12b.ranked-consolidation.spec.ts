import type { Page } from '@playwright/test'
import { devLogin, expect, RUN_ID, test, waitForLobby, waitForVisibleOnAny } from '../fixtures/contexts'

async function finishOneHand(pages: Page[]) {
  for (let i = 0; i < 9; i++) {
    const page = await waitForVisibleOnAny(pages, '[data-testid="actionbar-turn"], [data-testid="actionbar-showdown"]')
    if (await page.getByTestId('actionbar-showdown').isVisible()) return
    await page.getByRole('button', { name: 'Fold', exact: true }).click()
    await expect(page.getByTestId('actionbar-turn')).toBeHidden()
  }
  throw new Error('hand did not finish')
}

test('small ranked tables combine after hands and reconnect restores the destination', async ({ browser }) => {
  test.setTimeout(90_000)
  const contexts = await Promise.all(Array.from({ length: 9 }, () => browser.newContext()))
  try {
    const pages = await Promise.all(contexts.map((context, i) => devLogin(context, `merge-${RUN_ID.slice(-5)}-${i}`)))
    const meta = await (await pages[0].request.get('/api/meta')).json()
    test.skip(meta.rankedEnabled !== 'true', 'Ranked play must be enabled')
    await Promise.all(pages.map(p => p.getByRole('button', { name: 'Play Ranked', exact: true }).click()))
    await Promise.all(pages.map(p => p.waitForURL('**/room/**')))
    const groups = new Map<string, Page[]>()
    for (const page of pages) groups.set(page.url(), [...(groups.get(page.url()) ?? []), page])
    expect([...groups.values()].map(g => g.length).sort()).toEqual([3, 6])
    const large = [...groups.values()].find(g => g.length === 6)!
    const small = [...groups.values()].find(g => g.length === 3)!
    const destination = large[0].url()
    const source = small[0].url()
    await expect(large[0].locator('.seat.you .pstack')).toContainText('BB')
    // Standing queues departure after the current hand, even if some players
    // joined after it began. Keep those players out of the next hand.
    await Promise.all(large.slice(0, 3).map(p => p.getByRole('button', { name: 'Stand', exact: true }).click()))
    await finishOneHand(large)
    for (const page of large.slice(0, 3)) {
      await page.getByRole('button', { name: 'Leave table', exact: true }).click()
      await waitForLobby(page)
    }
    await finishOneHand(small)
    const remaining = [...large.slice(3), ...small]
    for (const page of remaining) await expect(page).toHaveURL(destination, { timeout: 15_000 })
    expect(source).not.toBe(destination)
    await expect(small[0].getByTestId('toasts')).toContainText('Ranked tables combined')
    await expect(small[0].locator('.seat:not(.empty)')).toHaveCount(6)
    await expect(small[0].getByRole('button', { name: /Take seat/ })).toHaveCount(0)
    await small[0].reload()
    await expect(small[0]).toHaveURL(destination)
    await expect(small[0].locator('.seat.you')).toBeVisible()
    await expect(small[0].getByTestId('modal-room-gone')).toBeHidden()
  } finally {
    await Promise.all(contexts.map(c => c.close().catch(() => {})))
  }
})
