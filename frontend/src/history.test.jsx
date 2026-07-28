import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import HistoryChart from './HistoryChart.jsx'

const T0 = Date.parse('2026-07-27T06:00:00Z')

function payload(over = {}) {
  const samples = []
  for (let i = 0; i < 30; i++) {
    // Miner off before i=8 (pre-mining) and again in [18,22) (a mid-window suspension). When off the
    // miner line is held at zero (not gapped) — the zero-baseline test checks that.
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
    // all three lines must actually have a drawn path, not just be present in the DOM
    for (const k of ['solarW', 'consumptionW', 'minerPowerW']) {
      expect(screen.getByTestId(`history-line-${k}`).getAttribute('d')).toBeTruthy()
    }
  })

  it('holds the miner line at zero (not a gap) while the miner is off', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    const miner = await screen.findByTestId('history-line-minerPowerW')
    const solar = await screen.findByTestId('history-line-solarW')
    const md = miner.getAttribute('d')
    // One continuous path — no defined()-induced break where the miner was off.
    expect((md.match(/M/g) || []).length).toBe(1)
    // Off samples sit on the zero baseline, so the miner line reaches lower on screen (larger SVG y)
    // than the always-positive solar line's lowest point.
    const maxY = (d) => Math.max(...[...d.matchAll(/[ML]([\d.]+),([\d.]+)/g)].map((m) => Number(m[2])))
    expect(maxY(md)).toBeGreaterThan(maxY(solar.getAttribute('d')))
  })

  it('renders the sign-coloured solar↔home difference fill', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    const surplus = await screen.findByTestId('history-surplus-fill')
    const deficit = screen.getByTestId('history-deficit-fill')
    // both are the same band path, clipped to above/below the home line respectively
    expect(surplus.getAttribute('d')).toBeTruthy()
    expect(deficit.getAttribute('d')).toBeTruthy()
    expect(surplus.getAttribute('clip-path')).toContain('surplus')
    expect(deficit.getAttribute('clip-path')).toContain('deficit')
  })

  it('draws the deficit (red) fill where home consumption exceeds solar', async () => {
    // A window that starts in deficit (home 4 kW > solar 2 kW) and crosses into surplus.
    const samples = Array.from({ length: 20 }, (_, i) => ({
      at: new Date(T0 + i * 60_000).toISOString(),
      solarW: 2000 + i * 200,        // rises 2000 → ~5800
      consumptionW: 4000,            // flat 4 kW → deficit while solar < 4000 (roughly i < 10)
      minerPowerW: null, minerState: 'STOPPED',
    }))
    render(<HistoryChart authFetch={fakeFetch(payload({ samples, events: [] }))} />)
    const deficit = await screen.findByTestId('history-deficit-fill')
    const surplus = screen.getByTestId('history-surplus-fill')
    // Same band, clipped below/above the Home line respectively → the deficit stretch shows red,
    // the later surplus stretch green. Both must be drawn and clipped to their own region.
    expect(deficit.getAttribute('d')).toBeTruthy()
    expect(deficit.getAttribute('clip-path')).toContain('deficit')
    expect(surplus.getAttribute('clip-path')).toContain('surplus')
  })

  it('shows a plot readout on hover (solar / home / surplus)', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload())} />)
    const overlay = await screen.findByTestId('history-overlay')
    fireEvent.mouseMove(overlay, { clientX: 400 })
    const tip = await screen.findByTestId('history-tooltip')
    expect(tip.textContent).toMatch(/Solar/)
    expect(tip.textContent).toMatch(/Home/)
    expect(tip.textContent).toMatch(/Surplus/)
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
    expect(screen.getByText('Surplus')).toBeInTheDocument()
    expect(screen.getByText('Deficit')).toBeInTheDocument()
    expect(screen.getByText('Power change')).toBeInTheDocument()
    await screen.findByTestId('history-line-solarW')
  })

  it('shows an empty-state message when the window has no data', async () => {
    render(<HistoryChart authFetch={fakeFetch(payload({ samples: [], events: [] }))} />)
    await waitFor(() => expect(screen.getByText(/No solar recorded yet today|No data recorded/)).toBeInTheDocument())
  })
})
