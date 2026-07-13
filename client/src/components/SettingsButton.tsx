// Gear button that opens the Settings modal. Self-contained: owns its open
// state so it can drop into any topbar/header with no wiring.

import { useState } from 'react'
import SettingsModal from './SettingsModal'

export default function SettingsButton({
  className = '',
  onHandHistory,
}: {
  className?: string
  onHandHistory?: () => void
}) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        type="button"
        className={`btn btn-dev settings-btn ${className}`.trim()}
        data-sound="open"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label="Settings"
        title="Settings"
        onClick={() => setOpen(true)}
      >
        <span aria-hidden="true">⚙️</span>
      </button>
      {open && (
        <SettingsModal
          onClose={() => setOpen(false)}
          onHandHistory={
            onHandHistory
              ? () => {
                  setOpen(false)
                  onHandHistory()
                }
              : undefined
          }
        />
      )}
    </>
  )
}
