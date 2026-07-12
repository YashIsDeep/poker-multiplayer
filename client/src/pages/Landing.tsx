import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'

type Meta = { title: string; tagline: string }

export default function Landing() {
  const [meta, setMeta] = useState<Meta | null>(null)
  const [guestMode, setGuestMode] = useState(false)
  const [guestName, setGuestName] = useState('')

  const signInAsGuest = (e: FormEvent) => {
    e.preventDefault()
    const name = guestName.trim()
    if (!name) return
    window.location.assign(`/auth/dev?u=${encodeURIComponent(name)}`)
  }

  useEffect(() => {
    let timer: number | undefined
    const load = () => {
      fetch('/api/meta')
        .then((r) => r.json())
        .then((d: Meta) => setMeta(d))
        .catch(() => {
          timer = window.setTimeout(load, 3000)
        })
    }
    load()
    return () => window.clearTimeout(timer)
  }, [])

  return (
    <main className="landing">
      <h1 className="logo">{meta ? meta.title : '…'}</h1>
      <p className="tagline">{meta ? meta.tagline : 'waking the server…'}</p>
      {import.meta.env.DEV && guestMode ? (
        <form className="guest-form" onSubmit={signInAsGuest}>
          <input
            className="guest-input"
            type="text"
            placeholder="your name"
            maxLength={20}
            value={guestName}
            onChange={(e) => setGuestName(e.target.value)}
            autoFocus
          />
          <button className="btn btn-green" type="submit" disabled={!guestName.trim()}>
            Play
          </button>
          <button className="btn-plain" type="button" onClick={() => setGuestMode(false)}>
            back
          </button>
        </form>
      ) : (
        <div className="landing-actions">
          <a className="btn btn-red" href="/oauth2/authorization/google">
            Sign in with Google
          </a>
          <a className="btn btn-blue" href="/oauth2/authorization/discord">
            Sign in with Discord
          </a>
          {import.meta.env.DEV && (
            <button className="btn btn-green" onClick={() => setGuestMode(true)}>
              Sign in as Guest
            </button>
          )}
        </div>
      )}
      <p className="server-status">
        server: {meta ? 'online' : 'connecting…'}
      </p>
    </main>
  )
}
