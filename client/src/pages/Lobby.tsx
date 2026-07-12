import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

type Me = {
  id: string
  displayName: string
  avatarUrl: string | null
  provider: 'GOOGLE' | 'DISCORD' | 'DEV'
}

export default function Lobby() {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)
  const navigate = useNavigate()

  useEffect(() => {
    let cancelled = false

    fetch('/api/me', { credentials: 'same-origin' })
      .then((response) => {
        if (response.status === 401) {
          navigate('/', { replace: true })
          return null
        }
        if (!response.ok) throw new Error('Unable to load the current user')
        return response.json() as Promise<Me>
      })
      .then((currentUser) => {
        if (!cancelled && currentUser) setMe(currentUser)
      })
      .catch(() => {
        if (!cancelled) navigate('/', { replace: true })
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [navigate])

  return (
    <main className="lobby">
      <h1>Lobby</h1>
      {loading ? <p>Loading your seat…</p> : me && <p>Welcome, {me.displayName}</p>}
      <p>Room list arrives in P4.</p>
    </main>
  )
}
