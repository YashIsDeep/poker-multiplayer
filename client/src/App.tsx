import { BrowserRouter, Route, Routes } from 'react-router-dom'
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
