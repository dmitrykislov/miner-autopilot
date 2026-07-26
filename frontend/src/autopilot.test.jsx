import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AutopilotCard } from './App.jsx'

const noop = () => {}

function status(over = {}) {
  return {
    enabled: true,
    evaluatedAt: '2026-07-27T00:00:00Z',
    lastDecision: 'surplus 1500W ≥ 1000W → +1000W to 1800W',
    lastChangeAt: '2026-07-27T00:00:00Z',
    lastChange: { at: '2026-07-27T00:00:00Z', action: 'STEP_UP', fromPowerW: 800, toPowerW: 1800, detail: 'surplus 1500W' },
    ...over,
  }
}

describe('AutopilotCard', () => {
  it('shows On + last decision and the last change details', () => {
    const { container } = render(<AutopilotCard autopilot={status()} pending={false} onToggle={noop} />)
    const t = container.textContent
    expect(t).toContain('On')
    expect(t).toContain('surplus 1500W ≥ 1000W')     // last decision
    expect(t).toContain('STEP_UP')                    // last change action
    expect(t).toContain('800 W → 1800 W')             // from → to
    expect(screen.getByText('Disable autopilot')).toBeInTheDocument() // enabled → offer disable
  })

  it('shows Off and offers Enable when disabled', () => {
    render(<AutopilotCard autopilot={status({ enabled: false })} pending={false} onToggle={noop} />)
    expect(screen.getByText('Enable autopilot')).toBeInTheDocument()
  })

  it('renders "off" for a null power target (miner turned off) and START from off', () => {
    const s = status({ lastChange: { at: '2026-07-27T00:00:00Z', action: 'START', fromPowerW: null, toPowerW: 800, detail: 'start at min' } })
    const { container } = render(<AutopilotCard autopilot={s} pending={false} onToggle={noop} />)
    expect(container.textContent).toContain('off → 800 W')
  })

  it('handles the no-change-yet case', () => {
    render(<AutopilotCard autopilot={status({ lastChange: null, lastChangeAt: null })} pending={false} onToggle={noop} />)
    expect(screen.getByText('No changes made yet')).toBeInTheDocument()
  })

  it('calls onToggle when the button is clicked', () => {
    const onToggle = vi.fn()
    render(<AutopilotCard autopilot={status()} pending={false} onToggle={onToggle} />)
    fireEvent.click(screen.getByText('Disable autopilot'))
    expect(onToggle).toHaveBeenCalledOnce()
  })

  it('disables the button while a toggle is pending', () => {
    render(<AutopilotCard autopilot={status()} pending={true} onToggle={noop} />)
    expect(screen.getByRole('button')).toBeDisabled()
  })

  it('tolerates a null autopilot status (pre-connect)', () => {
    render(<AutopilotCard autopilot={null} pending={false} onToggle={noop} />)
    expect(screen.getByText('Enable autopilot')).toBeInTheDocument()  // treats unknown as off
  })
})
