# T7 — Gameplay wiring (client): design

Status: **DESIGNED** (approved 2026-07-12)

## Deliverables in this folder

| File | What it is |
|---|---|
| `mockup.html` | Functional wireframe — every screen state the T7 client must handle, in plain styling. Structure-first: the DOM/component shapes here are what T7 builds. |
| `table-green.html` | **Approved visual direction for the table.** Green-felt stadium table in the Balatro/lobby language (pixel font, tokens.css palette, swirl background, CRT grain). T7 implements this look directly for the Room view. |

Open either file over HTTP (e.g. `python -m http.server` in this folder) — `file://` is blocked in the preview pane.

## Approved table design (table-green.html)

- **World**: same as the lobby — `Pixelify Sans` (→ `--font-pixel`), colors straight from `client/src/styles/tokens.css`, WebGL swirl background (same shader as T8 mockup / future `SwirlBackground.tsx`), CRT scanlines + animated film grain (grain opacity .11, jittering `steps(4)` shift), vignette.
- **Table**: stadium/racetrack shape (`border-radius:999px` on a 2:1 box), grass-green radial felt `#2e8b52 → #1c5c36 → #123f24` with its own grain pass (`mix-blend-mode:overlay`, opacity .16), 5px `--panel-edge` border + inset `#101b29` ring, dashed inner betting line, tilted ghost "HOLD'EM" logo (gold O, like the lobby logo).
- **Seats (2–8, odd counts supported)**: seat 0 = you, fixed bottom-center (90°); others spread evenly clockwise:
  `ang = π/2 + i·2π/N`, then a **superellipse projection** pushes seats onto the stadium rim instead of an ellipse:
  `x = 50 + 46·sign(cos)·|cos|^0.72`, `y = 50 + 56·sign(sin)·|sin|^0.72` (percent of table box). Bet chips use the same curve at radii 30/36.
- **Seat pod**: chunky panel (3px `--panel-edge` border, inset bottom shadow), NAME / gold stack / last-action stacked; blue border = you; actor gets orange border + pulsing glow + top-center conic timer ring (red + tremble when ≤6 s); gold "D" dealer coin bottom-left; red **All-in** / grey **Sitting out** badges; folded/sit-out pods desaturated.
- **Cards**: pixel-style faces 64×90 board / 40×56 hole, bob animation with random phase, red diagonal-stripe backs, winners outlined gold with `winpulse`.

## Functional design (mockup.html) — what T7 actually wires

- **Store** (zustand): STOMP snapshot per viewer, `serverNow` clock-offset EMA for the countdown, `act()` auto-attaching the current `actionSeq`.
- **Action area state machine**: your turn → Fold / Call *n* / Raise-to slider clamped to `[minRaiseTo, maxRaiseTo]` with min/½pot/pot/all-in presets, sending `{action:"raiseTo", amount, actionSeq}`; waiting → "waiting for X…"; showdown → banner + revealed hands; busted → rebuy slider (buy-in range); sitting out → "I'm back".
- **States covered**: turn / waiting / showdown / busted / sit-out; per-seat tags dealer, all-in, sit-out, busted, offline; deadline countdown; conn pill.
- T7 ships this behaviour wearing the `table-green.html` skin; **T8** layers the remaining juice (chip fly, number pop, card deal animations) on the same structure.
