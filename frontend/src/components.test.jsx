import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MinerCard, EnergyFlow, CoverageRing, Sparkline } from './App.jsx'

function miner(over = {}) {
  return {
    reachable: true, running: true, state: 'MINING', model: 'Antminer S19k Pro',
    powerTargetW: 1200, tunerEnabled: true, activePools: 1, totalPools: 1,
    hashrateThs: 95, powerDrawW: 1180, fans: [{ name: '0', rpm: 3000, speedPercent: 80 }],
    uptimeSeconds: 3600, statusReason: null, error: null, ...over,
  }
}
const noop = () => {}

describe('MinerCard', () => {
  it('shows Mining state with hashrate, draw and fan rpm', () => {
    const { container } = render(
      <MinerCard miner={miner()} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    const t = container.textContent
    expect(t).toContain('Mining')
    expect(t).toContain('95.0')     // TH/s
    expect(t).toContain('1180')     // W draw
    expect(t).toContain('3000')     // fan rpm
  })

  it('Start disabled while running, Stop enabled', () => {
    render(<MinerCard miner={miner({ running: true })} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(screen.getByRole('button', { name: 'Start' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop' })).toBeEnabled()
  })

  it('Start enabled while stopped, Stop disabled', () => {
    const m = miner({ running: false, state: 'STOPPED', hashrateThs: null, powerDrawW: null, fans: [] })
    render(<MinerCard miner={m} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(screen.getByRole('button', { name: 'Start' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled()
  })

  it('all controls disabled while a command is pending', () => {
    render(<MinerCard miner={miner()} pending onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(screen.getByRole('button', { name: 'Start' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    expect(screen.getByRole('spinbutton')).toBeDisabled()
  })

  it('offline: controls disabled and the error is shown', () => {
    const m = miner({ reachable: false, running: false, state: 'OFFLINE', error: 'connection refused' })
    render(<MinerCard miner={m} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(screen.getByText('connection refused')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled()
  })

  it('suspended: shows the reason and hides hashrate', () => {
    const m = miner({ running: true, state: 'SUSPENDED', hashrateThs: null, powerDrawW: null,
      statusReason: 'Suspended: no active pool' })
    const { container } = render(
      <MinerCard miner={m} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(screen.getByText('Suspended: no active pool')).toBeInTheDocument()
    expect(container.textContent).not.toContain('TH/s')
  })

  it('Apply sends the parsed power target; Start/Stop invoke callbacks', () => {
    const onSetPower = vi.fn(), onStart = vi.fn(), onStop = vi.fn()
    render(<MinerCard miner={miner({ powerTargetW: 1200 })} pending={false}
      onStart={onStart} onStop={onStop} onSetPower={onSetPower} />)
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '2400' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(onSetPower).toHaveBeenCalledWith(2400)
    fireEvent.click(screen.getByRole('button', { name: 'Stop' }))
    expect(onStop).toHaveBeenCalledOnce()
  })

  it('null miner renders a connecting placeholder without crashing', () => {
    render(<MinerCard miner={null} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(screen.getByText('Connecting…')).toBeInTheDocument()
    expect(screen.getByText('Braiins Miner')).toBeInTheDocument()  // name fallback
  })
})

describe('EnergyFlow', () => {
  const house = (over) => ({ kw: 0.5, metered: true, powerW: 500, ts: 't', ageSec: 3, ...over })

  it('metered home shows the measured value (read-only, no input)', () => {
    const { container } = render(<EnergyFlow solar={3} house={house({ kw: 1.5 })} spark={[]} />)
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument() // no editable input when metered
    expect(container.textContent).toMatch(/exporting/i)              // solar 3 > house 1.5
  })

  it('metered importing home shows the topping-up caption', () => {
    const { container } = render(<EnergyFlow solar={0.2} house={house({ kw: 2.0 })} spark={[]} />)
    expect(container.textContent).toMatch(/topping up/i)             // solar 0.2 < house 2.0 → importing
  })

  it('unmetered home shows waiting-for-meter, no input, and unavailable margin', () => {
    const { container } = render(<EnergyFlow solar={3} house={{ kw: null, metered: false }} spark={[]} />)
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument() // no editable baseline any more
    expect(container.textContent).toMatch(/waiting for/i)            // meter offline message
    expect(container.textContent).toMatch(/unavailable/i)            // margin can't be computed
  })
})

describe('CoverageRing', () => {
  it('clamps the percentage to 0..100', () => {
    const { rerender } = render(<CoverageRing pct={150} covering />)
    expect(screen.getByText('100')).toBeInTheDocument()
    rerender(<CoverageRing pct={-20} covering={false} />)
    expect(screen.getByText('0')).toBeInTheDocument()
  })
})

describe('Sparkline', () => {
  it('renders nothing meaningful for < 2 points', () => {
    const { container } = render(<Sparkline data={[1]} />)
    expect(container.querySelector('polyline')).toBeNull()
  })
  it('draws a polyline for a series', () => {
    const { container } = render(<Sparkline data={[1, 2, 3]} />)
    expect(container.querySelector('polyline')).not.toBeNull()
  })
})
