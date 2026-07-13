// Gear button that opens the Settings modal. Self-contained: owns its open
// state so it can drop into any topbar/header with no wiring.

import { useState } from 'react'
import { playSound } from '../lib/sound'
import SettingsModal from './SettingsModal'

export default function SettingsButton({ className = '' }: { className?: string }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        type="button"
        className={`btn btn-dev settings-btn ${className}`.trim()}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label="Settings"
        title="Settings"
        onClick={() => {
          playSound('click')
          setOpen(true)
        }}
      >
        <span aria-hidden="true">⚙️</span>
      </button>
      {open && <SettingsModal onClose={() => setOpen(false)} />}
    </>
  )
}
