import { describe, it, expect } from 'vitest'
import { fmt, flow, formatUptime, formatDuration, minerView, historyWindow, daylightExtent, pointerToSvgX } from './logic.js'

describe('fmt', () => {
  it('renders "--" for non-numeric / missing values', () => {
    expect(fmt(null)).toBe('--')
    expect(fmt(undefined)).toBe('--')
    expect(fmt(NaN)).toBe('--')
    expect(fmt('abc')).toBe('--')
  })
  it('formats numbers to the given precision (default 2)', () => {
    expect(fmt(0)).toBe('0.00')
    expect(fmt(1.2345)).toBe('1.23')
    expect(fmt(95, 1)).toBe('95.0')
    expect(fmt(1200, 0)).toBe('1200')
    expect(fmt(-0.5, 2)).toBe('-0.50')
    expect(fmt('1.5')).toBe('1.50') // numeric strings are accepted
  })
})

describe('flow', () => {
  // (solar, house) — house measured directly; net = solar − house.
  it('exports when solar exceeds house', () => {
    const f = flow(3, 1.5)
    expect(f.net).toBe(1.5)
    expect(f.exporting).toBe(true)
    expect(f.gridFlow).toBe(1.5)
    expect(f.coverage).toBe(100) // capped
  })
  it('imports when house exceeds solar', () => {
    const f = flow(1, 4)
    expect(f.net).toBe(-3)
    expect(f.exporting).toBe(false)
    expect(f.gridFlow).toBe(3)
    expect(f.coverage).toBeCloseTo(25, 5)
  })
  it('treats an exact balance as exporting (net 0)', () => {
    const f = flow(2, 2)
    expect(f.net).toBe(0)
    expect(f.exporting).toBe(true)
    expect(f.gridFlow).toBe(0)
    expect(f.coverage).toBe(100)
  })
  it('handles zero / missing house load without dividing by zero', () => {
    expect(flow(0, 0).coverage).toBe(100)
    expect(flow(2, 0).coverage).toBe(100)
    expect(flow(undefined, undefined).net).toBe(0)
    expect(flow(NaN, NaN).coverage).toBe(100)
  })
  it('rounds net to milli-kW', () => {
    expect(flow(1.23456, 0.5).net).toBe(0.735)
  })
})

describe('formatUptime', () => {
  it('returns null for missing values', () => {
    expect(formatUptime(null)).toBeNull()
    expect(formatUptime(undefined)).toBeNull()
    expect(formatUptime(NaN)).toBeNull()
  })
  it('shows minutes under an hour and h+m at/above an hour', () => {
    expect(formatUptime(0)).toBe('0m')
    expect(formatUptime(59)).toBe('0m')
    expect(formatUptime(600)).toBe('10m')
    expect(formatUptime(3600)).toBe('1h 0m')
    expect(formatUptime(3660)).toBe('1h 1m')
    expect(formatUptime(7325)).toBe('2h 2m')
  })
})

describe('formatDuration', () => {
  it('renders "--" for missing / negative values', () => {
    expect(formatDuration(null)).toBe('--')
    expect(formatDuration(undefined)).toBe('--')
    expect(formatDuration(NaN)).toBe('--')
    expect(formatDuration(-5)).toBe('--')
  })
  it('scales the unit to the magnitude', () => {
    expect(formatDuration(0)).toBe('0s')
    expect(formatDuration(45)).toBe('45s')
    expect(formatDuration(60)).toBe('1m 0s')
    expect(formatDuration(125)).toBe('2m 5s')
    expect(formatDuration(3600)).toBe('1h 0m')
    expect(formatDuration(3660)).toBe('1h 1m')
    expect(formatDuration(90061)).toBe('1d 1h 1m') // 1d + 1h + 1m + 1s
  })
})

describe('minerView', () => {
  const base = { reachable: true, running: true }
  it('maps null to a connecting placeholder', () => {
    const v = minerView(null)
    expect(v.statusText).toBe('Connecting…')
    expect(v.reachable).toBe(false)
    expect(v.mining).toBe(false)
  })
  it('MINING → green dot, is-on', () => {
    const v = minerView({ ...base, state: 'MINING', uptimeSeconds: 3600 })
    expect(v).toMatchObject({ statusText: 'Mining', dot: 'on', cardCls: 'is-on', mining: true, upStr: '1h 0m' })
  })
  it('SUSPENDED → amber dot, is-warn', () => {
    const v = minerView({ ...base, state: 'SUSPENDED' })
    expect(v).toMatchObject({ statusText: 'Suspended', dot: 'warn', cardCls: 'is-warn', mining: false })
  })
  it('STOPPED → grey dot, is-off', () => {
    const v = minerView({ reachable: true, running: false, state: 'STOPPED' })
    expect(v).toMatchObject({ statusText: 'Stopped', dot: 'off', cardCls: 'is-off' })
  })
  it('OFFLINE with no error → clean "Off"', () => {
    const v = minerView({ reachable: false, running: false, state: 'OFFLINE' })
    expect(v).toMatchObject({ statusText: 'Off', dot: 'offline', cardCls: 'is-offline' })
  })
  it('OFFLINE with a genuine error → "Offline"', () => {
    const v = minerView({ reachable: false, running: false, state: 'OFFLINE', error: 'connection refused' })
    expect(v.statusText).toBe('Offline')
  })
  it('unknown state falls back sensibly by reachability', () => {
    expect(minerView({ reachable: true, state: 'WEIRD' }).statusText).toBe('Unknown')
    expect(minerView({ reachable: false, state: 'WEIRD' }).statusText).toBe('Off')
  })
})

describe('historyWindow', () => {
  const H = 3600e3
  const NOW = Date.parse('2026-07-27T15:30:00') // local

  it('span window: offset 0 ends at now and spans the requested hours', () => {
    const w = historyWindow({ hours: 4 }, 0, NOW)
    expect(w.toMs).toBe(NOW)
    expect(w.fromMs).toBe(NOW - 4 * H)
  })

  it('span window: each offset steps one whole span back', () => {
    const w1 = historyWindow({ hours: 8 }, 1, NOW)
    expect(w1.toMs).toBe(NOW - 8 * H)
    expect(w1.fromMs).toBe(NOW - 16 * H)
    const w2 = historyWindow({ hours: 1 }, 3, NOW)
    expect(w2.toMs).toBe(NOW - 3 * H)
    expect(w2.fromMs).toBe(NOW - 4 * H)
  })

  it('span window: negative/fractional offsets clamp to a whole ≥ 0 step', () => {
    expect(historyWindow({ hours: 2 }, -5, NOW)).toEqual(historyWindow({ hours: 2 }, 0, NOW))
  })

  it('today: offset 0 runs from local midnight to now', () => {
    const w = historyWindow({ today: true }, 0, NOW)
    const mid = new Date(w.fromMs)
    expect(mid.getHours()).toBe(0)
    expect(mid.getMinutes()).toBe(0)
    expect(mid.getSeconds()).toBe(0)
    expect(new Date(w.fromMs).getDate()).toBe(new Date(NOW).getDate()) // same calendar day
    expect(w.toMs).toBe(NOW) // capped at now (still daytime)
  })

  it('today: offset 1 is the whole previous local day (midnight→midnight)', () => {
    const today = historyWindow({ today: true }, 0, NOW)
    const prev = historyWindow({ today: true }, 1, NOW)
    expect(prev.toMs).toBe(today.fromMs)            // ends exactly at today's midnight
    expect(new Date(prev.fromMs).getHours()).toBe(0) // starts at a local midnight
    const gapH = (prev.toMs - prev.fromMs) / H
    expect(gapH).toBeGreaterThanOrEqual(23)          // ~24h (DST-tolerant)
    expect(gapH).toBeLessThanOrEqual(25)
  })
})

describe('daylightExtent', () => {
  const iso = (h) => new Date(Date.parse('2026-07-27T00:00:00Z') + h * 3600e3).toISOString()

  it('returns first→last sample above the solar threshold', () => {
    const samples = [
      { at: iso(5), solarW: 0 },      // night
      { at: iso(6), solarW: 30 },     // below threshold (dawn noise)
      { at: iso(7), solarW: 400 },    // first real solar
      { at: iso(12), solarW: 6000 },
      { at: iso(18), solarW: 200 },   // last real solar
      { at: iso(20), solarW: 0 },     // night
    ]
    const ext = daylightExtent(samples, 50)
    expect(ext).toEqual([Date.parse(iso(7)), Date.parse(iso(18))])
  })

  it('accepts epoch-ms timestamps too', () => {
    const t0 = Date.parse('2026-07-27T07:00:00Z')
    const ext = daylightExtent([{ at: t0, solarW: 500 }, { at: t0 + 3600e3, solarW: 800 }], 50)
    expect(ext).toEqual([t0, t0 + 3600e3])
  })

  it('returns null when there is no solar (all night / below threshold)', () => {
    expect(daylightExtent([{ at: iso(2), solarW: 0 }, { at: iso(3), solarW: 10 }], 50)).toBeNull()
    expect(daylightExtent([], 50)).toBeNull()
    expect(daylightExtent(null, 50)).toBeNull()
  })

  it('returns null for a single instant of solar (no span to draw)', () => {
    expect(daylightExtent([{ at: iso(12), solarW: 5000 }], 50)).toBeNull()
  })

  it('ignores null solar and malformed timestamps', () => {
    const samples = [
      { at: iso(7), solarW: null },
      { at: 'not-a-date', solarW: 500 },
      { at: iso(9), solarW: 500 },
      { at: iso(11), solarW: 500 },
    ]
    expect(daylightExtent(samples, 50)).toEqual([Date.parse(iso(9)), Date.parse(iso(11))])
  })
})

describe('pointerToSvgX', () => {
  it('maps the cursor into internal SVG coordinates', () => {
    // SVG rendered at 200px wide starting at x=100 on screen; internal coord width 400.
    expect(pointerToSvgX(100, 100, 200, 400)).toBe(0)     // at the left edge → 0
    expect(pointerToSvgX(300, 100, 200, 400)).toBe(400)   // at the right edge → svgWidth
    expect(pointerToSvgX(150, 100, 200, 400)).toBe(100)   // 25% across → 25% of 400
    expect(pointerToSvgX(200, 100, 200, 400)).toBe(200)   // halfway (scaled) → svgWidth/2
  })
  it('is scale-correct: when render width == internal width it is a plain offset', () => {
    expect(pointerToSvgX(250, 50, 800, 800)).toBe(200) // 250-50 = 200, no scaling
  })
  it('guards against a zero-width rect (pre-layout / jsdom)', () => {
    expect(pointerToSvgX(123, 0, 0, 960)).toBe(0)
  })
})
