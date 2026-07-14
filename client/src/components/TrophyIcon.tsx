export default function TrophyIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className ? `trophy-icon ${className}` : 'trophy-icon'}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M6 3h12v2h2v4a5 5 0 0 1-5 5h-1v3h3v2H7v-2h3v-3H9a5 5 0 0 1-5-5V5h2V3Zm0 4H5v2a3 3 0 0 0 3 3h.18A6 6 0 0 1 6 9V7Zm12 0v2a6 6 0 0 1-2.18 3H16a3 3 0 0 0 3-3V7h-1Z" />
      <path d="M9 21h6" />
    </svg>
  )
}
