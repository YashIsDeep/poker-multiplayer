import { useCallback, useEffect, useRef, useState } from 'react'
import { chooseAutoSeat } from '../lib/autoSeat'
import { useGameStore } from '../store/gameStore'
import type { RoomSnapshot } from '../store/types'

const AUTO_PICK_SECONDS = 3

export default function AutoSeatPrompt({ room }: { room: RoomSnapshot }) {
  const autoSeatRoomId = useGameStore((state) => state.autoSeatRoomId)
  const dismissAutoSeat = useGameStore((state) => state.dismissAutoSeat)
  const pushToast = useGameStore((state) => state.pushToast)
  const [remaining, setRemaining] = useState(AUTO_PICK_SECONDS)
  const [picking, setPicking] = useState(false)
  const pickInFlight = useRef(false)
  const cancelled = useRef(false)

  const active =
    autoSeatRoomId === room.roomId &&
    !room.viewer.seated &&
    room.viewer.canTakeSeat

  const pickSeat = useCallback(async () => {
    if (pickInFlight.current || cancelled.current) return
    pickInFlight.current = true
    setPicking(true)

    const state = useGameStore.getState()
    const latestRoom = state.room
    if (
      state.autoSeatRoomId !== room.roomId ||
      latestRoom?.roomId !== room.roomId ||
      latestRoom.viewer.seated ||
      !latestRoom.viewer.canTakeSeat
    ) {
      state.dismissAutoSeat()
      return
    }

    const seat = chooseAutoSeat(latestRoom)
    if (seat === null) {
      state.dismissAutoSeat()
      state.pushToast('info', 'Table filled up', 'you are spectating instead')
      return
    }

    try {
      const ack = await state.takeSeat(seat)
      if (cancelled.current) return
      if (ack.ok) {
        state.pushToast('ok', `Took seat ${seat + 1}`)
      } else {
        state.dismissAutoSeat()
        state.pushToast('info', 'Auto-pick missed', 'choose any open seat to sit down')
      }
    } catch {
      if (!cancelled.current) {
        state.dismissAutoSeat()
        state.pushToast('warn', 'Auto-pick failed', 'choose any open seat to sit down')
      }
    }
  }, [room.roomId])

  useEffect(() => {
    if (!active) return

    cancelled.current = false
    pickInFlight.current = false
    setPicking(false)
    setRemaining(AUTO_PICK_SECONDS)

    const deadline = Date.now() + AUTO_PICK_SECONDS * 1000
    const interval = window.setInterval(() => {
      setRemaining(Math.max(1, Math.ceil((deadline - Date.now()) / 1000)))
    }, 200)
    const timeout = window.setTimeout(() => {
      window.clearInterval(interval)
      void pickSeat()
    }, AUTO_PICK_SECONDS * 1000)

    return () => {
      window.clearInterval(interval)
      window.clearTimeout(timeout)
    }
  }, [active, pickSeat, room.roomId])

  if (!active) return null

  return (
    <div className="modalback auto-seat-backdrop" data-testid="auto-seat-prompt">
      <section
        className="modal panel auto-seat-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="auto-seat-title"
        aria-describedby="auto-seat-description"
      >
        <span className="auto-seat-kicker">WELCOME TO THE TABLE</span>
        <h3 id="auto-seat-title">Auto-picking a seat in</h3>
        <div className="auto-seat-count num" aria-live="polite">
          {picking ? '…' : remaining}
        </div>
        <p id="auto-seat-description">
          {picking ? 'Finding an open seat…' : 'We’ll choose an open seat so you can jump straight in.'}
        </p>
        <div className="auto-seat-pips" aria-hidden="true">
          {Array.from({ length: AUTO_PICK_SECONDS }, (_, index) => (
            <span key={index} className={index < remaining && !picking ? 'lit' : ''} />
          ))}
        </div>
        <div className="auto-seat-actions">
          <button
            type="button"
            className="btn btn-orange auto-seat-join"
            data-testid="auto-seat-join"
            data-sound="confirm"
            disabled={picking}
            onClick={() => void pickSeat()}
          >
            Join now
          </button>
          <button
            type="button"
            className="btn btn-dev auto-seat-spectate"
            data-sound="close"
            disabled={picking}
            onClick={() => {
              cancelled.current = true
              dismissAutoSeat()
              pushToast('info', 'Spectating table', 'click any open seat whenever you want to play')
            }}
          >
            Spectate Instead
          </button>
        </div>
      </section>
    </div>
  )
}
