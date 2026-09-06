import { createTable, expect, RUN_ID, test, waitForLobby } from '../fixtures/contexts'

test('last-member leave deletes the room and its join code', async ({ alice }) => {
  const table = `GC ${RUN_ID}`
  await createTable(alice, table, 2)
  const code = (await alice.getByRole('button', { name: /^Copy room code/ }).textContent())?.trim() ?? ''
  await alice.getByRole('button', { name: 'Leave table' }).click()
  await waitForLobby(alice)
  await expect(alice.locator(`[data-room-name="${table}"]`)).toHaveCount(0)
  await alice.getByLabel('Join code').fill(code)
  await alice.locator('.join-code').getByRole('button', { name: 'Join', exact: true }).click()
  await expect(alice.getByTestId('toasts')).toContainText('Invalid table code')
  await expect(alice.getByTestId('modal-room-gone')).toBeHidden()
})

test('reserved scenario 11: nine-seat layout renders every seat', async ({ alice }) => {
  await alice.setViewportSize({ width: 390, height: 844 })
  await alice.goto('/room/mock-room?mock=turn&seats=9')
  await expect(alice.locator('.seat-anchor')).toHaveCount(9)
  const fitsViewport = await alice.evaluate(() => {
    const viewportWidth = window.innerWidth
    const criticalElements = [
      ...document.querySelectorAll('.seat'),
      ...document.querySelectorAll('.actionbar .btn'),
      document.querySelector('.btn-leave'),
    ].filter((element): element is Element => element !== null)
    return criticalElements.every((element) => {
      const rect = element.getBoundingClientRect()
      return rect.left >= -1 && rect.right <= viewportWidth + 1
    })
  })
  expect(fitsViewport).toBe(true)
})

test('reserved scenario 12: reduced-motion and fallback path remains playable', async ({ alice }) => {
  await alice.emulateMedia({ reducedMotion: 'reduce' })
  await alice.goto('/room/mock-room?mock=turn&seats=2')
  await expect(alice.getByTestId('actionbar-turn')).toBeVisible()
  await expect(alice.locator('#swirl')).toBeVisible()
})
