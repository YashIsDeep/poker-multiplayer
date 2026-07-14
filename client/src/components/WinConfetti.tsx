import type { CSSProperties } from 'react'

const COLORS = ['#f2c14e', '#ff9d2e', '#ff4c40', '#2e9dfa', '#4bc292', '#f4f4f4']

const PIECES = Array.from({ length: 36 }, (_, index) => {
  const angle = (index / 36) * Math.PI * 2
  const radius = 92 + (index % 6) * 28

  return {
    x: Math.round(Math.cos(angle) * radius),
    y: Math.round(Math.sin(angle) * radius * 0.58 - 12),
    delay: `${(index % 7) * 35}ms`,
    duration: `${1.35 + (index % 4) * 0.16}s`,
    rotation: `${180 + (index % 3) * 90}deg`,
    width: `${6 + (index % 3) * 2}px`,
    height: `${4 + (index % 4) * 3}px`,
    color: COLORS[index % COLORS.length],
  }
})

export default function WinConfetti() {
  return (
    <div className="win-confetti" aria-hidden="true">
      {PIECES.map((piece, index) => {
        const style = {
          '--confetti-x': `${piece.x}px`,
          '--confetti-y': `${piece.y}px`,
          '--confetti-delay': piece.delay,
          '--confetti-duration': piece.duration,
          '--confetti-rotation': piece.rotation,
          '--confetti-width': piece.width,
          '--confetti-height': piece.height,
          '--confetti-color': piece.color,
        } as CSSProperties

        return <span key={index} className="win-confetti-piece" style={style} />
      })}
    </div>
  )
}
