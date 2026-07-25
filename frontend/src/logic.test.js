import { describe, it, expect } from 'vitest'
import { fmt, flow, formatUptime, minerView } from './logic.js'

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
  it('OFFLINE → red dot, is-offline', () => {
    const v = minerView({ reachable: false, running: false, state: 'OFFLINE' })
    expect(v).toMatchObject({ statusText: 'Offline', dot: 'offline', cardCls: 'is-offline' })
  })
  it('unknown state falls back sensibly by reachability', () => {
    expect(minerView({ reachable: true, state: 'WEIRD' }).statusText).toBe('Unknown')
    expect(minerView({ reachable: false, state: 'WEIRD' }).statusText).toBe('Offline')
  })
})
