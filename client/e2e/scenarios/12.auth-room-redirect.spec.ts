import { expect, test } from '../fixtures/contexts'

const ROOM_PATH = '/room/a055e903-b2bd-40f2-8bcf-e8a09863d6f3'

test('an unauthenticated room visit resumes after login', async ({ browser }) => {
  const context = await browser.newContext()
  const page = await context.newPage()

  await page.goto(ROOM_PATH)
  await page.waitForURL((url) => url.pathname === '/login')
  await expect(page.getByRole('button', { name: 'Sign in as Guest' })).toBeVisible()

  await page.getByRole('button', { name: 'Sign in as Guest' }).click()
  await page.getByPlaceholder('your name').fill(`redirect-${Date.now().toString(36).slice(-6)}`)
  await page.getByRole('button', { name: 'Play', exact: true }).click()

  await page.waitForURL((url) => url.pathname === ROOM_PATH)
  await expect.poll(() => page.evaluate(() => window.location.pathname)).toBe(ROOM_PATH)

  await context.close()
})

test('the signed-out lobby offers sign-in instead of the live table list', async ({ browser }) => {
  const context = await browser.newContext()
  const page = await context.newPage()

  await page.goto('/')
  await expect(page.getByRole('link', { name: 'Sign In', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Play Ranked', exact: true })).toBeHidden()
  await expect(page.getByRole('button', { name: '+ Create table' })).toBeHidden()

  await page.getByRole('link', { name: 'Sign In', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/login')
  await expect(page.getByRole('button', { name: 'Sign in as Guest' })).toBeVisible()

  await context.close()
})
