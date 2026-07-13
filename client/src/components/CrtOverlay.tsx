// T8: CRT scanlines + animated film grain + vignette (pure CSS, see crt.css).

export default function CrtOverlay() {
  return (
    <>
      <div className="crt" aria-hidden="true" />
      <div className="vignette" aria-hidden="true" />
    </>
  )
}
