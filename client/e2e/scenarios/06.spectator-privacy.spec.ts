import { createTable, expect, joinTableByName, loginGuestPage, RUN_ID, takeSeat, test } from '../fixtures/contexts'

test('spectator DOM and received STOMP frames never expose seated hole cards', async ({ alice, bob, browser }) => {
  const table = `Private ${RUN_ID}`
  await createTable(alice, table, 3)
  await joinTableByName(bob, table)
  await takeSeat(alice, 0)
  await takeSeat(bob, 1)
  await expect(alice.locator('.seat.you .card[data-card]')).toHaveCount(2, { timeout: 15_000 })
  const ownCards = await alice.locator('.seat.you .card[data-card]').all()
  const cardCodes = await Promise.all(ownCards.map((card) => card.getAttribute('data-card')))

  const context = await browser.newContext()
  const frames: string[] = []
  const watched = await context.newPage()
  watched.on('websocket', (socket) => {
    socket.on('framereceived', (event) => frames.push(String(event.payload)))
  })
  await loginGuestPage(watched, `watch-${RUN_ID.slice(-8)}`)
  await joinTableByName(watched, table)
  await expect(watched.locator('.seat .card[data-card]')).toHaveCount(0)
  await watched.waitForTimeout(500)
  const payloads = frames.join('\n')
  for (const code of cardCodes) if (code) expect(payloads).not.toContain(`\"${code}\"`)
  await context.close()
})
