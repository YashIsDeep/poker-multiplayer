# T8 Code Architecture — Balatro UI (Client-Only)

## Goal

Layer the remaining visual juice and Polish animations onto T7's functional Room component without changing store logic, server protocol, or seat/card layout. T8 provides:

- a WebGL swirl background shader (Perlin noise fractional Brownian motion) with graceful CSS fallback;
- CRT overlay effects: scanlines, animated film grain, vignette;
- juice animations keyed off store snapshot diffs: chip fly-in, pot number pop, card bob, card deal stagger, actor pod pulse, timer ring decay;
- reduced-motion-aware animation queue and discrete fallback (no animation on `prefers-reduced-motion: reduce`);
- WebGL fallback to the CSS gradient background when WebGL is unavailable;
- action bar styling with raise slider, fold/check/call/raise pills and visual state.

T8 is pure-client rendering; T7 provides the functional Room store snapshot and game state machine. Server protocol, per-viewer snapshot redaction, and reconnect overlay logic belong to T5/T6 and T10 respectively. T8's "reconnect UX" refers to visual state (e.g., action bar disabled during reconnect) only; reconnection protocol and toast/modal UX are T10.

## Scope boundary

T8 owns:

- WebGL canvas setup, shader compilation, resize handling, frame loop, and DPR undersampling.
- CSS-based CRT overlays: scanlines (repeating-linear-gradient), grain (SVG noise pattern), vignette (radial-gradient).
- Animation queue: snapshot-diff derivation, entrance/exit animation scheduling, CSS keyframe playback, and cleanup.
- Reduced-motion override and discrete-only animation fallback.
- Card, pot, chip, and actor visual animations tied to game events (action, commit, reveal).
- Action bar, raise slider, and button styling (no state wiring; state comes from T7 store).
- Graceful degradation when WebGL context is unavailable.

T8 does NOT own:

- Room state management, STOMP store, serverNow clock-offset, or act() wiring (T7).
- Seat layout or ellipse geometry (T7 computed during snapshot render).
- Reconnect protocol, ROOM_NOT_FOUND handling, toast/modal overlays, or multi-tab UX (T10).
- Server-side action sequence guards, hand state progression, or timer scheduling (T6).
- Card-encoding, hand-evaluation, or any game engine logic (T2/T3).

### Scope-boundary clarifications

**T7 vs. T8 animation ownership:**
- T7 renders the static structure: seats, pod badges, card faces, board, pot display, action bar buttons.
- T8 layers animations and visual effects on top without restructuring the DOM or adding interactive logic.
- Card `bob` animation: pure CSS in T7; T8 adds `card.deal-animate` entrance stagger via snapshot diff.
- Pot number display: T7 renders the pot value; T8 detects a change and applies `.num.pop` animation.
- Chip fly-in: T8 derives bet-chip entrance events from per-seat committed-bet
  changes in the snapshot diff (same mechanism as card/pot events) and applies a
  CSS entrance animation.
- Actor pod pulse: T7 applies the `.actor` class; T8 provides the CSS animation rule.

**Reconnect UX (T8 vs. T10 clarification):**
- T8: visual indication that action bar is disabled (opacity, cursor, reduced-saturation filters).
- T10: the toast/modal overlay telling the user "Reconnecting..." or "Connection lost. Rejoin this room?"; the reconnect command and error handling.
- If the mockup shows a reconnect state, T8 styles it; if it shows an overlay/toast, that's T10.

## File and module layout

```text
client/src/
  components/
    SwirlBackground.tsx
      - Canvas wrapper: size, DPR undersampling, WebGL setup fallback.
      - Shader program compilation, uniform/attribute binding.
      - Frame loop (RAF or discrete frame on prefers-reduced-motion).
      - Props: none required (uses 100% container size).

    CrtOverlay.tsx
      - CSS-based scanlines, grain, vignette.
      - Props: optional opacity multipliers for grain/scanlines.
      - Renders as `<div class="crt">` + `<div class="vignette">` (siblings, z-index stacking).

    PixelCard.tsx (update from T7)
      - Add optional `dealAnimationDelay?: number` for staggered entrance on board/hole.
      - Export helper `deriveCardAnimations(prevCards, nextCards)` for T8 to diff.

    Pot.tsx (update from T7)
      - Watch for numeric changes; export trigger for `.num.pop` animation.
      - Hook: `useAnimatePopOnChange(potAmount)`.

    Seat/SeatPod.tsx (update from T7)
      - Already has `.actor` class styling.
      - T8 supplies CSS animation rule for `.seat.actor .pod` (podpulse).

    ChipFly.tsx
      - Renders a seat's bet chip; consumes 'chip-fly' events from
        useAnimationQueue (derived from per-seat bet diffs, not DOM watching).
      - Applies entrance animation via CSS.
      - Cleanup on unmount.

    TimerRing.tsx (update from T7)
      - Already renders the ring; T8 supplies CSS `conic-gradient` animation.
      - Hook: `useTimerRingAnimation(secondsRemaining, isLow)` for pulsing red + tremble.

    ActionBar.tsx (update from T7)
      - Styling for fold/call/raise buttons (3D shadow effect, active state).
      - Raise slider styling (thumb, track with gradient fill).
      - Pills (min/½pot/pot/all-in) with hover/active states.
      - Optional `disabled` prop for reconnecting state.

    ShowdownBanner.tsx
      - Center banner animation entrance (bannerin keyframe).
      - Props: winner name, amount, hand description.

  hooks/
    useAnimationQueue.ts
      - Snapshot diff → animation event derivation.
      - Queues animations (entrance/pop/fly) with timing stagger.
      - Cleanup via useEffect return.
      - Types: `AnimationEvent` (type, target, delay, duration).

    useReducedMotion.ts
      - `matchMedia('(prefers-reduced-motion: reduce)').matches`.
      - Returns boolean; components skip CSS animations and use instant transitions.

    useWebGLCanvas.ts
      - Create WebGL context with error handling.
      - Detect unavailability; return `null` and let CSS fallback show.
      - Props: canvas ref, shader source code.

  styles/
    animations.css
      - Keyframes: numpop, bob, winpulse, podpulse, chipfly, bannerin, grainshift, tremble.
      - Reduced-motion: `animation: none !important; transition: none !important`.

    crt.css
      - CRT scanlines, grain, vignette CSS.
      - Can be imported independently.

    table.css (update from T7)
      - Table layout, ellipse, seat pod geometry.
      - Appends T8 animation rules.
```

## Animation architecture

### Snapshot-diff animation derivation

T7's Room component receives a `RoomSnapshot` from the STOMP store on every change. T8 watches this snapshot and derives animation events:

1. **Card entrance (board/hole):** `useEffect` compares `snapshot.board.length` with previous length. New cards get `dealAnimationDelay` staggered by index.
2. **Pot amount change:** `useEffect` compares `snapshot.pot` with previous value. Change triggers `.num.pop` animation via CSS.
3. **Bet chip addition:** `useAnimationQueue` compares each seat's committed
   bet between snapshots; an increase emits a 'chip-fly' event for that seat.
   No DOM watching.
4. **Actor change:** `useEffect` detects new `.seat.actor` class. Applied pod pulse animation CSS is already present.
5. **Timer countdown:** T7's timer hook updates seconds remaining; T8's timer ring watches and updates the conic-gradient `--p` variable for visual decay.

Each animation is independent and does NOT change the store. The store is the single source of truth; T8 only observes and triggers CSS animations as a side effect.

```typescript
// Pseudo-code: useAnimationQueue.ts
export function useAnimationQueue(
  currentSnapshot: RoomSnapshot,
  prevSnapshot: RoomSnapshot | null
) {
  const [animations, setAnimations] = useState<AnimationEvent[]>([]);

  useEffect(() => {
    if (!prevSnapshot) return; // First render
    const events: AnimationEvent[] = [];

    // Detect card additions
    const prevCount = prevSnapshot.board?.length ?? 0;
    const currCount = currentSnapshot.board?.length ?? 0;
    if (currCount > prevCount) {
      for (let i = prevCount; i < currCount; i++) {
        events.push({
          type: 'card-entrance',
          index: i,
          delay: i * 120, // stagger by 120ms
        });
      }
    }

    // Detect pot change
    if (currentSnapshot.pot !== prevSnapshot.pot) {
      events.push({
        type: 'pot-pop',
        delay: 0,
      });
    }

    setAnimations(events);
    // Cleanup after animations finish
    const timers = events.map(e =>
      setTimeout(() => {
        setAnimations(prev => prev.filter(a => a !== e));
      }, 350 + e.delay)
    );
    return () => timers.forEach(clearTimeout);
  }, [currentSnapshot, prevSnapshot]);

  return animations;
}
```

### Reduced-motion handling

On `prefers-reduced-motion: reduce`:

1. WebGL frame loop renders exactly one frame (no animation loop).
2. All CSS animations disabled via `*::before/after { animation: none !important; }`.
3. UI remains interactive and visually complete, just without motion.
4. T7 store continues updating; only visual playback is suppressed.

### CSS Transform discipline

All animations use `transform`, `opacity`, or `filter` to avoid layout thrashing:

- Card entrance: `translate(-10%, 0) opacity: 0` → normal.
- Chip fly: `translate(sx, sy) opacity: 0` → final position.
- Pot pop: `scale(1)` → `scale(1.35)` at 30%.
- Pod pulse: box-shadow glow changes only (no geometry reflow).
- Timer ring: conic-gradient only (no DOM mutation).

### WebGL unavailable fallback

When `gl = canvas.getContext('webgl')` returns `null`:

1. `SwirlBackground` catches the error and returns an empty canvas (no render).
2. CSS background gradient in `.stage` remains visible behind all content.
3. Game is fully playable; only visual enhancement is missing.
4. No error is thrown or logged (graceful degradation).

## Performance constraints

1. **Single WebGL context:** Only one `canvas#swirl` runs at full screen size. Undersampling at 0.66× DPR reduces fill rate by ~56% while maintaining soft-look Perlin effect.
2. **RequestAnimationFrame discipline:** Frame loop uses `RAF` (not `setInterval`). If `prefers-reduced-motion`, one frame is rendered immediately and RAF is not scheduled.
3. **CSS transforms over reflow:** All animations use `transform` and `opacity`, never `left`/`width`/`margin`.
4. **Animation queue cleanup:** Animations are removed from state once their CSS duration expires, preventing state bloat.
5. **Grain animation:** Grain is a repeating 160×160px SVG pattern, shifted every frame via `translate`. Cost: negligible (single DOM element, no JavaScript overhead).
6. **No DOM observation:** All animation events, including chip fly-in, derive
   from snapshot diffs in one hook; no MutationObserver or per-element watchers.

## Failure behavior

- **WebGL unavailable:** Canvas renders nothing; CSS gradient fallback is visible.
- **Shader compilation error:** Error is caught silently; CSS fallback is used.
- **Canvas resize during animation:** RAF updates viewport; animated elements remain positioned correctly.
- **Snapshot arrives during animation:** New animation events are queued independently; ongoing animations play to completion.
- **Reduced-motion user:** All keyframe animations skip; UI is fully functional and complete.
- **Very old browser (no CSS Grid/Flexbox):** Graceful degradation; seats may overlap, but core gameplay is readable.

## Implementation sequence

1. **Phase 1: WebGL + CRT overlays (no store wiring)**
   - Implement `SwirlBackground.tsx` with shader code from mockup.
   - Implement `CrtOverlay.tsx` with scanlines, grain, vignette.
   - Verify in a standalone test component.
   - Test WebGL fallback (disable WebGL in DevTools).

2. **Phase 2: Animation queue and hooks**
   - Implement `useAnimationQueue.ts` with snapshot-diff logic.
   - Implement `useReducedMotion.ts` for media query.
   - Add keyframes to `animations.css` (numpop, bob, winpulse, podpulse, chipfly, bannerin, grainshift, tremble).
   - Test animation derivation with mock snapshots.

3. **Phase 3: Component updates**
   - Update `PixelCard.tsx` to export `deriveCardAnimations()` helper.
   - Update `Pot.tsx` to trigger `.num.pop` animation on change.
   - Update `Seat/SeatPod.tsx` to ensure `.actor` class is applied.
   - Update `TimerRing.tsx` with conic-gradient animation.
   - Update `ActionBar.tsx` with 3D button/slider styling.

4. **Phase 4: Room integration**
   - Add `<SwirlBackground />` to Room background.
   - Add `<CrtOverlay />` on top.
   - Integrate `useAnimationQueue()` in Room.
   - Connect snapshot diff → animation events.
   - Wire pot/card/chip animation triggers.

5. **Phase 5: Visual QA and tuning**
   - Test all seat counts (2–9).
   - Test animation timing and stagger (dev tools slow-motion).
   - Test reduced-motion compliance.
   - Test on mobile (responsive scaling).
   - Measure frame rate during heavy animation load (60fps target).

## Acceptance criteria

- **WebGL swirl background** renders continuously and smoothly (60fps) or degrades to CSS gradient when unavailable.
- **CRT overlays** (scanlines, grain, vignette) are visible and do not impact performance.
- **Card animations** (bob, entrance stagger on board) play correctly and align with game events.
- **Pot number animation** (pop scale) triggers when pot changes.
- **Chip fly** entrance animation plays when bets are placed.
- **Timer ring** decays visually with countdown and pulses red when low.
- **Actor pod glow and pulse** highlight the player whose turn it is.
- **Reduced-motion mode** disables all keyframe animations; UI remains functional and complete.
- **WebGL fallback** to CSS gradient is seamless when WebGL is unavailable.
- **Action bar** is styled with 3D button effects and raise slider fill gradient.
- **All animations use `transform`/`opacity`** and do not trigger layout recalculation.
- **No layout shift** when animations play or end.
- **Animations are independent of store mutations:** they derive from snapshots, not commands.
- **Frame rate remains ≥60fps** during active gameplay, as measured by Chrome DevTools Performance.
