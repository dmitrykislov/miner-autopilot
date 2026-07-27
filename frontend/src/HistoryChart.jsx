import React, { useEffect, useMemo, useRef, useState } from 'react'
import { scaleTime, scaleLinear } from 'd3-scale'
import { line } from 'd3-shape'
import { max, bisector } from 'd3-array'
import { timeFormat } from 'd3-time-format'

// Selectable history windows (hours). The backend clamps to the retention window and downsamples.
const WINDOWS = [
  { label: '24h', hours: 24 },
  { label: '7d', hours: 168 },
  { label: '30d', hours: 720 },
]

// The three plotted series. Values are watts; the axis/tooltip render them as kW.
const SERIES = [
  { key: 'solarW', label: 'Solar', color: 'var(--solar)' },
  { key: 'consumptionW', label: 'Home', color: 'var(--house)' },
  { key: 'minerPowerW', label: 'Miner', color: 'var(--miner)' },
]

const M = { top: 14, right: 16, bottom: 26, left: 46 }
const H = 300
const FALLBACK_W = 960 // used when the container hasn't been measured (e.g. under jsdom in tests)

const kw = (w) => (w == null ? '—' : `${(w / 1000).toFixed(2)} kW`)
const bisectDate = bisector((d) => d.date).center
const fmtAxisTime = (span) => timeFormat(span > 3 * 24 * 3600e3 ? '%b %d' : span > 24 * 3600e3 ? '%a %H:%M' : '%H:%M')
const fmtFull = timeFormat('%b %d, %H:%M')

/**
 * Interactive D3 history chart: solar generation, home consumption and miner power over time, with
 * autopilot power-change markers. Hover the plot for a readout at that instant; hover a marker for
 * the change details. `authFetch` is the token-aware fetch from the dashboard.
 */
export default function HistoryChart({ authFetch }) {
  const [hours, setHours] = useState(24)
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [width, setWidth] = useState(FALLBACK_W)
  const [hover, setHover] = useState(null)      // { sample, x }
  const [hoverEvent, setHoverEvent] = useState(null)
  const wrapRef = useRef(null)
  const fetchRef = useRef(authFetch)
  fetchRef.current = authFetch

  // Measure the container so the chart is responsive (falls back to a fixed width under jsdom).
  useEffect(() => {
    const el = wrapRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect?.width
      if (w && w > 0) setWidth(w)
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  // Load (and periodically refresh) the selected window.
  useEffect(() => {
    let cancelled = false
    const load = () => {
      fetchRef.current(`/api/history?hours=${hours}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((d) => { if (!cancelled && d) setData(d) })
        .catch(() => {})
        .finally(() => { if (!cancelled) setLoading(false) })
    }
    setLoading(true)
    load()
    const t = setInterval(load, 60000)
    return () => { cancelled = true; clearInterval(t) }
  }, [hours])

  const samples = useMemo(
    () => (data?.samples || []).map((s) => ({ ...s, date: new Date(s.at) })),
    [data],
  )
  const events = useMemo(
    () => (data?.events || []).map((e) => ({ ...e, date: new Date(e.at) })),
    [data],
  )

  const w = Math.max(width, 320)
  const plotW = w - M.left - M.right
  const plotH = H - M.top - M.bottom

  const { x, y, maxW } = useMemo(() => {
    const from = data ? new Date(data.from) : new Date(Date.now() - hours * 3600e3)
    const to = data ? new Date(data.to) : new Date()
    const peak = max(samples, (s) => Math.max(s.solarW || 0, s.consumptionW || 0, s.minerPowerW || 0)) || 1000
    const x = scaleTime().domain([from, to]).range([M.left, M.left + plotW])
    const y = scaleLinear().domain([0, peak * 1.1]).range([M.top + plotH, M.top]).nice()
    return { x, y, maxW: peak }
  }, [data, samples, plotW, plotH, hours])

  const paths = useMemo(() => SERIES.map((s) => ({
    ...s,
    d: line()
      .defined((p) => p[s.key] != null)
      .x((p) => x(p.date))
      .y((p) => y(p[s.key]))(samples),
  })), [samples, x, y])

  const onMove = (e) => {
    if (!samples.length) return
    const rect = e.currentTarget.getBoundingClientRect()
    const px = e.clientX - rect.left
    const date = x.invert(px)
    const i = bisectDate(samples, date)
    const sample = samples[Math.max(0, Math.min(samples.length - 1, i))]
    if (sample) setHover({ sample, x: x(sample.date) })
  }

  const surplus = (s) => (s.solarW != null && s.consumptionW != null ? s.solarW - s.consumptionW : null)
  const xTicks = x.ticks(Math.max(2, Math.floor(plotW / 110)))
  const yTicks = y.ticks(5)
  const spanMs = x.domain()[1] - x.domain()[0]
  const tickFmt = fmtAxisTime(spanMs)

  return (
    <section className="section history">
      <div className="section-head">
        <h2>History</h2>
        <div className="history-windows" role="tablist" aria-label="History window">
          {WINDOWS.map((wnd) => (
            <button key={wnd.hours} type="button" role="tab" aria-selected={hours === wnd.hours}
              className={`hbtn ${hours === wnd.hours ? 'active' : ''}`}
              onClick={() => setHours(wnd.hours)}>{wnd.label}</button>
          ))}
        </div>
      </div>

      <div className="history-legend">
        {SERIES.map((s) => (
          <span className="hleg" key={s.key}>
            <span className="hleg-dot" style={{ background: s.color }} /> {s.label}
          </span>
        ))}
        <span className="hleg hleg-event"><span className="hleg-mark" /> Power change</span>
      </div>

      <div className="history-plot" ref={wrapRef}>
        {samples.length === 0 ? (
          <div className="history-empty">{loading ? 'Loading…' : 'Collecting data — the chart fills in as samples are recorded.'}</div>
        ) : (
          <svg width={w} height={H} role="img" aria-label="Power history chart" className="history-svg">
            {/* y grid + labels */}
            {yTicks.map((t) => (
              <g key={`y${t}`} className="grid">
                <line x1={M.left} x2={M.left + plotW} y1={y(t)} y2={y(t)} />
                <text x={M.left - 8} y={y(t)} dy="0.32em" textAnchor="end" className="axis-label">
                  {(t / 1000).toFixed(t % 1000 === 0 ? 0 : 1)}
                </text>
              </g>
            ))}
            <text className="axis-unit" x={M.left - 8} y={M.top - 4} textAnchor="end">kW</text>

            {/* x labels */}
            {xTicks.map((t, i) => (
              <text key={`x${i}`} x={x(t)} y={H - 8} textAnchor="middle" className="axis-label">{tickFmt(t)}</text>
            ))}

            {/* power-change markers */}
            {events.map((ev, i) => (
              <g key={`ev${i}`} className="hevent"
                onMouseEnter={() => setHoverEvent(ev)} onMouseLeave={() => setHoverEvent(null)}>
                <line x1={x(ev.date)} x2={x(ev.date)} y1={M.top} y2={M.top + plotH} className="hevent-line" />
                <circle cx={x(ev.date)} cy={M.top} r="5" className={`hevent-dot ev-${(ev.action || '').toLowerCase()}`}
                  data-testid="history-event" />
              </g>
            ))}

            {/* series */}
            {paths.map((p) => (
              <path key={p.key} d={p.d || ''} fill="none" stroke={p.color} strokeWidth="1.8"
                className="hline" data-testid={`history-line-${p.key}`} />
            ))}

            {/* hover crosshair + points */}
            {hover && (
              <g className="hcross">
                <line x1={hover.x} x2={hover.x} y1={M.top} y2={M.top + plotH} />
                {SERIES.map((s) => hover.sample[s.key] != null && (
                  <circle key={s.key} cx={hover.x} cy={y(hover.sample[s.key])} r="3.2" fill={s.color} className={`hc-${s.key}`} />
                ))}
              </g>
            )}

            {/* capture overlay */}
            <rect x={M.left} y={M.top} width={plotW} height={plotH} fill="transparent"
              onMouseMove={onMove} onMouseLeave={() => setHover(null)} data-testid="history-overlay" />
          </svg>
        )}

        {hover && !hoverEvent && (
          <div className="history-tip" data-testid="history-tooltip"
            style={{ left: Math.min(Math.max(hover.x, M.left), w - 150) }}>
            <div className="tip-time">{fmtFull(hover.sample.date)}</div>
            <div className="tip-row"><span className="tip-dot" style={{ background: 'var(--solar)' }} />Solar <b>{kw(hover.sample.solarW)}</b></div>
            <div className="tip-row"><span className="tip-dot" style={{ background: 'var(--house)' }} />Home <b>{kw(hover.sample.consumptionW)}</b></div>
            <div className="tip-row"><span className="tip-dot" style={{ background: 'var(--accent)' }} />Miner <b>{hover.sample.minerPowerW == null ? 'off' : `${hover.sample.minerPowerW} W`}</b></div>
            {surplus(hover.sample) != null && (
              <div className="tip-row surplus">Surplus <b>{surplus(hover.sample) >= 0 ? '+' : ''}{kw(surplus(hover.sample))}</b></div>
            )}
          </div>
        )}

        {hoverEvent && (
          <div className="history-tip event" data-testid="history-event-tooltip"
            style={{ left: Math.min(Math.max(x(hoverEvent.date), M.left), w - 170) }}>
            <div className="tip-time">{fmtFull(hoverEvent.date)}</div>
            <div className="tip-action"><b>{hoverEvent.action}</b> {hoverEvent.fromW == null ? 'off' : `${hoverEvent.fromW} W`} → {hoverEvent.toW == null ? 'off' : `${hoverEvent.toW} W`}</div>
            {hoverEvent.reason && <div className="tip-reason">{hoverEvent.reason}</div>}
          </div>
        )}
      </div>
    </section>
  )
}
