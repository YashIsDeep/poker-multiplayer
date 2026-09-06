// T8: screen vignette (pure CSS, see crt.css). The CRT scanline/film-grain
// layer that used to sit under this was removed.

export default function CrtOverlay() {
  return <div className="vignette" aria-hidden="true" />
}
