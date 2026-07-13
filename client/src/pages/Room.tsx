// Room view (T7): store-driven table, action area, and topbar, wearing the
// approved table-green.html skin. T8 layers swirl/CRT/juice on the same DOM.

import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import SwirlBackground from '../components/SwirlBackground'
import CrtOverlay from '../components/CrtOverlay'
import Table from '../components/Table'
import ActionBar from '../components/ActionBar'
import ShowdownBanner from '../components/ShowdownBanner'
import { useGameStore } from '../store/gameStore'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { useAnimationQueue } from '../hooks/useAnimationQueue'
import { startMockDriver } from '../dev/mockGame'
import '../styles/table.css'

export default function Room() {
  const { roomId } = useParams()
  const [search] = useSearchParams()
  const navigate = useNavigate()

  const init = useGameStore((s) => s.init)
  const connection = useGameStore((s) => s.connection)
  const room = useGameStore((s) => s.room)
  const game = useGameStore((s) => s.game)
  const leaveRoom = useGameStore((s) => s.leaveRoom)
  const pushToast = useGameStore((s) => s.pushToast)
  const stand = useGameStore((s) => s.stand)
  const roomClosed = useGameStore((s) => s.roomClosed)
  const roomGone = useGameStore((s) => s.roomGone)
  const takenOver = useGameStore((s) => s.takenOver)

  const reducedMotion = useReducedMotion()
  const animations = useAnimationQueue(game, reducedMotion)

  const mockState = import.meta.env.DEV ? search.get('mock') : null
  const mockStarted = useRef(false)
  const [leaving, setLeaving] = useState(false)

  useEffect(() => {
    if (mockState) {
      if (!mockStarted.current) {
        mockStarted.current = true
        startMockDriver(mockState, search)
      }
      return
    }
    init()
  }, [init, mockState, search])

  // Wrong/absent room after the state sync settles → back to the lobby
  // (unless a resilience modal is explaining why).
  useEffect(() => {
    if (mockState || connection !== 'connected') return
    if (roomClosed || roomGone || takenOver) return
    if (room === null || (roomId && room.roomId !== roomId)) {
      const timer = setTimeout(() => {
        const current = useGameStore.getState()
        if (
          current.connection === 'connected' &&
          !current.roomClosed &&
          !current.roomGone &&
          (current.room === null || (roomId && current.room.roomId !== roomId))
        ) {
          navigate('/lobby', { replace: true })
        }
      }, 1500)
      return () => clearTimeout(timer)
    }
  }, [mockState, connection, room, roomId, roomClosed, roomGone, takenOver, navigate])

  if (!room) {
    return (
      <div className="stage room-stage">
        <SwirlBackground />
        <div className="room-splash">
          <p className="waiting">Connecting…</p>
        </div>
        <CrtOverlay />
      </div>
    )
  }

  return (
    <div className="stage room-stage" data-testid="room">
      <SwirlBackground />

      <div className="room-topbar">
        <div className="room-tag panel">
          <b>{room.name}</b>
          <span className="label">code</span>
          <b className="num">{room.joinCode}</b>
          {game && (
            <>
              <span className="label">hand</span>
              <b className="num">#{game.handId}</b>
            </>
          )}
        </div>
        <div className="room-topbar-actions">
          {room.viewer.canStand && (
            <button className="btn btn-dev btn-leave" onClick={() => void stand()}>
              Stand
            </button>
          )}
          <button
            className="btn btn-red btn-leave"
            disabled={leaving}
            onClick={async () => {
              if (leaving) return
              setLeaving(true)
              try {
                const ack = await leaveRoom()
                if (ack?.ok) navigate('/lobby')
              } catch {
                pushToast('warn', 'Leave failed', 'could not reach the server')
              } finally {
                setLeaving(false)
              }
            }}
          >
            {leaving ? 'Leaving…' : 'Leave table'}
          </button>
        </div>
      </div>

      <Table room={room} game={game} animations={animations} />
      <ShowdownBanner />
      <ActionBar />

      <CrtOverlay />
    </div>
  )
}
