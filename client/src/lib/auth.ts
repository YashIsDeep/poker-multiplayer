type CsrfToken = {
  headerName: string
  token: string
}

export async function endSession(): Promise<void> {
  const csrfResponse = await fetch('/api/csrf', { credentials: 'same-origin' })
  if (!csrfResponse.ok) {
    throw new Error(`csrf fetch failed: ${csrfResponse.status}`)
  }

  const csrf = (await csrfResponse.json()) as CsrfToken
  const logoutResponse = await fetch('/logout', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { [csrf.headerName]: csrf.token },
    // Spring returns a success redirect. Do not follow it here: behind the
    // Vite proxy its absolute target is the server origin and would surface as
    // a CORS failure even though the session was already invalidated.
    redirect: 'manual',
  })
  if (!logoutResponse.ok && logoutResponse.type !== 'opaqueredirect') {
    throw new Error(`logout failed: ${logoutResponse.status}`)
  }
}
