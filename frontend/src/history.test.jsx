import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import HistoryChart from './HistoryChart.jsx'

const T0 = Date.parse('2026-07-27T06:00:00Z')

function payload(over = {}) {
  const samples = []
  for (let i = 0; i < 30; i++) {
    // Miner off before i=8 (pre-mining) and again in [18,22) (a mid-window suspension) → the miner
    // line has an interior gap (two drawn segments), which is what the line-break test checks.
    const off = i < 8 || (i >= 18 && i < 22)
    samples.push({
      at: new Date(T0 + i * 60_000).toISOString(),
      solarW: 3000 + i * 20,                 // always above the daylight threshold
      consumptionW: 1500,
      minerPowerW: off ? null : 2400,
      minerDrawW: off ? null : 2350,
      minerState: off ? (i < 8 ? 'STOPPED' : 'SUSPENDED') : 'MINING',
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

function fakeFetch(data) {
  return vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve(
    typeof data === 'function' ? data() : data) }))
}

/** Parse the {from,to,span} of the most recent /api/history request. */
function lastRange(mock) {
  const url = mock.mock.calls.at(-1)[0]
  const q = new URL(url, 'http://x').searchParams
  const from = Number(q.get('from')), to = Number(q.get('to'))
  return { from, to, span: to - from, url }
}

describe('HistoryChart', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('defaults to Today and requests an explicit from/to range (not ?hours)', async () => {
    const authFetch = fakeFetch(payload())
    render(<HistoryChart authFetch={authFetch} />)
    await screen.findByTestId('history-line-solarW')

    const { url } = lastRange(authFetch)
    expect(url).toMatch(/\/api\/history\?from=\d+&to=\d+$/)
    expect(url).not.toContain('hours=')
    expect(screen.getByTestId('history-range').textContent).toContain('Today')
  })

  it('draws the three series', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    await screen.findByTestId('history-line-solarW')
    expect(screen.getByTestId('history-line-consumptionW')).toBeInTheDocument()
    expect(screen.getByTestId('history-line-minerPowerW')).toBeInTheDocument()
    expect(screen.getByTestId('history-line-solarW').getAttribute('d')).toBeTruthy()
  })

  it('breaks the miner line where the miner was off (null segment)', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    const miner = await screen.findByTestId('history-line-minerPowerW')
    // d3 line().defined(...) restarts the path after the gap → a second "M" command.
    expect((miner.getAttribute('d').match(/M/g) || []).length).toBeGreaterThan(1)
  })

  it('reveals power-change details on marker hover', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    const marker = await screen.findByTestId('history-event')
    fireEvent.mouseEnter(marker.parentNode)
    const tip = await screen.findByTestId('history-event-tooltip')
    expect(within(tip).getByText(/START/)).toBeInTheDocument()
    expect(tip.textContent).toContain('off → 1200 W')
    expect(tip.textContent).toContain('start at floor')
  })

  it('selecting 1h requests a one-hour range', async () => {
    const authFetch = fakeFetch(payload())
    render(<HistoryChart authFetch={authFetch} />)
    await screen.findByTestId('history-line-solarW')

    fireEvent.click(screen.getByRole('tab', { name: '1h' }))
    await waitFor(() => expect(lastRange(authFetch).span).toBeCloseTo(3600e3, -3)) // ~1h
  })

  it('steps back in time and returns to live with "Now"', async () => {
    const authFetch = fakeFetch(payload())
    render(<HistoryChart authFetch={authFetch} />)
    await screen.findByTestId('history-line-solarW')

    // A fixed span makes the step deterministic.
    fireEvent.click(screen.getByRole('tab', { name: '4h' }))
    await waitFor(() => expect(lastRange(authFetch).span).toBeCloseTo(4 * 3600e3, -3))
    const liveTo = lastRange(authFetch).to

    // "Later" is disabled while live; stepping earlier moves the window one whole span back.
    expect(screen.getByLabelText('Later')).toBeDisabled()
    fireEvent.click(screen.getByLabelText('Earlier'))
    await waitFor(() => expect(lastRange(authFetch).to).toBeLessThan(liveTo))
    expect(lastRange(authFetch).span).toBeCloseTo(4 * 3600e3, -3) // same span, shifted back
    expect(screen.getByLabelText('Later')).not.toBeDisabled()      // can now go forward

    // "Now" jumps back to the live edge.
    fireEvent.click(screen.getByText('Now'))
    await waitFor(() => expect(lastRange(authFetch).to).toBeGreaterThanOrEqual(liveTo))
  })

  it('shows a legend for all series plus the power-change marker', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    expect(screen.getByText('Solar')).toBeInTheDocument()
    expect(screen.getByText('Home')).toBeInTheDocument()
    expect(screen.getByText('Miner')).toBeInTheDocument()
    expect(screen.getByText('Power change')).toBeInTheDocument()
    await screen.findByTestId('history-line-solarW')
  })

  it('shows an empty-state message when the window has no data', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload({ samples: [], events: [] }))} />)
    await waitFor(() => expect(screen.getByText(/No solar recorded yet today|No data recorded/)).toBeInTheDocument())
  })
})
