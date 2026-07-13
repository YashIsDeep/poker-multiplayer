import { afterEach, describe, expect, it, vi } from 'vitest'
import { endSession } from './auth'

describe('endSession', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetches a session CSRF token before posting logout', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'session-token' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await endSession()

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/csrf', { credentials: 'same-origin' })
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-XSRF-TOKEN': 'session-token' },
      redirect: 'manual',
    })
  })

  it('accepts Spring Security\'s manual logout redirect', async () => {
    const redirectResponse = new Response(null, { status: 302 })
    Object.defineProperty(redirectResponse, 'type', { value: 'opaqueredirect' })
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'session-token' }), {
          status: 200,
        }),
      )
      .mockResolvedValueOnce(redirectResponse)
    vi.stubGlobal('fetch', fetchMock)

    await expect(endSession()).resolves.toBeUndefined()
  })

  it('does not post logout when the CSRF token cannot be loaded', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(endSession()).rejects.toThrow('csrf fetch failed: 401')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('reports a rejected logout request', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'session-token' }), {
          status: 200,
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(endSession()).rejects.toThrow('logout failed: 403')
  })
})
