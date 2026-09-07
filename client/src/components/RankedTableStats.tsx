import { Link } from 'react-router-dom'
import { useRankedStats } from '../hooks/useRankedStats'
import type { RankedPlayer } from '../lib/ranked'
import { formatBB } from '../lib/ranked'
import type { RoomSnapshot } from '../store/types'

export default function RankedTableStats({ room }: { room: RoomSnapshot }) {
  const settled = room.game?.phase === 'COMPLETE' ? room.game.handId : null
  const { data } = useRankedStats<RankedPlayer>(`/api/players/${room.viewer.userId}`, settled)
  return <Link data-sound="confirm" className="table-config-chip" to={`/players/${room.viewer.userId}`}>
    {data ? `${formatBB(data.bbPer100)} BB/100 · ${data.handCount} hands` : 'Ranked profile'}
  </Link>
}
