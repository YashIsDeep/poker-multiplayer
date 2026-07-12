import { useEffect, useState } from 'react'

type Meta = { title: string; tagline: string }

export default function Landing() {
  const [meta, setMeta] = useState<Meta | null>(null)

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
      <div className="landing-actions">
        <a className="btn btn-red" href="/oauth2/authorization/google">
          Sign in with Google
        </a>
        <a className="btn btn-blue" href="/oauth2/authorization/discord">
          Sign in with Discord
        </a>
      </div>
      <p className="server-status">
        server: {meta ? 'online' : 'connecting…'}
      </p>
    </main>
  )
}
