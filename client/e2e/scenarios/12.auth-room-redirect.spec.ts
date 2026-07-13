import { expect, test } from '../fixtures/contexts'

const ROOM_PATH = '/room/a055e903-b2bd-40f2-8bcf-e8a09863d6f3'
const ROOM_URL = `http://localhost:5173${ROOM_PATH}`

test('an unauthenticated room visit resumes after login', async ({ browser }) => {
  const context = await browser.newContext()
  const page = await context.newPage()

  await page.goto(ROOM_URL)
  await page.waitForURL('**/')
  await expect(page.getByRole('button', { name: 'Sign in as Guest' })).toBeVisible()

  await page.getByRole('button', { name: 'Sign in as Guest' }).click()
  await page.getByPlaceholder('your name').fill(`redirect-${Date.now().toString(36).slice(-6)}`)
  await page.getByRole('button', { name: 'Play', exact: true }).click()

  await page.waitForURL(ROOM_URL)
  await expect.poll(() => page.evaluate(() => window.location.pathname)).toBe(ROOM_PATH)

  await context.close()
})
