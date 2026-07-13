// T10: blocking modal on room.closed (RoomExitNotice for the current room),
// with a 3s auto-redirect countdown to the lobby.

import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGameStore } from '../../store/gameStore'

export default function RoomClosedModal() {
  const roomClosed = useGameStore((s) => s.roomClosed)
  const acknowledge = useGameStore((s) => s.acknowledgeRoomClosed)
  const navigate = useNavigate()
  const [remaining, setRemaining] = useState(3)

  useEffect(() => {
    if (!roomClosed) {
      setRemaining(3)
      return
    }
    const timer = setInterval(() => setRemaining((r) => r - 1), 1000)
    return () => clearInterval(timer)
  }, [roomClosed])

  useEffect(() => {
    if (roomClosed && remaining <= 0) {
      acknowledge()
      navigate('/lobby')
    }
  }, [roomClosed, remaining, acknowledge, navigate])

  if (!roomClosed) return null

  return (
    <div className="modalback" data-testid="modal-room-closed">
      <div className="modal panel">
        <h3>Table closed</h3>
        <p>
          This table was shut down. Taking you back to the lobby in{' '}
          <span className="num">{Math.max(remaining, 0)}</span>s…
        </p>
        <div className="row">
          <button
            className="btn btn-blue"
            onClick={() => {
              acknowledge()
              navigate('/lobby')
            }}
          >
            Back to lobby now
          </button>
        </div>
      </div>
    </div>
  )
}
