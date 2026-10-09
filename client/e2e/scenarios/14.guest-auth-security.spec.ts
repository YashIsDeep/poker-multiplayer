import { devLogin, expect, test } from '../fixtures/contexts'

test('duplicate guest names cannot recover another guest account', async ({ browser }) => {
  const first = await browser.newContext()
  const second = await browser.newContext()
  try {
    const alice = await devLogin(first, 'same-name')
    const impostor = await devLogin(second, 'same-name')
    const firstIdentity = await (await alice.request.get('/api/me')).json()
    const secondIdentity = await (await impostor.request.get('/api/me')).json()
    expect(firstIdentity.displayName).toBe(secondIdentity.displayName)
    expect(firstIdentity.id).not.toBe(secondIdentity.id)
  } finally {
    await first.close()
    await second.close()
  }
})

test('guest login requires POST and the csrf token from the same session', async ({ browser }) => {
  const first = await browser.newContext()
  const second = await browser.newContext()
  try {
    expect((await first.request.get('/auth/dev?u=csrf-attack', { maxRedirects: 0 })).status()).toBe(401)
    expect((await first.request.post('/auth/dev', { form: { u: 'csrf-attack' } })).status()).toBe(403)
    const csrf = await (await first.request.get('/auth/dev/csrf')).json()
    expect((await second.request.post('/auth/dev', {
      form: { u: 'csrf-attack' }, headers: { [csrf.headerName]: csrf.token },
    })).status()).toBe(403)
    expect((await second.request.get('/api/me')).status()).toBe(401)
    for (const header of ['Forwarded', 'X-Forwarded-For']) {
      expect((await first.request.get('/auth/dev/csrf', {
        headers: { [header]: 'for=127.0.0.1' },
      })).status()).toBe(403)
    }
  } finally {
    await first.close()
    await second.close()
  }
})
