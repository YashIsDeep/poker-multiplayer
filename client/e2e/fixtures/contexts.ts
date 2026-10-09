// Shared fixtures: three isolated browser contexts (alice, bob, spectator),
// each dev-logged-in through the CSRF-protected POST endpoint.
// User names are unique per run so parallel/local re-runs don't collide.

import { test as base } from '@playwright/test'
import type { BrowserContext, Page, TestInfo } from '@playwright/test'

export const RUN_ID = `${Date.now().toString(36)}`

// T6 is part of the current baseline, so gameplay coverage runs by default.
// E2E_GAME=0 remains useful when debugging only the T5 room/lobby transport.
export const GAME_ENABLED = process.env.E2E_GAME !== '0'
export const KILL_SERVER_ENABLED = process.env.E2E_KILL_SERVER === '1'

/** The lobby lives at the app root, so match on the pathname rather than a glob. */
export async function waitForLobby(page: Page): Promise<void> {
  await page.waitForURL((url) => url.pathname === '/')
}

export async function devLogin(context: BrowserContext, name: string): Promise<Page> {
  const page = await context.newPage()
  await loginGuestPage(page, name)
  return page
}

export async function loginGuestPage(page: Page, name: string): Promise<void> {
  const csrfResponse = await page.request.get('/auth/dev/csrf')
  if (!csrfResponse.ok()) throw new Error(`Guest CSRF bootstrap failed: ${csrfResponse.status()}`)
  const csrf = await csrfResponse.json() as { headerName: string; token: string }
  const loginResponse = await page.request.post('/auth/dev', {
    headers: { [csrf.headerName]: csrf.token },
    form: { u: name },
    maxRedirects: 0,
  })
  if (loginResponse.status() !== 302) throw new Error(`Guest login failed: ${loginResponse.status()}`)
  await page.goto('/')
  await waitForLobby(page)
}

function userName(prefix: string, testInfo: TestInfo): string {
  let hash = 0
  for (const char of testInfo.testId) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return `${prefix}-${RUN_ID.slice(-5)}-${hash.toString(36).slice(-5)}`
}

type Fixtures = {
  alice: Page
  bob: Page
  spectator: Page
}

export const test = base.extend<Fixtures>({
  alice: async ({ browser }, use, testInfo) => {
    const context = await browser.newContext()
    const page = await devLogin(context, userName('alice', testInfo))
    await use(page)
    await context.close()
  },
  bob: async ({ browser }, use, testInfo) => {
    const context = await browser.newContext()
    const page = await devLogin(context, userName('bob', testInfo))
    await use(page)
    await context.close()
  },
  spectator: async ({ browser }, use, testInfo) => {
    const context = await browser.newContext()
    const page = await devLogin(context, userName('spec', testInfo))
    await use(page)
    await context.close()
  },
})

export { expect } from '@playwright/test'

/** These scenarios choose explicit seats; dismiss the normal automatic-seat offer. */
async function keepManualSeating(page: Page): Promise<void> {
  await page.getByTestId('room').waitFor()
  const spectate = page.getByRole('button', { name: 'Spectate Instead', exact: true })
  // The room snapshot can navigate before the create/join acknowledgment
  // activates this offer. Wait for that UI transition rather than sampling it.
  await spectate.waitFor({ state: 'visible', timeout: 1_500 }).catch(() => {})
  if (await spectate.isVisible()) await spectate.click()
}

/** Create a table through the lobby UI; resolves once inside the room. */
export async function createTable(page: Page, name: string, _seats = 6): Promise<void> {
  await page.getByRole('button', { name: '+ Create table' }).click()
  await page.getByLabel('Table name').fill(name)
  // Public tables have a fixed six-seat capacity, including heads-up games.
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await page.waitForURL('**/room/**')
  await keepManualSeating(page)
}

/** Join a table from the lobby list by its displayed name. */
export async function joinTableByName(page: Page, name: string): Promise<void> {
  const row = page.locator(`[data-room-name="${name}"]`)
  await row.getByRole('button', { name: /Join|Spectate/ }).click()
  await page.waitForURL('**/room/**')
  await keepManualSeating(page)
}

/** Take the first free seat pod. */
export async function takeSeat(page: Page, seat: number): Promise<void> {
  await page.getByRole('button', { name: `Take seat ${seat + 1}` }).click()
}

/** Resolve the page whose viewer currently owns the action controls. */
export async function waitForTurn(pages: Page[], timeoutMs = 15_000): Promise<Page> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    for (const page of pages) {
      if (await page.locator('[data-testid="actionbar-turn"]').isVisible().catch(() => false)) {
        return page
      }
    }
    await pages[0].waitForTimeout(100)
  }
  throw new Error(`no player received action controls within ${timeoutMs}ms`)
}

/** Resolve the first page where a selector becomes visible without cross-frame locators. */
export async function waitForVisibleOnAny(
  pages: Page[],
  selector: string,
  timeoutMs = 15_000,
): Promise<Page> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    for (const page of pages) {
      if (await page.locator(selector).isVisible().catch(() => false)) return page
    }
    await pages[0].waitForTimeout(100)
  }
  throw new Error(`${selector} was not visible on any page within ${timeoutMs}ms`)
}
