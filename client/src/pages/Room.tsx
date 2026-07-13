// Room view (T7): store-driven table, action area, and topbar, wearing the
// approved table-green.html skin. T8 layers swirl/CRT/juice on the same DOM.

import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import SwirlBackground from '../components/SwirlBackground'
import CrtOverlay from '../components/CrtOverlay'
import Table from '../components/Table'
import ActionBar from '../components/ActionBar'
import ShowdownBanner from '../components/ShowdownBanner'
import SettingsButton from '../components/SettingsButton'
import TableSettingsModal from '../components/TableSettingsModal'
import { formatChips } from '../lib/chips'
import { serverNow, useGameStore } from '../store/gameStore'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { useAnimationQueue } from '../hooks/useAnimationQueue'
import { useGameSounds } from '../hooks/useGameSounds'
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
  const yourSeat = useGameStore((s) => s.yourSeat)
  const leaveRoom = useGameStore((s) => s.leaveRoom)
  const pushToast = useGameStore((s) => s.pushToast)
  const sitOut = useGameStore((s) => s.sitOut)
  const stand = useGameStore((s) => s.stand)
  const roomClosed = useGameStore((s) => s.roomClosed)
  const roomGone = useGameStore((s) => s.roomGone)
  const takenOver = useGameStore((s) => s.takenOver)
  const keepRoomAlive = useGameStore((s) => s.keepRoomAlive)

  const reducedMotion = useReducedMotion()
  const animations = useAnimationQueue(game, reducedMotion)
  useGameSounds(game, yourSeat)

  const mockState = import.meta.env.DEV ? search.get('mock') : null
  const mockStarted = useRef(false)
  const [leaving, setLeaving] = useState(false)
  const [sittingOut, setSittingOut] = useState(false)
  const [keepingAlive, setKeepingAlive] = useState(false)
  const [tableSettingsOpen, setTableSettingsOpen] = useState(false)
  const [clock, setClock] = useState(() => serverNow())

  const viewerSeatStatus =
    game && yourSeat !== null
      ? game.seats.find((seat) => seat.seat === yourSeat)?.seatStatus
      : undefined

  useEffect(() => {
    if (viewerSeatStatus === 'SITTING_OUT') setSittingOut(false)
  }, [viewerSeatStatus])

  useEffect(() => {
    const timer = window.setInterval(() => setClock(serverNow()), 1000)
    return () => window.clearInterval(timer)
  }, [])

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

  const idleDeadline = Date.parse(room.idleExpiresAt)
  const idleRemaining = Number.isNaN(idleDeadline) ? Number.POSITIVE_INFINITY : idleDeadline - clock
  const showIdleWarning = idleRemaining > 0 && idleRemaining <= 5 * 60_000
  const idleMinutes = Math.max(0, Math.ceil(idleRemaining / 60_000))

  return (
    <div className="stage room-stage" data-testid="room">
      <SwirlBackground />

      <div className="room-topbar">
        <div className="room-tag panel">
          <b>{room.name}</b>
          <span className="label">code</span>
          <b className="num">{room.joinCode}</b>
          <span className="table-config-chip num" title="Current big blind">
            BB {formatChips(room.config.bigBlind)}
          </span>
          <span
            className="table-config-chip num"
            title={`${formatChips(room.config.startingStack ?? room.config.minBuyIn)} chips for future seats`}
          >
            STACK {formatChips(
              (room.config.startingStack ?? room.config.minBuyIn) / room.config.bigBlind,
            )} BB
          </span>
          {game && (
            <>
              <span className="label">hand</span>
              <b className="num">#{game.handId}</b>
            </>
          )}
        </div>
        <div className="room-topbar-actions">
          {room.canEditSettings && (
            <button
              type="button"
              className="btn btn-dev btn-table-settings"
              data-sound="open"
              title="Edit table settings"
              onClick={() => setTableSettingsOpen(true)}
            >
              <span className="admin-shield" aria-hidden="true">A</span>
              <span className="table-settings-button-label">Table settings</span>
            </button>
          )}
          <SettingsButton />
          {room.viewer.canStand && viewerSeatStatus !== 'SITTING_OUT' && (
            <button
              className="btn btn-dev btn-leave"
              data-sound="fold"
              disabled={sittingOut}
              onClick={async () => {
                if (sittingOut) return
                setSittingOut(true)
                try {
                  const ack = await sitOut()
                  if (!ack?.ok) setSittingOut(false)
                } catch {
                  setSittingOut(false)
                  pushToast('warn', 'Sit out failed', 'could not reach the server')
                }
              }}
            >
              {sittingOut ? 'Sitting out…' : 'Sit out'}
            </button>
          )}
          {room.viewer.canStand && (
            <button className="btn btn-dev btn-leave" data-sound="exit" onClick={() => void stand()}>
              Stand
            </button>
          )}
          <button
            className="btn btn-red btn-leave"
            data-sound="exit"
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

      {showIdleWarning && (
        <div className="idle-warning panel" role="status">
          <span>This table closes for inactivity in about {idleMinutes} min.</span>
          <button
            className="btn btn-orange"
            data-sound="confirm"
            disabled={keepingAlive || connection !== 'connected'}
            onClick={async () => {
              if (keepingAlive) return
              setKeepingAlive(true)
              try {
                const ack = await keepRoomAlive()
                if (!ack.ok) pushToast('warn', 'Keep-alive rejected')
              } catch {
                pushToast('warn', 'Keep-alive failed', 'could not reach the server')
              } finally {
                setKeepingAlive(false)
              }
            }}
          >
            {keepingAlive ? 'Keeping open…' : 'Keep table open'}
          </button>
        </div>
      )}

      <Table room={room} game={game} animations={animations} />
      <ShowdownBanner />
      <ActionBar />

      {tableSettingsOpen && <TableSettingsModal onClose={() => setTableSettingsOpen(false)} />}

      <CrtOverlay />
    </div>
  )
}
