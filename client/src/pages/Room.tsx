// Room view (T7): store-driven table, action area, and topbar, wearing the
// approved table-green.html skin. T8 layers swirl/CRT/juice on the same DOM.

import { useEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import SwirlBackground from '../components/SwirlBackground'
import CrtOverlay from '../components/CrtOverlay'
import Table from '../components/Table'
import ActionBar from '../components/ActionBar'
import ShowdownBanner from '../components/ShowdownBanner'
import SettingsModal from '../components/SettingsModal'
import RoomMenu from '../components/RoomMenu'
import type { RoomMenuItem } from '../components/RoomMenu'
import AutoSeatPrompt from '../components/AutoSeatPrompt'
import TableSettingsModal from '../components/TableSettingsModal'
import HandHistoryDrawer from '../components/HandHistoryDrawer'
import ChatPanel from '../components/ChatPanel'
import ShowdownResultsModal from '../components/ShowdownResultsModal'
import TrophyIcon from '../components/TrophyIcon'
import { formatChips } from '../lib/chips'
import { isCompletedHandResult } from '../lib/showdownResults'
import { serverNow, useGameStore } from '../store/gameStore'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { useAnimationQueue } from '../hooks/useAnimationQueue'
import { useGameSounds } from '../hooks/useGameSounds'
import { startMockDriver } from '../dev/mockGame'
import '../styles/table.css'
import '../styles/chat.css'

async function writeToClipboard(text: string) {
  if (navigator.clipboard?.writeText) {
    let timeoutId: number | undefined
    try {
      await Promise.race([
        navigator.clipboard.writeText(text),
        new Promise<never>((_, reject) => {
          timeoutId = window.setTimeout(() => reject(new Error('clipboard write timed out')), 750)
        }),
      ])
      return
    } catch {
      // Restricted browsers can reject or leave the Clipboard API pending.
    } finally {
      if (timeoutId !== undefined) window.clearTimeout(timeoutId)
    }
  }

  const previousFocus = document.activeElement as HTMLElement | null
  const input = document.createElement('textarea')
  input.value = text
  input.setAttribute('readonly', '')
  input.style.position = 'fixed'
  input.style.opacity = '0'
  document.body.appendChild(input)
  input.select()
  const copied = document.execCommand('copy')
  input.remove()
  previousFocus?.focus()
  if (!copied) throw new Error('clipboard write failed')
}

type TrophyFlight = {
  left: number
  top: number
  dx: number
  dy: number
}

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
  const tableGame = animations.displayGame
  useGameSounds(game, yourSeat)

  const mockState = import.meta.env.DEV ? search.get('mock') : null
  const mockStarted = useRef(false)
  const [leaving, setLeaving] = useState(false)
  const [sittingOut, setSittingOut] = useState(false)
  const [keepingAlive, setKeepingAlive] = useState(false)
  const [tableSettingsOpen, setTableSettingsOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [handHistoryOpen, setHandHistoryOpen] = useState(false)
  const [showdownOpen, setShowdownOpen] = useState(false)
  const [lastHandResult, setLastHandResult] = useState<typeof game>(null)
  const [trophyFlight, setTrophyFlight] = useState<TrophyFlight | null>(null)
  const [clock, setClock] = useState(() => serverNow())
  const trophyOriginRef = useRef<HTMLSpanElement>(null)
  const trophyButtonRef = useRef<HTMLButtonElement>(null)
  const trophyFlightHandRef = useRef<number | null>(null)

  const handIsOver = isCompletedHandResult(game)
  const handId = game?.handId ?? null

  useEffect(() => {
    if (!handIsOver || !game) return
    // Keep the newest complete snapshot: the first frame may arrive before
    // the authoritative best-five projection is available to the client.
    setLastHandResult(game)
  }, [game, handIsOver])

  useEffect(() => {
    if (reducedMotion || !handIsOver || handId === null) return
    if (trophyFlightHandRef.current === handId) return

    const origin = trophyOriginRef.current?.getBoundingClientRect()
    const target = trophyButtonRef.current?.getBoundingClientRect()
    if (!origin || !target || origin.width === 0 || target.width === 0) return

    trophyFlightHandRef.current = handId
    setTrophyFlight({
      left: origin.left + origin.width / 2,
      top: origin.top + origin.height / 2,
      dx: target.left + target.width / 2 - (origin.left + origin.width / 2),
      dy: target.top + target.height / 2 - (origin.top + origin.height / 2),
    })
    const timer = window.setTimeout(() => setTrophyFlight(null), 1300)
    return () => window.clearTimeout(timer)
  }, [handId, handIsOver, reducedMotion])

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

  const copyRoomCode = async () => {
    try {
      await writeToClipboard(room.joinCode)
      pushToast('ok', 'Copied!')
    } catch {
      pushToast('warn', 'Copy failed', 'select the room code and copy it manually')
    }
  }

  const handleSitOut = async () => {
    if (sittingOut) return
    setSittingOut(true)
    try {
      const ack = await sitOut()
      if (!ack?.ok) setSittingOut(false)
    } catch {
      setSittingOut(false)
      pushToast('warn', 'Sit out failed', 'could not reach the server')
    }
  }

  const handleLeave = async () => {
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
  }

  const canSitOut = room.viewer.canStand && viewerSeatStatus !== 'SITTING_OUT'

  // Same actions as the individual topbar buttons; CSS swaps in this menu on
  // small screens where the buttons don't fit.
  const menuItems: RoomMenuItem[] = [
    ...(room.canEditSettings
      ? [
          {
            key: 'table-settings',
            label: 'Table settings',
            sound: 'open' as const,
            onClick: () => setTableSettingsOpen(true),
          },
        ]
      : []),
    {
      key: 'settings',
      label: 'Settings',
      sound: 'open' as const,
      onClick: () => setSettingsOpen(true),
    },
    ...(canSitOut
      ? [
          {
            key: 'sit-out',
            label: sittingOut ? 'Sitting out…' : 'Sit out',
            sound: 'fold' as const,
            disabled: sittingOut,
            onClick: () => void handleSitOut(),
          },
        ]
      : []),
    ...(room.viewer.canStand
      ? [{ key: 'stand', label: 'Stand', sound: 'exit' as const, onClick: () => void stand() }]
      : []),
    {
      key: 'leave',
      label: leaving ? 'Leaving…' : 'Leave table',
      sound: 'exit' as const,
      danger: true,
      disabled: leaving,
      onClick: () => void handleLeave(),
    },
  ]

  return (
    <div className="stage room-stage" data-testid="room">
      <SwirlBackground />

      <div className="room-topbar">
        <div className="room-tag panel">
          <b>{room.name}</b>
          <span className="label">code</span>
          <button
            type="button"
            className="room-code num"
            aria-label={`Copy room code ${room.joinCode}`}
            title="Copy room code"
            data-sound="confirm"
            onClick={() => void copyRoomCode()}
          >
            {room.joinCode}
          </button>
          <span className="table-config-chip table-config-chip--blind num" title="Current big blind">
            BB {formatChips(room.config.bigBlind)}
          </span>
          <span
            className="table-config-chip table-config-chip--stack num"
            title={`${formatChips(room.config.startingStack ?? room.config.minBuyIn)} chips for future seats`}
          >
            STACK {formatChips(
              (room.config.startingStack ?? room.config.minBuyIn) / room.config.bigBlind,
            )} BB
          </span>
          {tableGame && (
            <>
              <span className="label">hand</span>
              <b className="num">#{tableGame.handId}</b>
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
          <button
            type="button"
            className="btn btn-dev settings-btn"
            data-sound="open"
            aria-haspopup="dialog"
            aria-expanded={settingsOpen}
            aria-label="Settings"
            title="Settings"
            onClick={() => setSettingsOpen(true)}
          >
            <span aria-hidden="true">⚙️</span>
          </button>
          {canSitOut && (
            <button
              className="btn btn-dev btn-leave"
              data-sound="fold"
              disabled={sittingOut}
              onClick={() => void handleSitOut()}
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
            onClick={() => void handleLeave()}
          >
            {leaving ? 'Leaving…' : 'Leave table'}
          </button>
          <RoomMenu items={menuItems} />
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

      <Table room={room} game={tableGame} animations={animations} />
      <ChatPanel
        room={room}
        game={game}
        showdownOpen={showdownOpen}
        trophyButtonRef={trophyButtonRef}
        onOpenShowdown={() => setShowdownOpen(true)}
      />
      <ShowdownBanner trophyRef={trophyOriginRef} />
      {trophyFlight && (
        <span
          className="trophy-flight"
          data-testid="trophy-flight"
          aria-hidden="true"
          style={
            {
              left: `${trophyFlight.left}px`,
              top: `${trophyFlight.top}px`,
              '--trophy-dx': `${trophyFlight.dx}px`,
              '--trophy-dy': `${trophyFlight.dy}px`,
            } as CSSProperties
          }
        >
          <TrophyIcon />
        </span>
      )}
      <ActionBar presentationLocked={animations.handCleanup} />

      <AutoSeatPrompt room={room} />

      {tableSettingsOpen && <TableSettingsModal onClose={() => setTableSettingsOpen(false)} />}
      {settingsOpen && (
        <SettingsModal
          onClose={() => setSettingsOpen(false)}
          onHandHistory={() => {
            setSettingsOpen(false)
            setHandHistoryOpen(true)
          }}
        />
      )}
      {handHistoryOpen && (
        <HandHistoryDrawer roomId={room.roomId} onClose={() => setHandHistoryOpen(false)} />
      )}
      {showdownOpen && (
        <ShowdownResultsModal
          room={room}
          game={lastHandResult}
          onClose={() => setShowdownOpen(false)}
        />
      )}

      <CrtOverlay />
    </div>
  )
}
