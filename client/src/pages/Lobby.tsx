// Lobby (functional, Balatro-skinned): live room list from /topic/lobby,
// create-table modal, private-code join. Room create/join navigates into /room/:id.

import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import SwirlBackground from '../components/SwirlBackground'
import CrtOverlay from '../components/CrtOverlay'
import SettingsButton from '../components/SettingsButton'
import { useGameStore } from '../store/gameStore'
import { endSession } from '../lib/auth'
import { playSound } from '../lib/sound'
import { generateRandomTableName } from '../lib/tableNames'
import '../styles/lobby.css'

type Me = {
  id: string
  displayName: string
  avatarUrl: string | null
  provider: 'GOOGLE' | 'DISCORD' | 'DEV'
}

export default function Lobby() {
  const [me, setMe] = useState<Me | null>(null)
  const [creating, setCreating] = useState(false)
  const [joinCode, setJoinCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const navigate = useNavigate()

  const init = useGameStore((s) => s.init)
  const connection = useGameStore((s) => s.connection)
  const lobby = useGameStore((s) => s.lobby)
  const room = useGameStore((s) => s.room)
  const joinRoom = useGameStore((s) => s.joinRoom)
  const pushToast = useGameStore((s) => s.pushToast)

  useEffect(() => {
    let cancelled = false
    fetch('/api/me', { credentials: 'same-origin' })
      .then((response) => {
        if (response.status === 401) {
          navigate('/', { replace: true })
          return null
        }
        if (!response.ok) throw new Error('unable to load the current user')
        return response.json() as Promise<Me>
      })
      .then((currentUser) => {
        if (!cancelled && currentUser) setMe(currentUser)
      })
      .catch(() => {
        if (!cancelled) navigate('/', { replace: true })
      })
    return () => {
      cancelled = true
    }
  }, [navigate])

  useEffect(() => {
    init()
  }, [init])

  // Already in a room (e.g. hard refresh restored membership) → go there.
  useEffect(() => {
    if (room) navigate(`/room/${room.roomId}`, { replace: true })
  }, [room, navigate])

  const join = (code: string) => {
    if (busy) return
    playSound('click')
    setBusy(true)
    joinRoom(code)
      .then((ack) => {
        if (ack.ok && ack.roomId) {
          navigate(`/room/${ack.roomId}`)
        }
      })
      .catch(() => pushToast('warn', 'Join failed', 'could not reach the server'))
      .finally(() => setBusy(false))
  }

  const submitCode = (e: FormEvent) => {
    e.preventDefault()
    const code = joinCode.trim().toUpperCase()
    if (code) join(code)
  }

  const logout = async () => {
    if (loggingOut) return
    playSound('click')
    setLoggingOut(true)
    try {
      await endSession()
      window.location.assign('/')
    } catch {
      pushToast('warn', 'Logout failed', 'could not end the current session')
      setLoggingOut(false)
    }
  }

  return (
    <div className="stage lobby-stage">
      <SwirlBackground />

      <div className="lobby-scroll">
        <header className="lobby-head">
          <div className="lobby-logo panel">
            H<span className="lt-o">O</span>LD&#39;EM
          </div>
          <div className="user-chip panel">
            {me?.avatarUrl ? (
              <img className="avatar" src={me.avatarUrl} alt="" />
            ) : (
              <span className="avatar" aria-hidden="true" />
            )}
            <b>{me?.displayName ?? '…'}</b>
            <SettingsButton />
            <button className="btn btn-dev logout" type="button" disabled={loggingOut} onClick={logout}>
              {loggingOut ? 'Logging out…' : 'Log out'}
            </button>
          </div>
        </header>

        <div className="lobby-main panel">
          <h2>
            Public tables{' '}
            <span className={`live-dot${connection === 'connected' ? '' : ' off'}`}>
              {connection === 'connected' ? 'live' : connection}
            </span>
          </h2>

          {!lobby || lobby.rooms.length === 0 ? (
            <p className="lobby-empty">No tables yet — create the first one.</p>
          ) : (
            lobby.rooms.map((r) => (
              <div className="room-row" key={r.roomId} data-testid="room-row" data-room-name={r.name}>
                <span className="room-name">{r.name}</span>
                <span className="room-stat">
                  <span className="label">code</span>
                  <b className="num">{r.joinCode}</b>
                </span>
                <span className={`room-stat${r.seatedCount >= r.maxSeats ? ' seats-full' : ''}`}>
                  <span className="label">seats</span>
                  <b className="num">
                    {r.seatedCount}/{r.maxSeats}
                  </b>
                </span>
                <span className="room-stat">
                  <span className="label">online</span>
                  <b className="num">{r.connectedMemberCount}</b>
                </span>
                <span className="room-stat">
                  <span className="label">watching</span>
                  <b className="num">{Math.max(0, r.memberCount - r.seatedCount)}</b>
                </span>
                <button
                  className="btn btn-blue btn-join"
                  disabled={busy || connection !== 'connected'}
                  onClick={() => join(r.joinCode)}
                >
                  {r.seatedCount >= r.maxSeats ? 'Spectate' : 'Join'}
                </button>
              </div>
            ))
          )}
        </div>

        <div className="lobby-actions">
          <button
            className="btn btn-orange btn-create"
            disabled={connection !== 'connected'}
            onClick={() => {
              playSound('click')
              setCreating(true)
            }}
          >
            + Create table
          </button>
          <form className="join-code panel" onSubmit={submitCode}>
            <span className="label">private code</span>
            <input
              type="text"
              maxLength={12}
              placeholder="ABC123"
              value={joinCode}
              onChange={(e) => setJoinCode(e.target.value)}
              aria-label="Join code"
            />
            <button
              className="btn btn-green btn-code-join"
              type="submit"
              disabled={busy || !joinCode.trim() || connection !== 'connected'}
            >
              Join
            </button>
          </form>
        </div>
      </div>

      {creating && <CreateTableModal onClose={() => setCreating(false)} />}
      <CrtOverlay />
    </div>
  )
}

function CreateTableModal({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState(generateRandomTableName)
  const [seats, setSeats] = useState(6)
  const [privateTable, setPrivateTable] = useState(false)
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()
  const createRoom = useGameStore((s) => s.createRoom)
  const pushToast = useGameStore((s) => s.pushToast)

  const submit = () => {
    const trimmed = name.trim()
    if (!trimmed || busy) return
    setBusy(true)
    createRoom(trimmed, seats, privateTable)
      .then((ack) => {
        if (ack.ok && ack.roomId) {
          navigate(`/room/${ack.roomId}`)
        } else {
          setBusy(false)
        }
      })
      .catch(() => {
        pushToast('warn', 'Create failed', 'could not reach the server')
        setBusy(false)
      })
  }

  return (
    <div className="modalback" data-testid="modal-create">
      <div className="modal panel">
        <h3>Create table</h3>
        <div className="field">
          <span className="label">Name</span>
          <input
            type="text"
            value={name}
            maxLength={40}
            autoFocus
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && submit()}
            aria-label="Table name"
          />
        </div>
        <div className="field">
          <span className="label">Seats</span>
          <span className="stepper">
            <button aria-label="fewer seats" onClick={() => setSeats((n) => Math.max(2, n - 1))}>
              −
            </button>
            <b data-testid="seat-count">{seats}</b>
            <button aria-label="more seats" onClick={() => setSeats((n) => Math.min(9, n + 1))}>
              +
            </button>
          </span>
        </div>
        <div className="visibility-field">
          <span className="label">Visibility</span>
          <div className="visibility-options" role="group" aria-label="Table visibility">
            <button
              type="button"
              className={`visibility-option${privateTable ? '' : ' selected'}`}
              aria-pressed={!privateTable}
              onClick={() => setPrivateTable(false)}
            >
              <b>Public</b>
              <span>Shown in the lobby</span>
            </button>
            <button
              type="button"
              className={`visibility-option${privateTable ? ' selected' : ''}`}
              aria-pressed={privateTable}
              onClick={() => setPrivateTable(true)}
            >
              <b>Private</b>
              <span>Join by code only</span>
            </button>
          </div>
        </div>
        <div className="row">
          <button className="btn btn-dev" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-orange" disabled={!name.trim() || busy} onClick={submit}>
            Create
          </button>
        </div>
      </div>
    </div>
  )
}
