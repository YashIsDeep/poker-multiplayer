import { useEffect } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { bindAutoUnlock } from './lib/sound'
import Landing from './pages/Landing'
import Lobby from './pages/Lobby'
import Room from './pages/Room'
import ConnectionBanner from './components/resilience/ConnectionBanner'
import ToastHost from './components/resilience/ToastHost'
import RoomClosedModal from './components/resilience/RoomClosedModal'
import RoomGoneModal from './components/resilience/RoomGoneModal'
import TabTakeoverModal from './components/resilience/TabTakeoverModal'
import './styles/resilience.css'

export default function App() {
  // Resume the Web Audio context on the first user gesture so the earliest
  // sound effect isn't swallowed by the browser autoplay policy.
  useEffect(() => bindAutoUnlock(), [])

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/lobby" element={<Lobby />} />
        <Route path="/room/:roomId" element={<Room />} />
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
