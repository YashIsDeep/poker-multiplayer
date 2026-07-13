// T10: blocking modal on ROOM_NOT_FOUND (server restart / room GC'd).

import { useNavigate } from 'react-router-dom'
import { useGameStore } from '../../store/gameStore'

export default function RoomGoneModal() {
  const roomGone = useGameStore((s) => s.roomGone)
  const acknowledge = useGameStore((s) => s.acknowledgeRoomGone)
  const navigate = useNavigate()

  if (!roomGone) return null

  return (
    <div className="modalback" data-testid="modal-room-gone">
      <div className="modal panel">
        <h3>This table is gone</h3>
        <p>
          It may have been closed or the server restarted. Your seat and chips for this table are
          cleared.
        </p>
        <div className="row">
          <button
            className="btn btn-blue"
            onClick={() => {
              acknowledge()
              navigate('/lobby')
            }}
          >
            Back to lobby
          </button>
        </div>
      </div>
    </div>
  )
}
