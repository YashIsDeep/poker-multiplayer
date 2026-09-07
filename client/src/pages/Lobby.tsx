// Lobby (functional, Balatro-skinned): live room list from /topic/lobby,
// create-table modal, private-code join. Room create/join navigates into /room/:id.
// This is the app's front door at "/": signed-out visitors see the same shell
// with a Sign In button in the header, and the live list — which rides the
// session-authenticated STOMP handshake — is replaced by a sign-in prompt.

import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import SwirlBackground from '../components/SwirlBackground'
import RankedEntry from '../components/RankedEntry'
import CrtOverlay from '../components/CrtOverlay'
import SettingsButton from '../components/SettingsButton'
import LeaderboardLink from '../components/LeaderboardLink'
import { useGameStore } from '../store/gameStore'
import { endSession } from '../lib/auth'
import { takePostLoginRedirect } from '../lib/postLoginRedirect'
import { generateRandomTableName } from '../lib/tableNames'
import { formatChips } from '../lib/chips'
import { playSound } from '../lib/sound'
import TableSettingsFields, {
  PUBLIC_TABLE_SETTINGS,
  tableSettingsError,
} from '../components/TableSettingsFields'
import type { TableSettings } from '../store/types'
import '../styles/lobby.css'

const JOIN_CODE_PATTERN = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/

type Me = {
  id: string
  displayName: string
  avatarUrl: string | null
  provider: 'GOOGLE' | 'DISCORD' | 'DEV'
  /** Public tables are moderator-only; everyone else creates private tables. */
  moderator: boolean
}

type SessionStatus = 'checking' | 'signed-in' | 'signed-out'

export default function Lobby() {
  const [me, setMe] = useState<Me | null>(null)
  const [session, setSession] = useState<SessionStatus>('checking')
  const [creating, setCreating] = useState(false)
  const [joinCode, setJoinCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const navigate = useNavigate()

  const init = useGameStore((s) => s.init)
  const connection = useGameStore((s) => s.connection)
  const lobby = useGameStore((s) => s.lobby)
  const room = useGameStore((s) => s.room)
  const rankedQueue = useGameStore((s) => s.rankedQueue)
  const queued = rankedQueue?.status === 'QUEUED'
  const casualRooms = lobby?.rooms.filter(r => r.mode !== 'RANKED') ?? []
  const joinRoom = useGameStore((s) => s.joinRoom)
  const pushToast = useGameStore((s) => s.pushToast)
  const signedOut = session === 'signed-out'

  useEffect(() => {
    let cancelled = false
    fetch('/api/me', { credentials: 'same-origin' })
      .then((response) => {
        if (response.status === 401) return null
        if (!response.ok) throw new Error('unable to load the current user')
        return response.json() as Promise<Me>
      })
      .then((currentUser) => {
        if (cancelled) return
        if (!currentUser) {
          setSession('signed-out')
          return
        }
        setMe(currentUser)
        setSession('signed-in')
        // Sign-in lands here, so this is where a room deep link taken before
        // authenticating gets resumed.
        const resume = takePostLoginRedirect(window.sessionStorage)
        if (resume && resume !== '/') navigate(resume, { replace: true })
      })
      .catch(() => {
        // An unreachable server is not a signed-out session: keep the shell up
        // and let the sign-in prompt stand in for the live list.
        if (!cancelled) setSession('signed-out')
      })
    return () => {
      cancelled = true
    }
  }, [navigate])

  // The STOMP handshake needs a session, so only connect once we have one.
  useEffect(() => {
    if (session === 'signed-in') init()
  }, [session, init])

  // Already in a room (e.g. hard refresh restored membership) → go there.
  useEffect(() => {
    if (room) navigate(`/room/${room.roomId}`, { replace: true })
  }, [room, navigate])

  const join = (code: string) => {
    if (busy) return
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
    if (!code) return
    if (!JOIN_CODE_PATTERN.test(code)) {
      pushToast('warn', 'Invalid table code', 'enter the 6-character code shown by the table owner')
      return
    }
    join(code)
  }

  const logout = async () => {
    if (loggingOut) return
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
          {signedOut ? (
            <div className="user-chip panel guest-chip">
              <SettingsButton />
              <Link className="btn btn-green sign-in" data-sound="confirm" to="/login">
                Sign In
              </Link>
            </div>
          ) : (
            <div className="user-chip panel">
              {me?.avatarUrl ? (
                <img className="avatar" src={me.avatarUrl} alt="" />
              ) : (
                <span className="avatar" aria-hidden="true" />
              )}
              {me ? <Link className="lobby-profile" data-sound="confirm" to={`/players/${me.id}`}
                title="Your ranked profile"><b>{me.displayName}</b></Link> : <b>…</b>}
              <SettingsButton />
              <button
                className="btn btn-dev logout"
                type="button"
                data-sound="exit"
                disabled={loggingOut}
                onClick={logout}
              >
                {loggingOut ? 'Logging out…' : 'Log out'}
              </button>
            </div>
          )}
        </header>

        {signedOut ? (
          <section className="lobby-signed-out panel" aria-label="Sign in to play">
            <h2>Multiplayer Texas Hold&#39;em</h2>
            <p>Sign in to see live tables, join a seat and play ranked.</p>
            <div className="lobby-signed-out-actions">
              <Link className="btn btn-green btn-create" data-sound="confirm" to="/login">
                Sign in to play
              </Link>
              <LeaderboardLink compact />
            </div>
          </section>
        ) : (
          <RankedEntry />
        )}
        <div className="lobby-main panel">
          <h2>
            Public tables{' '}
            {!signedOut && (
              <span className={`live-dot${connection === 'connected' ? '' : ' off'}`}>
                {connection === 'connected' ? 'live' : connection}
              </span>
            )}
          </h2>

          {signedOut ? (
            <p className="lobby-empty">
              <Link data-sound="confirm" to="/login">Sign in</Link> to see the live table list.
            </p>
          ) : !lobby || casualRooms.length === 0 ? (
            <p className="lobby-empty">No tables yet — create the first one.</p>
          ) : (
            casualRooms.map((r) => (
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
                  data-sound="confirm"
                  disabled={busy || queued || connection !== 'connected'}
                  onClick={() => join(r.joinCode)}
                >
                  {r.seatedCount >= r.maxSeats ? 'Spectate' : 'Join'}
                </button>
              </div>
            ))
          )}
        </div>

        {!signedOut && <div className="lobby-actions">
          <button
            className="btn btn-orange btn-create"
            data-sound="open"
            disabled={queued || connection !== 'connected'}
            onClick={() => setCreating(true)}
          >
            + Create table
          </button>
          <form className="join-code panel" onSubmit={submitCode}>
            <span className="label">private code</span>
            <input
              type="text"
              maxLength={6}
              placeholder="ABC123"
              value={joinCode}
              onChange={(e) => setJoinCode(e.target.value)}
              aria-label="Join code"
            />
            <button
              className="btn btn-green btn-code-join"
              type="submit"
              data-sound="confirm"
              disabled={busy || !joinCode.trim() || connection !== 'connected'}
            >
              Join
            </button>
          </form>
        </div>}
      </div>

      {creating && (
        <CreateTableModal moderator={me?.moderator ?? false} onClose={() => setCreating(false)} />
      )}
      <CrtOverlay />
    </div>
  )
}

function CreateTableModal({
  moderator,
  onClose,
}: {
  moderator: boolean
  onClose: () => void
}) {
  const [name, setName] = useState(generateRandomTableName)
  const [seats, setSeats] = useState(6)
  // Only a moderator can add to the public lobby, so everyone else starts —
  // and stays — on a private table. The server enforces this independently.
  const [privateTable, setPrivateTable] = useState(!moderator)
  const [settings, setSettings] = useState<TableSettings>(PUBLIC_TABLE_SETTINGS)
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()
  const createRoom = useGameStore((s) => s.createRoom)
  const pushToast = useGameStore((s) => s.pushToast)

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      playSound('close')
      onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  const submit = () => {
    const trimmed = name.trim()
    if (!trimmed || busy || (privateTable && tableSettingsError(settings))) return
    setBusy(true)
    createRoom(trimmed, privateTable ? seats : 6, privateTable, privateTable ? settings : undefined)
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
      <div className="modal panel create-table-modal">
        <h3>Create table</h3>
        <div className="field">
          <span className="label">Name</span>
          <input
            type="text"
            value={name}
            maxLength={40}
            autoFocus
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(event) => {
              if (
                event.key === 'Enter' &&
                name.trim() &&
                !busy &&
                (!privateTable || !tableSettingsError(settings))
              ) {
                playSound('confirm')
                submit()
              }
            }}
            aria-label="Table name"
          />
        </div>
        <div className="field">
          <span className="label">Seats</span>
          {privateTable ? <span className="stepper">
            <button
              aria-label="fewer seats"
              data-sound="tick"
              onClick={() => setSeats((n) => Math.max(2, n - 1))}
            >
              −
            </button>
            <b data-testid="seat-count">{seats}</b>
            <button
              aria-label="more seats"
              data-sound="tick"
              onClick={() => setSeats((n) => Math.min(9, n + 1))}
            >
              +
            </button>
          </span> : <b data-testid="seat-count">6 · fixed</b>}
        </div>
        <div className="visibility-field">
          <span className="label">Visibility</span>
          {moderator ? (
            <div className="visibility-options" role="group" aria-label="Table visibility">
              <button
                type="button"
                className={`visibility-option${privateTable ? '' : ' selected'}`}
                data-sound="toggleOn"
                aria-pressed={!privateTable}
                onClick={() => setPrivateTable(false)}
              >
                <b>Public</b>
                <span>Shown in the lobby</span>
              </button>
              <button
                type="button"
                className={`visibility-option${privateTable ? ' selected' : ''}`}
                data-sound="toggleOn"
                aria-pressed={privateTable}
                onClick={() => setPrivateTable(true)}
              >
                <b>Private</b>
                <span>Join by code only</span>
              </button>
            </div>
          ) : (
            <p className="visibility-locked">
              <b>Private</b> — join by code only. Public tables are created by moderators.
            </p>
          )}
        </div>
        {privateTable ? (
          <>
            <TableSettingsFields value={settings} onChange={setSettings} disabled={busy} />
            {tableSettingsError(settings) && (
              <p className="settings-error" role="alert">{tableSettingsError(settings)}</p>
            )}
          </>
        ) : (
          <div className="public-table-defaults">
            <span className="label">Public table defaults</span>
            <div>
              <span>BLINDS <b className="num">0.5 / 1 BB</b></span>
              <span title={`${formatChips(PUBLIC_TABLE_SETTINGS.startingStack)} chips`}>
                STACK <b className="num">
                  {formatChips(PUBLIC_TABLE_SETTINGS.startingStack / PUBLIC_TABLE_SETTINGS.bigBlind)} BB
                </b>
              </span>
              <span>REBUY <b>100 BB</b></span>
              <span>TURN <b className="num">{PUBLIC_TABLE_SETTINGS.actionTimeoutSeconds}s</b></span>
            </div>
          </div>
        )}
        <div className="row">
          <button className="btn btn-dev" data-sound="close" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-orange"
            data-sound="confirm"
            disabled={!name.trim() || busy || (privateTable && Boolean(tableSettingsError(settings)))}
            onClick={submit}
          >
            Create
          </button>
        </div>
      </div>
    </div>
  )
}
