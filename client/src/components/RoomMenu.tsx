// Overflow menu for the room topbar. On small screens the individual action
// buttons (settings, sit out, stand, leave…) don't fit, so they collapse into
// this hamburger dropdown — CSS decides which rendering is visible via the
// .room-menu display rules in table.css.

import { useEffect, useRef, useState } from 'react'
import { playSound, type SoundName } from '../lib/sound'

export type RoomMenuItem = {
  key: string
  label: string
  onClick: () => void
  sound: SoundName
  disabled?: boolean
  danger?: boolean
}

export default function RoomMenu({ items }: { items: RoomMenuItem[] }) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        playSound('close')
        setOpen(false)
      }
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        playSound('close')
        setOpen(false)
        buttonRef.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  return (
    <div className="room-menu" ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className="btn btn-dev room-menu-btn"
        data-sound={open ? 'close' : 'open'}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Room menu"
        title="Room menu"
        onClick={() => setOpen((v) => !v)}
      >
        <span aria-hidden="true">☰</span>
      </button>
      {open && (
        <div className="room-menu-list panel" role="menu" aria-label="Room actions">
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              role="menuitem"
              className={`room-menu-item${item.danger ? ' danger' : ''}`}
              data-sound={item.sound}
              disabled={item.disabled}
              onClick={() => {
                setOpen(false)
                item.onClick()
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
