import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { bindAutoUnlock, bindGlobalClickSound } from './lib/sound'
import { useExperienceSounds } from './hooks/useExperienceSounds'
import {
  rememberPostLoginRedirect,
  takePostLoginRedirect,
} from './lib/postLoginRedirect'
import Landing from './pages/Landing'
import Lobby from './pages/Lobby'
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
  const [resumePath, setResumePath] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setStatus('checking')
    setResumePath(null)

    fetch('/api/me', { credentials: 'same-origin' })
      .then((response) => {
        if (response.status === 401) return false
        if (!response.ok) throw new Error('unable to check the current session')
        return true
      })
      .then((authenticated) => {
        if (cancelled) return

        if (!authenticated) {
          if (location.pathname.startsWith('/room/')) {
            rememberPostLoginRedirect(
              `${location.pathname}${location.search}${location.hash}`,
              window.sessionStorage,
            )
          }
          setStatus('unauthenticated')
          return
        }

        if (location.pathname === '/lobby') {
          setResumePath(takePostLoginRedirect(window.sessionStorage))
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
  if (status === 'unauthenticated') return <Navigate to="/" replace />
  if (resumePath && resumePath !== `${location.pathname}${location.search}${location.hash}`) {
    return <Navigate to={resumePath} replace />
  }
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
        <Route path="/" element={<Landing />} />
        <Route
          path="/lobby"
          element={
            <RequireAuth>
              <Lobby />
            </RequireAuth>
          }
        />
        <Route
          path="/room/:roomId"
          element={
            <RequireAuth>
              <Room />
            </RequireAuth>
          }
        />
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
