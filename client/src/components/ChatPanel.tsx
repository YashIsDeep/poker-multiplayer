import { useEffect, useRef, useState } from 'react'
import type { FormEvent, Ref } from 'react'
import { useGameStore } from '../store/gameStore'
import { isCompletedHandResult } from '../lib/showdownResults'
import type { Game, RoomSnapshot } from '../store/types'
import TrophyIcon from './TrophyIcon'

const MAX_CHAT_LENGTH = 50
const COMMON_EMOJIS = [
  '😀',
  '😄',
  '😂',
  '🤣',
  '😍',
  '😎',
  '🤔',
  '👍',
  '🙌',
  '👏',
  '💪',
  '❤️',
  '👋',
  '🎉',
  '🔥',
  '✨',
  '🚀',
  '💰',
  '✅',
  '❌',
]

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

type ChatPanelProps = {
  room: RoomSnapshot
  game: Game | null
  showdownOpen: boolean
  onOpenShowdown: () => void
  trophyButtonRef?: Ref<HTMLButtonElement>
}

export default function ChatPanel({
  room,
  game,
  showdownOpen,
  onOpenShowdown,
  trophyButtonRef,
}: ChatPanelProps) {
  const messages = useGameStore((state) => state.chatMessages)
  const connection = useGameStore((state) => state.connection)
  const sendChatMessage = useGameStore((state) => state.sendChatMessage)
  const pushToast = useGameStore((state) => state.pushToast)
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [sendingEmoji, setSendingEmoji] = useState<string | null>(null)
  const endRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const handOver = isCompletedHandResult(game)

  useEffect(() => {
    if (open) endRef.current?.scrollIntoView({ block: 'nearest' })
  }, [messages, open])

  const sendEmoji = async (emoji: string) => {
    if (sending || sendingEmoji !== null || connection !== 'connected') return

    setSendingEmoji(emoji)
    try {
      await sendChatMessage(emoji)
    } catch {
      pushToast('warn', 'Chat failed', 'could not reach the table')
    } finally {
      setSendingEmoji(null)
    }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const message = draft.trim()
    if (!message || sending || connection !== 'connected') return

    setSending(true)
    try {
      const ack = await sendChatMessage(message)
      if (ack?.ok) setDraft('')
    } catch {
      pushToast('warn', 'Chat failed', 'could not reach the table')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className={`chat-widget${open ? ' chat-widget--open' : ''}`} data-testid="chat">
      {open && (
        <section id="table-chat" className="chat-panel panel" aria-label="Table chat">
          <header className="chat-panel__header">
            <div>
              <strong>TABLE CHAT</strong>
              <small>live bubbles · disappear after a few seconds</small>
            </div>
            <button
              type="button"
              className="chat-panel__close"
              data-sound="close"
              aria-label="Close chat"
              onClick={() => setOpen(false)}
            >
              ×
            </button>
          </header>

          <div className="chat-log" role="log" aria-label="Table chat messages" aria-live="polite">
            {messages.length === 0 ? (
              <p className="chat-empty">No messages yet. Say hello.</p>
            ) : (
              messages.map((entry) => (
                <article
                  className={`chat-message${entry.senderId === room.viewer.userId ? ' chat-message--you' : ''}`}
                  key={entry.id}
                >
                  <div className="chat-message__meta">
                    <b>{entry.senderName}</b>
                    <time dateTime={entry.sentAt}>{formatTime(entry.sentAt)}</time>
                  </div>
                  <p>{entry.message}</p>
                </article>
              ))
            )}
            <div ref={endRef} />
          </div>

          <div className="chat-emoji-picker" role="group" aria-label="Common emojis">
            {COMMON_EMOJIS.map((emoji) => (
              <button
                key={emoji}
                type="button"
                className="chat-emoji"
                data-sound="confirm"
                aria-label={`Send ${emoji}`}
                title={`Send ${emoji}`}
                aria-busy={sendingEmoji === emoji}
                disabled={sending || sendingEmoji !== null || connection !== 'connected'}
                onClick={() => void sendEmoji(emoji)}
              >
                {emoji}
              </button>
            ))}
          </div>

          <form className="chat-compose" onSubmit={(event) => void submit(event)}>
            <input
              ref={inputRef}
              value={draft}
              maxLength={MAX_CHAT_LENGTH}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="Say something..."
              aria-label="Chat message"
              disabled={sending || sendingEmoji !== null || connection !== 'connected'}
            />
            <button
              type="submit"
              className="btn btn-blue"
              data-sound="confirm"
              disabled={!draft.trim() || sending || sendingEmoji !== null || connection !== 'connected'}
            >
              {sending ? '...' : 'Send'}
            </button>
          </form>
          <small className="chat-panel__note">Messages disappear when you leave.</small>
        </section>
      )}

      <button
        type="button"
        className="btn btn-blue chat-toggle"
        data-sound={open ? 'close' : 'open'}
        aria-label={open ? 'Close table chat' : 'Open table chat'}
        title={open ? 'Close table chat' : 'Open table chat'}
        aria-expanded={open}
        aria-controls="table-chat"
        onClick={() => setOpen((current) => !current)}
      >
        <svg className="chat-toggle__icon" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 5.5h16v11H9l-5 3v-14Z" />
          <path d="M8 10h8M8 13h5" />
        </svg>
      </button>

      <button
        ref={trophyButtonRef}
        type="button"
        className={`btn btn-dev trophy-toggle${handOver ? ' trophy-toggle--ready' : ''}`}
        data-testid="last-hand-trophy"
        data-sound="open"
        aria-label="View last hand results"
        title="View last hand results"
        aria-haspopup="dialog"
        aria-expanded={showdownOpen}
        aria-controls="table-showdown-results"
        onClick={onOpenShowdown}
      >
        <span className="trophy-toggle__icon">
          <TrophyIcon />
        </span>
      </button>
    </div>
  )
}
