import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MinerCard, EnergyFlow, CoverageRing, Sparkline, Tabs, InverterDetails } from './App.jsx'

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

  it('shows approximate energy consumed today when provided, inline', () => {
    const { container } = render(
      <MinerCard miner={miner()} pending={false} onStart={noop} onStop={noop} onSetPower={noop}
        energyTodayKwh={12.34} />)
    expect(container.textContent).toContain('≈ 12.3 kWh today')  // fmt(12.34, 1)
  })

  it('omits the energy figure when it is not available', () => {
    const { container } = render(
      <MinerCard miner={miner()} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(container.textContent).not.toMatch(/kWh today/)
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

  it('offline: Start stays ENABLED (a stopped Braiins miner reads offline but is startable)', () => {
    const m = miner({ reachable: false, running: false, state: 'OFFLINE', error: 'GraphQL error: Service unavailable' })
    render(<MinerCard miner={m} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(screen.getByText('GraphQL error: Service unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start' })).toBeEnabled()   // ← the fix: can recover it
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled()   // nothing running to stop
    expect(screen.getByRole('spinbutton')).toBeDisabled()                 // can't set power while unreachable
  })

  it('cleanly off (no error): shows "Off" and no error line, Start enabled', () => {
    const m = miner({ reachable: false, running: false, state: 'OFFLINE',
      hashrateThs: null, powerDrawW: null, fans: [], error: null })
    const { container } = render(
      <MinerCard miner={m} pending={false} onStart={noop} onStop={noop} onSetPower={noop} />)
    expect(container.textContent).toContain('Off')
    expect(container.querySelector('.plug-error')).toBeNull()          // no scary message when just off
    expect(screen.getByRole('button', { name: 'Start' })).toBeEnabled()
  })

  it('offline Start button invokes onStart', () => {
    const onStart = vi.fn()
    const m = miner({ reachable: false, running: false, state: 'OFFLINE', error: 'x' })
    render(<MinerCard miner={m} pending={false} onStart={onStart} onStop={noop} onSetPower={noop} />)
    fireEvent.click(screen.getByRole('button', { name: 'Start' }))
    expect(onStart).toHaveBeenCalledOnce()
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
  // house prop = measured whole-home consumption (kW) from Solar Analytics.
  const house = (over) => ({ kw: 1.5, metered: true, ts: 't', ageSec: 3, ...over })

  it('shows measured house consumption and the surplus margin', () => {
    // solar 3, house 1.5 ⇒ surplus +1.5
    const { container } = render(<EnergyFlow solar={3} house={house({ kw: 1.5 })} spark={[]} />)
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument() // no editable input
    expect(container.textContent).toMatch(/surplus margin/i)         // labelled surplus margin
    expect(container.textContent).toContain('+1.50')                 // solar − house = +1.5 kW
    expect(container.textContent).toContain('1.50')                  // house consumption node
    expect(container.textContent).toMatch(/exporting/i)              // grid leg direction
  })

  it('shows the surplus figure once (no duplication)', () => {
    const { container } = render(<EnergyFlow solar={3} house={house({ kw: 1.5 })} spark={[]} />)
    const t = container.textContent
    expect(t).not.toMatch(/consumption metered/i)                    // no badge (lives in header pill)
    expect(t.match(/\+1\.50/g) || []).toHaveLength(1)                // signed surplus appears once
  })

  it('shows importing as drawing from the grid', () => {
    // solar 0.2, house 2.0 ⇒ surplus −1.8
    const { container } = render(<EnergyFlow solar={0.2} house={house({ kw: 2.0 })} spark={[]} />)
    expect(container.textContent).toMatch(/drawing from the grid/i)  // qualitative caption
    expect(container.textContent).toMatch(/importing/i)              // grid leg direction
    expect(container.textContent).toContain('-1.80')                 // surplus margin (deficit)
  })

  it('unmetered shows waiting-for-meter, no input, and unavailable margin', () => {
    const { container } = render(<EnergyFlow solar={3} house={{ kw: null, metered: false }} spark={[]} />)
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
    expect(container.textContent).toMatch(/waiting for/i)            // Solar Analytics offline message
    expect(container.textContent).toMatch(/unavailable/i)            // margin can't be computed
  })
})

describe('Tabs', () => {
  it('renders both tabs, marks the active one, and fires onChange on click', () => {
    const onChange = vi.fn()
    render(<Tabs active="overview" onChange={onChange} />)
    const overview = screen.getByRole('tab', { name: 'Overview' })
    const advanced = screen.getByRole('tab', { name: 'Advanced' })
    expect(overview).toHaveAttribute('aria-selected', 'true')
    expect(advanced).toHaveAttribute('aria-selected', 'false')
    fireEvent.click(advanced)
    expect(onChange).toHaveBeenCalledWith('advanced')
  })
})

describe('InverterDetails', () => {
  const metrics = [
    { key: 'A', label: 'Active Power', category: 'power', value: '1.2', unit: 'kW' },
    { key: 'B', label: 'Grid Voltage', category: 'grid', value: '240', unit: 'V' },
    // A promoted key (shown elsewhere) must NOT appear in the detail sections.
    { key: 'I18N_COMMON_TOTAL_ACTIVE_POWER', label: 'Total Active Power', category: 'power', value: '1.2', unit: 'kW' },
  ]

  it('renders a section per populated category and hides promoted keys', () => {
    render(<InverterDetails metrics={metrics} strings={[]} />)
    expect(screen.getByRole('heading', { name: 'Power' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Grid & AC Output' })).toBeInTheDocument()
    expect(screen.getByText('Active Power')).toBeInTheDocument()
    expect(screen.queryByText('Total Active Power')).not.toBeInTheDocument() // promoted → hidden
    expect(screen.queryByRole('heading', { name: 'Energy' })).not.toBeInTheDocument() // empty → skipped
  })

  it('renders MPPT string cards under DC / PV Array', () => {
    render(<InverterDetails metrics={[]} strings={[{ name: 'MPPT1', voltage: 600, current: 8, powerKw: 4.8 }]} />)
    expect(screen.getByRole('heading', { name: 'DC / PV Array' })).toBeInTheDocument()
    expect(screen.getByText('MPPT1')).toBeInTheDocument()
  })

  it('shows a placeholder when there is nothing to show', () => {
    render(<InverterDetails metrics={[]} strings={[]} />)
    expect(screen.getByText(/No inverter detail/i)).toBeInTheDocument()
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
