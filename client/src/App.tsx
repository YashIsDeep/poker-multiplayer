import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { bindAutoUnlock, bindGlobalClickSound } from './lib/sound'
import { useExperienceSounds } from './hooks/useExperienceSounds'
import { rememberPostLoginRedirect } from './lib/postLoginRedirect'
import Landing from './pages/Landing'
import Lobby from './pages/Lobby'
import Leaderboard from './pages/Leaderboard'
import PlayerProfile from './pages/PlayerProfile'
import Room from './pages/Room'
import ConnectionBanner from './components/resilience/ConnectionBanner'
import ToastHost from './components/resilience/ToastHost'
import RoomClosedModal from './components/resilience/RoomClosedModal'
import RoomGoneModal from './components/resilience/RoomGoneModal'
import TabTakeoverModal from './components/resilience/TabTakeoverModal'
import './styles/resilience.css'

type AuthStatus = 'checking' | 'authenticated' | 'unauthenticated'

function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation()
  const [status, setStatus] = useState<AuthStatus>('checking')

  useEffect(() => {
    let cancelled = false
    setStatus('checking')

    fetch('/api/me', { credentials: 'same-origin' })
      .then((response) => {
        if (response.status === 401) return false
        if (!response.ok) throw new Error('unable to check the current session')
        return true
      })
      .then((authenticated) => {
        if (cancelled) return

        if (!authenticated) {
          rememberPostLoginRedirect(
            `${location.pathname}${location.search}${location.hash}`,
            window.sessionStorage,
          )
          setStatus('unauthenticated')
          return
        }

        setStatus('authenticated')
      })
      .catch(() => {
        if (!cancelled) setStatus('unauthenticated')
      })

    return () => {
      cancelled = true
    }
  }, [location.hash, location.pathname, location.search])

  if (status === 'checking') {
    return (
      <main className="landing">
        <p className="tagline">Checking session…</p>
      </main>
    )
  }
  if (status === 'unauthenticated') return <Navigate to="/login" replace />
  return children
}

function RedirectIfAuthenticated({ children }: { children: ReactNode }) {
  const [authenticated, setAuthenticated] = useState(false)

  useEffect(() => {
    let cancelled = false

    fetch('/api/me', { credentials: 'same-origin' })
      .then((response) => {
        if (!cancelled && response.ok) setAuthenticated(true)
      })
      .catch(() => {
        // A failed probe is not a signed-in session: leave the sign-in page up.
      })

    return () => {
      cancelled = true
    }
  }, [])

  // Render the sign-in page immediately instead of gating on the probe, so a
  // signed-out visitor never waits on it. A signed-in one sees the page flash
  // before the lobby takes over, where any remembered room is resumed.
  if (authenticated) return <Navigate to="/" replace />
  return children
}

export default function App() {
  useExperienceSounds()

  // Resume the Web Audio context on the first user gesture so the earliest
  // sound effect isn't swallowed by the browser autoplay policy. A capture-
  // phase listener also resolves one semantic click sound across the entire
  // app, including controls that stop propagation or are added in the future.
  useEffect(() => {
    bindAutoUnlock()
    return bindGlobalClickSound()
  }, [])

  return (
    <BrowserRouter>
      <Routes>
        {/* The lobby is the front door: signed-out visitors browse it and sign
            in from the header, the way lichess does. */}
        <Route path="/" element={<Lobby />} />
        <Route
          path="/login"
          element={
            <RedirectIfAuthenticated>
              <Landing />
            </RedirectIfAuthenticated>
          }
        />
        <Route path="/leaderboard" element={<Leaderboard />} />
        <Route path="/players/:userId" element={<PlayerProfile />} />
        <Route
          path="/room/:roomId"
          element={
            <RequireAuth>
              <Room />
            </RequireAuth>
          }
        />
        {/* Anything unrecognised falls back to the lobby rather than a blank page. */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      {/* T10 resilience layer: renders above whatever screen is active */}
      <ConnectionBanner />
      <ToastHost />
      <RoomClosedModal />
      <RoomGoneModal />
      <TabTakeoverModal />
    </BrowserRouter>
  )
}
