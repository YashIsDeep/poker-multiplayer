// React binding for the sound engine's mute/volume preferences.

import { useSyncExternalStore } from 'react'
import { soundEngine } from '../lib/sound'

export function useSoundSettings() {
  const muted = useSyncExternalStore(
    soundEngine.subscribe,
    soundEngine.isMuted,
    () => true, // server / first paint: assume muted
  )
  const volume = useSyncExternalStore(
    soundEngine.subscribe,
    soundEngine.getVolume,
    () => 0,
  )
  return {
    muted,
    volume,
    toggleMuted: soundEngine.toggleMuted,
    setMuted: soundEngine.setMuted,
    setVolume: soundEngine.setVolume,
  }
}
