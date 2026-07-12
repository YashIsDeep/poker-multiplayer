import { useParams } from 'react-router-dom'

export default function Room() {
  const { roomId } = useParams()
  return (
    <main className="room">
      <h1>Room {roomId}</h1>
      <p>The table arrives in P5/P6.</p>
    </main>
  )
}
