// Mute / unmute control. Also plays a click when unmuting so the user gets
// immediate audible proof the sound is on.

import { playSound } from '../lib/sound'
import { useSoundSettings } from '../hooks/useSoundSettings'

export default function SoundToggle({ className = '' }: { className?: string }) {
  const { muted, toggleMuted } = useSoundSettings()

  return (
    <button
      type="button"
      className={`btn btn-dev sound-toggle${muted ? ' muted' : ''} ${className}`.trim()}
      aria-pressed={!muted}
      aria-label={muted ? 'Unmute sounds' : 'Mute sounds'}
      title={muted ? 'Sound off' : 'Sound on'}
      onClick={() => {
        const willUnmute = muted
        toggleMuted()
        if (willUnmute) playSound('notify')
      }}
    >
      <span aria-hidden="true">{muted ? '🔇' : '🔊'}</span>
    </button>
  )
}
