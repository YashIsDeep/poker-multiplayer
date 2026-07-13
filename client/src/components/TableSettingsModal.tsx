import { useState } from 'react'
import { useGameStore } from '../store/gameStore'
import type { TableSettings } from '../store/types'
import TableSettingsFields, {
  normalizeTableSettings,
  tableSettingsError,
} from './TableSettingsFields'

export default function TableSettingsModal({ onClose }: { onClose: () => void }) {
  const room = useGameStore((state) => state.room)
  const updateRoomSettings = useGameStore((state) => state.updateRoomSettings)
  const pushToast = useGameStore((state) => state.pushToast)
  const [busy, setBusy] = useState(false)
  const [settings, setSettings] = useState<TableSettings>(() => normalizeTableSettings({
    bigBlind: room?.config.bigBlind ?? 100_000,
    startingStack: room?.config.startingStack ?? room?.config.minBuyIn ?? 10_000_000,
    rebuysEnabled: room?.config.rebuysEnabled ?? true,
    rebuyStack: room?.config.rebuyStack ?? room?.config.minBuyIn ?? 10_000_000,
    rebuyDelayHands: room?.config.rebuyDelayHands ?? 5,
  }))
  const error = tableSettingsError(settings)

  const save = async () => {
    if (busy || error) return
    setBusy(true)
    try {
      const ack = await updateRoomSettings(settings)
      if (ack.ok) {
        pushToast('ok', 'Table settings updated', 'existing chip stacks were left unchanged')
        onClose()
      }
    } catch {
      pushToast('warn', 'Settings update failed', 'could not reach the server')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modalback" onClick={onClose} role="presentation">
      <div
        className="modal panel table-settings-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Table settings"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="table-settings-title">
          <div>
            <span className="label">Private table · admin</span>
            <h3>Table settings</h3>
          </div>
          <span className="admin-shield large" aria-hidden="true">A</span>
        </div>
        <p className="settings-note">
          Blind changes start next hand. Stack changes apply to future seats and rebuys;
          delay changes apply to future busts.
        </p>
        <TableSettingsFields value={settings} onChange={setSettings} disabled={busy} />
        {error && <p className="settings-error" role="alert">{error}</p>}
        <div className="row">
          <button className="btn btn-dev" data-sound="close" disabled={busy} onClick={onClose}>Cancel</button>
          <button
            className="btn btn-orange"
            data-sound="confirm"
            disabled={busy || Boolean(error)}
            onClick={save}
          >
            {busy ? 'Saving…' : 'Save settings'}
          </button>
        </div>
      </div>
    </div>
  )
}
