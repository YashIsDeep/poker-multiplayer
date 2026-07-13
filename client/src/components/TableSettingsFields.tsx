import type { CSSProperties } from 'react'
import type { TableSettings } from '../store/types'
import { formatChips } from '../lib/chips'

export const PUBLIC_TABLE_SETTINGS: TableSettings = {
  bigBlind: 100_000,
  startingStack: 10_000_000,
  rebuysEnabled: false,
  rebuyStack: 10_000_000,
  rebuyDelayHands: 5,
}

export const STACK_BB_OPTIONS = [50, 100, 200, 500] as const
const REBUY_DELAY_OPTIONS = [0, 1, 3, 5, 10, 20] as const

export function valueAtPower(power: number): number {
  return 10 ** Math.round(power)
}

export function closestPower(value: number, min: number, max: number): number {
  if (!Number.isFinite(value) || value <= 0) return min
  return Math.min(max, Math.max(min, Math.round(Math.log10(value))))
}

function closestOptionIndex(value: number, options: readonly number[]): number {
  if (!Number.isFinite(value)) return 0
  return options.reduce(
    (best, option, index) => Math.abs(option - value) < Math.abs(options[best] - value) ? index : best,
    0,
  )
}

function stackBigBlinds(stack: number, bigBlind: number): number {
  return STACK_BB_OPTIONS[closestOptionIndex(stack / bigBlind, STACK_BB_OPTIONS)]
}

function formatBigBlinds(value: number): string {
  return `${formatChips(value)} BB`
}

export function normalizeTableSettings(settings: TableSettings): TableSettings {
  const bigBlind = valueAtPower(closestPower(settings.bigBlind, 1, 6))
  return {
    bigBlind,
    startingStack: bigBlind * stackBigBlinds(settings.startingStack, bigBlind),
    rebuysEnabled: settings.rebuysEnabled,
    rebuyStack: bigBlind * stackBigBlinds(settings.rebuyStack, bigBlind),
    rebuyDelayHands: REBUY_DELAY_OPTIONS[
      closestOptionIndex(settings.rebuyDelayHands, REBUY_DELAY_OPTIONS)
    ],
  }
}

export function tableSettingsError(settings: TableSettings): string | null {
  if (!Number.isSafeInteger(settings.bigBlind) || settings.bigBlind < 1) {
    return 'Choose a valid big blind.'
  }
  if (!Number.isSafeInteger(settings.startingStack) || settings.startingStack < settings.bigBlind * 20) {
    return 'Choose a valid starting stack.'
  }
  if (!Number.isSafeInteger(settings.rebuyStack) || settings.rebuyStack < 1) {
    return 'Choose a valid rebuy stack.'
  }
  if (!Number.isSafeInteger(settings.rebuyDelayHands)
      || settings.rebuyDelayHands < 0 || settings.rebuyDelayHands > 20) {
    return 'Choose a rebuy delay between 0 and 20 hands.'
  }
  return null
}

function OptionChipSlider({
  label,
  hint,
  value,
  options,
  tone,
  disabled,
  scaleLabel,
  formatValue = formatChips,
  onChange,
}: {
  label: string
  hint: string
  value: number
  options: readonly number[]
  tone: 'orange' | 'blue' | 'green'
  disabled: boolean
  scaleLabel: string
  formatValue?: (value: number) => string
  onChange: (value: number) => void
}) {
  const index = closestOptionIndex(value, options)
  const fill = options.length === 1 ? 100 : (index / (options.length - 1)) * 100
  const style = { '--fill': `${fill}%` } as CSSProperties

  return (
    <div className="power-setting">
      <div className="power-setting-head">
        <span>
          <span className="label">{label}</span>
          <small>{hint}</small>
        </span>
        <output className={`power-value num ${tone}`}>{formatValue(options[index])}</output>
      </div>
      <input
        className={`power-chip-slider ${tone}`}
        type="range"
        min={0}
        max={options.length - 1}
        step={1}
        value={index}
        disabled={disabled}
        style={style}
        aria-label={label}
        aria-valuetext={formatValue(options[index])}
        onChange={(event) => onChange(options[Number(event.target.value)])}
      />
      <div className="power-scale num" aria-hidden="true">
        <span>{formatValue(options[0])}</span>
        <span>{scaleLabel}</span>
        <span>{formatValue(options[options.length - 1])}</span>
      </div>
    </div>
  )
}

const BIG_BLIND_OPTIONS = [1, 2, 3, 4, 5, 6].map(valueAtPower)
function formatHands(value: number): string {
  if (value === 0) return 'No delay'
  return `${value} ${value === 1 ? 'hand' : 'hands'}`
}

export default function TableSettingsFields({
  value,
  onChange,
  disabled = false,
}: {
  value: TableSettings
  onChange: (next: TableSettings) => void
  disabled?: boolean
}) {
  const startingBB = stackBigBlinds(value.startingStack, value.bigBlind)
  const rebuyBB = stackBigBlinds(value.rebuyStack, value.bigBlind)

  const setBigBlind = (bigBlind: number) => onChange({
    ...value,
    bigBlind,
    startingStack: bigBlind * startingBB,
    rebuyStack: bigBlind * rebuyBB,
  })

  return (
    <section className="table-settings-fields" aria-label="Table chip settings">
      <OptionChipSlider
        label="Big blind"
        hint="Small blind is half"
        value={value.bigBlind}
        options={BIG_BLIND_OPTIONS}
        tone="orange"
        disabled={disabled}
        scaleLabel="×10"
        onChange={setBigBlind}
      />

      <OptionChipSlider
        label="Starting stack"
        hint={`${formatChips(value.startingStack)} chips · future seats`}
        value={startingBB}
        options={STACK_BB_OPTIONS}
        tone="blue"
        disabled={disabled}
        scaleLabel="50 · 100 · 200 · 500"
        formatValue={formatBigBlinds}
        onChange={(multiple) => onChange({ ...value, startingStack: value.bigBlind * multiple })}
      />

      <div className="rebuy-setting-head">
        <span>
          <span className="label">Rebuys</span>
          <small>Allow busted players to buy back in</small>
        </span>
        <button
          type="button"
          className={`pixel-switch${value.rebuysEnabled ? ' on' : ''}`}
          data-sound={value.rebuysEnabled ? 'toggleOff' : 'toggleOn'}
          aria-pressed={value.rebuysEnabled}
          disabled={disabled}
          onClick={() => onChange({ ...value, rebuysEnabled: !value.rebuysEnabled })}
        >
          {value.rebuysEnabled ? 'ON' : 'OFF'}
        </button>
      </div>

      {value.rebuysEnabled && (
        <div className="rebuy-power-setting">
          <OptionChipSlider
            label="Rebuy stack"
            hint={`${formatChips(value.rebuyStack)} chips · future rebuys`}
            value={rebuyBB}
            options={STACK_BB_OPTIONS}
            tone="green"
            disabled={disabled}
            scaleLabel="50 · 100 · 200 · 500"
            formatValue={formatBigBlinds}
            onChange={(multiple) => onChange({ ...value, rebuyStack: value.bigBlind * multiple })}
          />
          <OptionChipSlider
            label="Rebuy delay"
            hint="Hands to wait after busting"
            value={value.rebuyDelayHands}
            options={REBUY_DELAY_OPTIONS}
            tone="green"
            disabled={disabled}
            scaleLabel="cooldown"
            formatValue={formatHands}
            onChange={(rebuyDelayHands) => onChange({ ...value, rebuyDelayHands })}
          />
        </div>
      )}
    </section>
  )
}
