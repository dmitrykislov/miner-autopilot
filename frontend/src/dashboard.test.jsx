import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
import App from './App.jsx'
import { setToken } from './auth.js'

// The dashboard opens several EventSources; capture them so tests can push frames to a chosen one.
// Each stream now mints an SSE ticket first, so the connection appears after a microtask — push()
// waits for its source to exist before emitting.
let sources
class FakeEventSource {
  constructor(url) { this.url = url; sources.push(this) }
  close() {}
  emit(obj) { this.onmessage?.({ data: JSON.stringify(obj) }) }
}
const srcFor = (frag) => sources.find((s) => s.url.includes(frag))
const push = async (frag, obj) => {
  const s = await waitFor(() => { const x = srcFor(frag); if (!x) throw new Error(`no source for ${frag}`); return x })
  await act(async () => { s.emit(obj) })
}

beforeEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
  sources = []
  global.EventSource = FakeEventSource
  global.fetch = vi.fn((url) => {
    const u = String(url)
    // Streams ask for a short-lived SSE ticket before connecting.
    if (u.includes('/api/auth/sse-ticket')) return Promise.resolve({ ok: true, status: 200, json: async () => ({ ticket: 'sse.tkt' }) })
    // /api/power/latest stays empty so the feed only advances via the stream; everything else is ok.
    if (u.includes('/api/power/latest')) return Promise.resolve({ ok: false, status: 204, json: async () => null })
    return Promise.resolve({ ok: true, status: 200, json: async () => ({}) })
  })
  setToken('tok')
})

const powerSnapshot = { solarW: 3000, solarAt: '2026-07-27T02:00:00Z',
                        consumptionW: 1500, consumptionAt: '2026-07-27T02:00:00Z' }

describe('source-agnostic dashboard', () => {
  it('shows "Connecting…" until the power feed produces a snapshot', async () => {
    render(<App />)
    await screen.findByText('Log out')                       // dashboard mounted
    expect(screen.getByText(/Connecting/)).toBeInTheDocument()
    expect(screen.queryByText('Live Power Flow')).toBeNull()
  })

  it('renders the live flow from the power feed with NO Sungrow snapshot', async () => {
    render(<App />)
    await screen.findByText('Log out')
    await push('/api/power/stream', powerSnapshot)

    // The core overview renders purely from the source-agnostic feed…
    expect(await screen.findByText('Live Power Flow')).toBeInTheDocument()
    expect(screen.getByText('3.00')).toBeInTheDocument()          // solar 3000 W → 3.00 kW (unique)
    expect(screen.queryByText(/waiting for meter/i)).toBeNull()   // house is metered → consumption wired

    // …while the Sungrow-only bits stay hidden without an inverter snapshot.
    expect(screen.queryByRole('tab', { name: 'Advanced' })).toBeNull()
    expect(screen.queryByText('Lifetime')).toBeNull()             // a yield KPI
    expect(screen.queryByText(/^SN /)).toBeNull()                 // serial line
  })

  it('layers the Sungrow detail (Advanced tab + KPIs + model) in when an inverter snapshot arrives', async () => {
    render(<App />)
    await screen.findByText('Log out')
    await push('/api/power/stream', powerSnapshot)
    await screen.findByText('Live Power Flow')

    await push('/api/inverter/stream', {
      online: true, deviceModel: 'SG10RS', serialNumber: 'SN-123',
      highlights: { totalYieldKwh: 1234 }, metrics: [], strings: [],
    })

    expect(await screen.findByRole('tab', { name: 'Advanced' })).toBeInTheDocument()
    expect(screen.getByText('Lifetime')).toBeInTheDocument()   // Sungrow yield KPI
    expect(screen.getByText('SG10RS')).toBeInTheDocument()     // header model (own text node)
    expect(screen.getByText('SN SN-123')).toBeInTheDocument()  // serial line
  })
})
