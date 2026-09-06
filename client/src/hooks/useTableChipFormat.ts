import { useMemo } from 'react'
import { useGameStore } from '../store/gameStore'
import { tableChipFormat } from '../lib/chips'

export function useTableChipFormat(historicalBlind?: number) {
  const bigBlind = useGameStore(s => s.room && !s.room.privateTable ? s.room.config.bigBlind : null)
  const displayBlind = bigBlind === null ? null : historicalBlind ?? bigBlind
  return useMemo(() => tableChipFormat(displayBlind), [displayBlind])
}
