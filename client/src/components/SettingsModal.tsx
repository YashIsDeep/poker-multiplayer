// Settings modal (opened from the gear button). Currently houses the Sound
// section — an on/off toggle and a volume slider — but is laid out as a
// sectioned panel so more settings can slot in later.

import { useEffect, useRef } from 'react'
import type { CSSProperties } from 'react'
import { playSound } from '../lib/sound'
import { useSoundSettings } from '../hooks/useSoundSettings'

export default function SettingsModal({
  onClose,
  onHandHistory,
}: {
  onClose: () => void
  onHandHistory?: () => void
}) {
  const { muted, volume, setMuted, setVolume } = useSoundSettings()

  // Throttle the audible preview so dragging the slider doesn't machine-gun it.
  const lastPreview = useRef(0)
  const previewLevel = () => {
    const now = Date.now()
    if (now - lastPreview.current < 140) return
    lastPreview.current = now
    playSound('call')
  }

  // Close on Escape.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        playSound('close')
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const pct = Math.round(volume * 100)
  const fillStyle = { '--fill': `${muted ? 0 : pct}%` } as CSSProperties

  return (
    <div
      className="modalback"
      data-sound="close"
      data-testid="modal-settings"
      onClick={onClose}
      role="presentation"
    >
      <div
        className="modal settings-modal"
        data-sound-boundary=""
        role="dialog"
        aria-modal="true"
        aria-label="Settings"
        onClick={(e) => e.stopPropagation()}
      >
        <h3>Settings</h3>

        <section className="settings-section">
          <div className="settings-section-head label">Sound</div>

          <div className="field">
            <span>Sound effects</span>
            <button
              type="button"
              className={`btn btn-dev sound-switch${muted ? ' off' : ''}`}
              data-sound={muted ? 'toggleOn' : 'toggleOff'}
              aria-pressed={!muted}
              onClick={() => {
                const willUnmute = muted
                setMuted(!muted)
                if (willUnmute) playSound('toggleOn')
              }}
            >
              {muted ? '🔇 Off' : '🔊 On'}
            </button>
          </div>

          <div className="field">
            <span>Volume</span>
            <input
              type="range"
              data-sound-input="call"
              className="volume-slider"
              min={0}
              max={100}
              step={1}
              value={muted ? 0 : pct}
              disabled={muted}
              style={fillStyle}
              aria-label="Volume"
              onChange={(e) => {
                setVolume(Number(e.target.value) / 100)
                previewLevel()
              }}
            />
            <b className="num settings-vol">{muted ? '—' : `${pct}%`}</b>
          </div>
        </section>

        <section className="settings-section">
          <div className="settings-section-head label">Shortcuts</div>
          <p className="waiting settings-shortcuts-hint">
            Active on your turn (ignored while typing or a menu is open).
          </p>
          <ul className="settings-shortcuts-list">
            <li><kbd>F</kbd><span>Fold</span></li>
            <li><kbd>C</kbd><span>Check / Call</span></li>
            <li><kbd>R</kbd><span>Raise to the current amount</span></li>
            <li><kbd>A</kbd><span>All-in</span></li>
            <li><kbd>1</kbd>–<kbd>4</kbd><span>Set raise amount: Min / ½ Pot / Pot / All-in</span></li>
          </ul>
        </section>

        {onHandHistory && (
          <section className="settings-section">
            <div className="settings-section-head label">Table</div>
            <button
              type="button"
              className="btn btn-dev settings-history-button"
              data-sound="open"
              onClick={onHandHistory}
            >
              <span aria-hidden="true">↺</span>
              <span>
                <b>Hand histories</b>
                <small>Actions, boards, stacks and results</small>
              </span>
            </button>
          </section>
        )}

        <div className="row">
          <button className="btn btn-orange" data-sound="close" onClick={onClose}>
            Done
          </button>
        </div>
      </div>
    </div>
  )
}
