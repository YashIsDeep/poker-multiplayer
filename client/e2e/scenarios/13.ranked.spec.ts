import { expect, test, waitForTurn } from '../fixtures/contexts'

test('ranked queue, cancellation, automatic seating, earnings and public profile', async ({ alice, bob, spectator, browser }) => {
  const meta = await (await alice.request.get('/api/meta')).json()
  test.skip(meta.rankedEnabled !== 'true', 'Enable POKER_RANKED_ENABLED=true to exercise ranked play')
  const pages = [alice, bob, spectator]
  const identities = await Promise.all(pages.map(async page => (await (await page.request.get('/api/me')).json()).id as string))

  await alice.getByRole('button', { name: 'Play Ranked', exact: true }).click()
  await expect(alice.getByRole('button', { name: 'Cancel queue' })).toBeVisible()
  await alice.getByRole('button', { name: 'Cancel queue' }).click()
  await expect(alice.getByRole('button', { name: 'Play Ranked', exact: true })).toBeVisible()

  for (const page of pages) await page.getByRole('button', { name: 'Play Ranked', exact: true }).click()
  for (const page of pages) await page.waitForURL('**/room/**')
  expect(new Set(pages.map(p => p.url())).size).toBe(1)
  await expect(alice.getByRole('button', { name: /Take seat/ })).toHaveCount(0)
  for (let i = 0; i < 2; i++) {
    const actor = await waitForTurn(pages)
    await actor.getByRole('button', { name: 'Fold', exact: true }).click()
    await expect(actor.locator('[data-testid="actionbar-turn"]')).not.toBeVisible()
  }
  await expect.poll(async () => (await (await alice.request.get(`/api/players/${identities[0]}`)).json()).handCount).toBe(1)
  const results = await Promise.all(identities.map(async id => (await alice.request.get(`/api/players/${id}`)).json()))
  expect(results.reduce((sum, p) => sum + p.netBB, 0)).toBe(0)
  for (const player of results) {
    expect(player.handCount).toBe(1)
    expect(player.eligible).toBe(false)
    expect(player.rank).toBeNull()
    expect(player).not.toHaveProperty('holeCards')
    expect(player).not.toHaveProperty('providerSubject')
  }

  await alice.reload()
  await expect(alice.locator('.seat.you')).toBeVisible()
  await expect(alice.getByRole('button', { name: /Take seat/ })).toHaveCount(0)

  const anonymous = await browser.newContext()
  try {
    const page = await anonymous.newPage()
    await page.goto(`/players/${identities[0]}`)
    await expect(page.getByTestId('ranked-profile')).toContainText('Provisional player')
    await expect(page.getByTestId('ranked-profile')).toContainText('1 / 1,000 hands')
    await page.goto('/leaderboard')
    await expect(page.getByRole('heading', { name: 'Let the hands speak.' })).toBeVisible()
    await expect(page.getByText('All-time ranked · 3–6 players')).toBeVisible()
    const board = await (await page.request.get('/api/leaderboard')).json()
    expect(board.players.every((p: { handCount: number }) => p.handCount >= 1000)).toBe(true)
  } finally { await anonymous.close() }
})
