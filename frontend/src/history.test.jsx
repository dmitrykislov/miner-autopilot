import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import HistoryChart from './HistoryChart.jsx'

const T0 = Date.parse('2026-07-27T00:00:00Z')

function payload(over = {}) {
  const samples = []
  for (let i = 0; i < 30; i++) {
    samples.push({
      at: new Date(T0 + i * 60_000).toISOString(),
      solarW: 3000 + i * 20,
      consumptionW: 1500,
      minerPowerW: i < 10 ? null : 2400, // miner off for the first 10 samples → a line gap
      minerDrawW: i < 10 ? null : 2350,
      minerState: i < 10 ? 'STOPPED' : 'MINING',
    })
  }
  return {
    from: new Date(T0).toISOString(),
    to: new Date(T0 + 30 * 60_000).toISOString(),
    retentionDays: 31,
    intervalMs: 60000,
    samples,
    events: [
      { at: new Date(T0 + 10 * 60_000).toISOString(), action: 'START', fromW: null, toW: 1200, reason: 'surplus 1600W ≥ start → start at floor' },
    ],
    ...over,
  }
}

/** A token-aware fetch stub that returns the given payload (or a per-URL map). */
function fakeFetch(data) {
  return vi.fn((url) => Promise.resolve({ ok: true, json: () => Promise.resolve(
    typeof data === 'function' ? data(url) : data) }))
}

describe('HistoryChart', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('fetches the 24h window on mount and draws the three series', async () => {
    const authFetch = fakeFetch(payload())
    render(<HistoryChart authFetch={authFetch} />)

    expect(authFetch).toHaveBeenCalledWith('/api/history?hours=24')
    await waitFor(() => expect(screen.getByTestId('history-line-solarW')).toBeInTheDocument())
    expect(screen.getByTestId('history-line-consumptionW')).toBeInTheDocument()
    expect(screen.getByTestId('history-line-minerPowerW')).toBeInTheDocument()
    // each line has a non-empty path
    expect(screen.getByTestId('history-line-solarW').getAttribute('d')).toBeTruthy()
  })

  it('breaks the miner line where the miner was off (null segment)', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    const miner = await screen.findByTestId('history-line-minerPowerW')
    // d3 line().defined(...) inserts a gap (path restart) → the path has an "M" after the first move
    expect(miner.getAttribute('d')).toContain('M')
  })

  it('renders a marker per power-change event and reveals its details on hover', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    const marker = await screen.findByTestId('history-event')
    fireEvent.mouseEnter(marker.parentNode) // hovering the marker group
    const tip = await screen.findByTestId('history-event-tooltip')
    expect(within(tip).getByText(/START/)).toBeInTheDocument()
    expect(tip.textContent).toContain('off → 1200 W')
    expect(tip.textContent).toContain('start at floor')
  })

  it('switches window and refetches when a range button is clicked', async () => {
    const authFetch = fakeFetch(payload())
    render(<HistoryChart authFetch={authFetch} />)
    await screen.findByTestId('history-line-solarW')

    fireEvent.click(screen.getByRole('tab', { name: '7d' }))
    await waitFor(() => expect(authFetch).toHaveBeenCalledWith('/api/history?hours=168'))

    fireEvent.click(screen.getByRole('tab', { name: '30d' }))
    await waitFor(() => expect(authFetch).toHaveBeenCalledWith('/api/history?hours=720'))
  })

  it('shows a legend for all series plus the power-change marker', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    expect(screen.getByText('Solar')).toBeInTheDocument()
    expect(screen.getByText('Home')).toBeInTheDocument()
    expect(screen.getByText('Miner')).toBeInTheDocument()
    expect(screen.getByText('Power change')).toBeInTheDocument()
    await screen.findByTestId('history-line-solarW') // let the async load settle inside the test
  })

  it('shows an empty-state message when there are no samples yet', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload({ samples: [], events: [] }))} />)
    await waitFor(() =>
      expect(screen.getByText(/Collecting data/)).toBeInTheDocument())
  })
})
