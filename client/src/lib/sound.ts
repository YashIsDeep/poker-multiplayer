// Synthesized sound engine. Every effect is generated on the fly with the Web
// Audio API — no asset files to load, which keeps the bundle lean and matches
// the retro CRT / pixel aesthetic. The engine is a plain module singleton;
// React binds to it through useSoundSettings (mute/volume) and useGameSounds
// (gameplay-diff → effect). Everything degrades to a silent no-op when the
// Web Audio API is unavailable (SSR, jsdom tests, locked-down browsers).

export type SoundName =
  | 'click' // generic UI blip
  | 'tick' // small value/preset adjustment
  | 'open' // open a panel or begin a flow
  | 'close' // close a panel or go back
  | 'confirm' // submit or accept an operation
  | 'toggleOn' // enable a setting
  | 'toggleOff' // disable a setting
  | 'retry' // reconnect / try again
  | 'exit' // leave a seat, table, session, or tab
  | 'seat' // take a seat or sit back in
  | 'rebuy' // buy a fresh chip stack
  | 'deal' // new hand — cards riffled out
  | 'flop' // three community cards fan onto the felt
  | 'card' // a single community card flips
  | 'check' // a player knocks / checks
  | 'call' // a player calls — one chip
  | 'raise' // a player bets or raises — chip stack
  | 'fold' // a player folds — cards slide away
  | 'yourTurn' // it is now your turn to act
  | 'timer' // low-time countdown pulse
  | 'timerUrgent' // final countdown pulse
  | 'win' // pot awarded to someone else
  | 'winBig' // pot awarded to you
  | 'message' // incoming table chat
  | 'playerJoin' // another player joins the room
  | 'playerLeave' // another player leaves the room
  | 'connectionLost' // live connection dropped
  | 'connectionRestored' // live connection recovered
  | 'notify' // soft positive confirmation (seat taken, room joined)
  | 'error' // rejected action / error toast

export function isSoundName(value: string | null): value is SoundName {
  return value !== null && Object.prototype.hasOwnProperty.call(RECIPES, value)
}

type Prefs = { muted: boolean; volume: number }

const STORAGE_KEY = 'poker.sound.v1'
const DEFAULT_PREFS: Prefs = { muted: false, volume: 0.7 }

function loadPrefs(): Prefs {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULT_PREFS }
    const parsed = JSON.parse(raw) as Partial<Prefs>
    return {
      muted: typeof parsed.muted === 'boolean' ? parsed.muted : DEFAULT_PREFS.muted,
      volume:
        typeof parsed.volume === 'number' && parsed.volume >= 0 && parsed.volume <= 1
          ? parsed.volume
          : DEFAULT_PREFS.volume,
    }
  } catch {
    return { ...DEFAULT_PREFS }
  }
}

function savePrefs(prefs: Prefs) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // ignore — private mode / storage disabled
  }
}

// ---------- engine state ----------

let prefs: Prefs = typeof window !== 'undefined' ? loadPrefs() : { ...DEFAULT_PREFS }
const listeners = new Set<() => void>()

let ctx: AudioContext | null = null
let master: GainNode | null = null
let noiseBuffer: AudioBuffer | null = null
let unlockBound = false

type AudioCtor = typeof AudioContext
function audioCtor(): AudioCtor | null {
  if (typeof window === 'undefined') return null
  return (
    window.AudioContext ??
    (window as unknown as { webkitAudioContext?: AudioCtor }).webkitAudioContext ??
    null
  )
}

function ensureContext(): AudioContext | null {
  if (ctx) return ctx
  const Ctor = audioCtor()
  if (!Ctor) return null
  try {
    ctx = new Ctor()
    master = ctx.createGain()
    master.gain.value = prefs.volume
    master.connect(ctx.destination)
    // ~0.7s of white noise, reused by every noise-based effect.
    const buffer = ctx.createBuffer(1, Math.floor(ctx.sampleRate * 0.7), ctx.sampleRate)
    const data = buffer.getChannelData(0)
    for (let i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1
    noiseBuffer = buffer
    return ctx
  } catch {
    ctx = null
    master = null
    return null
  }
}

function notify() {
  for (const fn of listeners) fn()
}

// ---------- public preference API ----------

export const soundEngine = {
  isMuted: () => prefs.muted,
  getVolume: () => prefs.volume,

  setMuted(muted: boolean) {
    if (prefs.muted === muted) return
    prefs = { ...prefs, muted }
    savePrefs(prefs)
    if (!muted) void unlockAudio() // a mute toggle counts as a gesture
    notify()
  },

  toggleMuted() {
    soundEngine.setMuted(!prefs.muted)
  },

  setVolume(volume: number) {
    const clamped = Math.min(1, Math.max(0, volume))
    prefs = { ...prefs, volume: clamped }
    savePrefs(prefs)
    if (master) master.gain.value = clamped
    notify()
  },

  subscribe(fn: () => void) {
    listeners.add(fn)
    return () => listeners.delete(fn)
  },

  play,
}

// ---------- first-gesture unlock ----------

// Browsers start the AudioContext suspended until a user gesture. Resume it on
// the first interaction so the very first effect isn't swallowed.
export async function unlockAudio() {
  const context = ensureContext()
  if (!context) return
  if (context.state === 'suspended') {
    try {
      await context.resume()
    } catch {
      // ignore
    }
  }
}

export function bindAutoUnlock() {
  if (unlockBound || typeof window === 'undefined') return
  unlockBound = true
  const handler = () => void unlockAudio()
  window.addEventListener('pointerdown', handler, { passive: true })
  window.addEventListener('keydown', handler)
  window.addEventListener('touchstart', handler, { passive: true })
}

// Resolve the nearest semantic sound in the click's composed path. Components
// declare intent with data-sound; unannotated controls retain the generic
// guardrail while blank surfaces stay quiet.
const INTERACTIVE_TAGS = new Set(['BUTTON', 'A', 'INPUT', 'SELECT', 'TEXTAREA', 'SUMMARY'])

export function soundForClick(event: Event): SoundName | null {
  let interactive = false
  for (const target of event.composedPath()) {
    const candidate = target as {
      getAttribute?: (name: string) => string | null
      tagName?: string
    }
    const sound = candidate.getAttribute?.('data-sound') ?? null
    if (isSoundName(sound)) return sound
    // Sliders announce their value changes through a throttled input handler;
    // suppress the trailing click so one gesture never produces two cues.
    const inputSound = candidate.getAttribute?.('data-sound-input') ?? null
    if (isSoundName(inputSound)) return null
    const role = candidate.getAttribute?.('role') ?? null
    if (
      (candidate.tagName && INTERACTIVE_TAGS.has(candidate.tagName)) ||
      role === 'button' ||
      role === 'menuitem'
    ) {
      interactive = true
    }
    const boundary = candidate.getAttribute?.('data-sound-boundary')
    if (boundary !== null && boundary !== undefined) {
      return interactive ? 'click' : null
    }
  }
  return interactive ? 'click' : null
}

// One capture-phase boundary plays exactly one resolved sound for the whole
// app, including controls that stop propagation and controls added later.
export function bindGlobalClickSound(
  target: EventTarget | null = typeof window === 'undefined' ? null : window,
) {
  if (!target) return () => {}

  const handler = (event: Event) => {
    const sound = soundForClick(event)
    if (sound) soundEngine.play(sound)
  }
  target.addEventListener('click', handler, { capture: true })
  return () => target.removeEventListener('click', handler, { capture: true })
}

// ---------- low-level voice helpers ----------

type ToneOpts = {
  freq: number
  type?: OscillatorType
  start?: number // seconds from now
  dur?: number
  gain?: number
  glideTo?: number // frequency ramp target
  attack?: number
  release?: number
}

function tone(opts: ToneOpts) {
  if (!ctx || !master) return
  const now = ctx.currentTime
  const start = now + (opts.start ?? 0)
  const dur = opts.dur ?? 0.12
  const peak = opts.gain ?? 0.3
  const attack = opts.attack ?? 0.006
  const release = opts.release ?? dur

  const osc = ctx.createOscillator()
  osc.type = opts.type ?? 'triangle'
  osc.frequency.setValueAtTime(opts.freq, start)
  if (opts.glideTo !== undefined) {
    osc.frequency.exponentialRampToValueAtTime(Math.max(1, opts.glideTo), start + dur)
  }

  const g = ctx.createGain()
  g.gain.setValueAtTime(0.0001, start)
  g.gain.exponentialRampToValueAtTime(peak, start + attack)
  g.gain.exponentialRampToValueAtTime(0.0001, start + attack + release)

  osc.connect(g).connect(master)
  osc.start(start)
  osc.stop(start + attack + release + 0.02)
}

type NoiseOpts = {
  start?: number
  dur?: number
  gain?: number
  filter?: BiquadFilterType
  freq?: number
  q?: number
  sweepTo?: number
}

function noise(opts: NoiseOpts) {
  if (!ctx || !master || !noiseBuffer) return
  const now = ctx.currentTime
  const start = now + (opts.start ?? 0)
  const dur = opts.dur ?? 0.1
  const peak = opts.gain ?? 0.2

  const src = ctx.createBufferSource()
  src.buffer = noiseBuffer

  const filter = ctx.createBiquadFilter()
  filter.type = opts.filter ?? 'bandpass'
  filter.frequency.setValueAtTime(opts.freq ?? 1200, start)
  if (opts.sweepTo !== undefined) {
    filter.frequency.exponentialRampToValueAtTime(Math.max(20, opts.sweepTo), start + dur)
  }
  filter.Q.value = opts.q ?? 1

  const g = ctx.createGain()
  g.gain.setValueAtTime(0.0001, start)
  g.gain.exponentialRampToValueAtTime(peak, start + 0.005)
  g.gain.exponentialRampToValueAtTime(0.0001, start + dur)

  src.connect(filter).connect(g).connect(master)
  src.start(start)
  src.stop(start + dur + 0.02)
}

// One metallic chip = a short click of noise plus two detuned high partials.
function chip(at: number, gain = 0.22) {
  noise({ start: at, dur: 0.04, gain: gain * 0.6, filter: 'highpass', freq: 3200 })
  tone({ start: at, freq: 1750, type: 'square', dur: 0.05, gain: gain * 0.5, release: 0.05 })
  tone({ start: at, freq: 2300, type: 'square', dur: 0.05, gain: gain * 0.3, release: 0.05 })
}

function cardFlip(at = 0, gain = 1) {
  noise({
    start: at,
    dur: 0.06,
    gain: 0.2 * gain,
    filter: 'bandpass',
    freq: 1600,
    q: 0.7,
    sweepTo: 900,
  })
  tone({
    start: at + 0.02,
    freq: 520,
    type: 'triangle',
    dur: 0.05,
    gain: 0.1 * gain,
  })
}

// ---------- the sound recipes ----------

const RECIPES: Record<SoundName, () => void> = {
  click() {
    tone({ freq: 660, type: 'square', dur: 0.05, gain: 0.14, glideTo: 880, release: 0.05 })
  },
  tick() {
    tone({ freq: 980, type: 'square', dur: 0.025, gain: 0.1, glideTo: 1120, release: 0.025 })
  },
  open() {
    tone({ freq: 420, type: 'square', dur: 0.06, gain: 0.13, glideTo: 560, release: 0.06 })
    tone({ freq: 700, type: 'triangle', start: 0.055, dur: 0.08, gain: 0.16, release: 0.08 })
  },
  close() {
    tone({ freq: 700, type: 'square', dur: 0.06, gain: 0.12, glideTo: 520, release: 0.06 })
    tone({ freq: 360, type: 'triangle', start: 0.05, dur: 0.08, gain: 0.14, release: 0.08 })
  },
  confirm() {
    tone({ freq: 523, type: 'triangle', dur: 0.08, gain: 0.18, release: 0.08 })
    tone({ freq: 784, type: 'triangle', start: 0.075, dur: 0.11, gain: 0.2, release: 0.11 })
  },
  toggleOn() {
    tone({ freq: 480, type: 'square', dur: 0.04, gain: 0.12, release: 0.04 })
    tone({ freq: 760, type: 'square', start: 0.045, dur: 0.05, gain: 0.14, release: 0.05 })
  },
  toggleOff() {
    tone({ freq: 720, type: 'square', dur: 0.04, gain: 0.12, release: 0.04 })
    tone({ freq: 400, type: 'square', start: 0.045, dur: 0.05, gain: 0.12, release: 0.05 })
  },
  retry() {
    for (const [index, freq] of [360, 480, 640].entries()) {
      tone({ freq, type: 'square', start: index * 0.055, dur: 0.05, gain: 0.12, release: 0.05 })
    }
  },
  exit() {
    noise({ dur: 0.13, gain: 0.1, filter: 'lowpass', freq: 900, sweepTo: 260 })
    tone({ freq: 330, type: 'triangle', dur: 0.14, gain: 0.16, glideTo: 165, release: 0.14 })
  },
  seat() {
    tone({ freq: 145, type: 'sine', dur: 0.09, gain: 0.22, release: 0.09 })
    chip(0.035, 0.18)
  },
  rebuy() {
    chip(0, 0.18)
    chip(0.045, 0.2)
    chip(0.09, 0.22)
    tone({ freq: 784, type: 'triangle', start: 0.13, dur: 0.12, gain: 0.18, release: 0.12 })
  },
  deal() {
    // a quick riffle: five staggered card whisks
    for (let i = 0; i < 5; i++) {
      noise({ start: i * 0.045, dur: 0.05, gain: 0.16, filter: 'bandpass', freq: 2000, q: 0.8 })
    }
  },
  flop() {
    cardFlip(0, 0.9)
    cardFlip(0.09, 1)
    cardFlip(0.18, 1.05)
  },
  card() {
    cardFlip()
  },
  check() {
    // two soft knuckle knocks on the felt
    tone({ freq: 180, type: 'sine', dur: 0.08, gain: 0.28, release: 0.08 })
    tone({ freq: 170, type: 'sine', dur: 0.08, gain: 0.24, start: 0.11, release: 0.08 })
  },
  call() {
    chip(0)
  },
  raise() {
    // a small stack of chips clacking down
    chip(0, 0.2)
    chip(0.05, 0.22)
    chip(0.11, 0.24)
    chip(0.16, 0.18)
  },
  fold() {
    // cards sliding away — a soft downward whoosh
    noise({ dur: 0.22, gain: 0.18, filter: 'lowpass', freq: 1400, sweepTo: 300 })
  },
  yourTurn() {
    // bright two-note prompt (perfect fifth up)
    tone({ freq: 660, type: 'triangle', dur: 0.12, gain: 0.3 })
    tone({ freq: 990, type: 'triangle', dur: 0.16, gain: 0.32, start: 0.12 })
  },
  timer() {
    tone({ freq: 740, type: 'square', dur: 0.045, gain: 0.11, release: 0.045 })
  },
  timerUrgent() {
    tone({ freq: 880, type: 'square', dur: 0.055, gain: 0.16, release: 0.055 })
    tone({ freq: 440, type: 'triangle', start: 0.035, dur: 0.08, gain: 0.11, release: 0.08 })
  },
  win() {
    // Short major-key victory cadence for the rest of the table.
    tone({ freq: 392, type: 'sine', dur: 0.34, gain: 0.12, release: 0.34 })
    const notes = [523, 659, 784, 1047]
    notes.forEach((f, i) =>
      tone({ freq: f, type: 'triangle', dur: 0.16, gain: 0.24, start: i * 0.1, release: 0.18 }),
    )
  },
  winBig() {
    // Fuller fanfare when the viewer wins: rising arpeggio, warm bass, and a
    // sustained final chord so the result reads as a proper victory.
    tone({ freq: 262, type: 'sine', dur: 0.72, gain: 0.14, release: 0.68 })
    const notes = [523, 659, 784, 1047, 1319]
    notes.forEach((f, i) =>
      tone({ freq: f, type: 'triangle', dur: 0.2, gain: 0.27, start: i * 0.09, release: 0.22 }),
    )
    for (const freq of [523, 659, 784, 1047]) {
      tone({ freq, type: 'triangle', dur: 0.48, gain: 0.1, start: 0.42, release: 0.5 })
    }
    tone({ freq: 1568, type: 'sine', dur: 0.38, gain: 0.16, start: 0.44, release: 0.38 })
  },
  message() {
    tone({ freq: 880, type: 'sine', dur: 0.07, gain: 0.16, release: 0.07 })
    tone({ freq: 1175, type: 'triangle', start: 0.065, dur: 0.1, gain: 0.18, release: 0.1 })
  },
  playerJoin() {
    tone({ freq: 330, type: 'triangle', dur: 0.08, gain: 0.13, release: 0.08 })
    tone({ freq: 494, type: 'triangle', start: 0.07, dur: 0.1, gain: 0.15, release: 0.1 })
    chip(0.13, 0.12)
  },
  playerLeave() {
    tone({ freq: 494, type: 'triangle', dur: 0.08, gain: 0.13, release: 0.08 })
    tone({ freq: 294, type: 'triangle', start: 0.065, dur: 0.12, gain: 0.13, release: 0.12 })
  },
  connectionLost() {
    noise({ dur: 0.18, gain: 0.12, filter: 'lowpass', freq: 1100, sweepTo: 180 })
    tone({ freq: 260, type: 'sawtooth', dur: 0.18, gain: 0.12, glideTo: 110, release: 0.18 })
  },
  connectionRestored() {
    for (const [index, freq] of [392, 587, 784].entries()) {
      tone({
        freq,
        type: 'square',
        start: index * 0.06,
        dur: 0.06,
        gain: 0.11 + index * 0.02,
        release: 0.06,
      })
    }
  },
  notify() {
    tone({ freq: 587, type: 'triangle', dur: 0.1, gain: 0.24 })
    tone({ freq: 784, type: 'triangle', dur: 0.12, gain: 0.24, start: 0.09 })
  },
  error() {
    tone({ freq: 220, type: 'sawtooth', dur: 0.16, gain: 0.2, glideTo: 150, release: 0.16 })
    tone({ freq: 233, type: 'sawtooth', dur: 0.16, gain: 0.14, glideTo: 150, release: 0.16 })
  },
}

function play(name: SoundName) {
  if (prefs.muted) return
  const context = ensureContext()
  if (!context || !master) return
  // A resume() may be pending from the unlock gesture; if the context is still
  // suspended the scheduled nodes simply won't sound, which is acceptable.
  if (context.state === 'suspended') void unlockAudio()
  master.gain.value = prefs.volume
  try {
    RECIPES[name]()
  } catch {
    // never let audio break the UI
  }
}

export function playSound(name: SoundName) {
  play(name)
}
