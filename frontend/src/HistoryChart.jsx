import React, { useEffect, useMemo, useRef, useState } from 'react'
import { scaleTime, scaleLinear } from 'd3-scale'
import { line, area } from 'd3-shape'
import { max, bisector } from 'd3-array'
import { timeFormat } from 'd3-time-format'
import { historyWindow, daylightExtent, pointerToSvgX } from './logic.js'

// Selectable windows. Fixed spans, plus "Today" (the solar-active part of the current day).
const WINDOWS = [
  { key: '1h', label: '1h', hours: 1 },
  { key: '4h', label: '4h', hours: 4 },
  { key: '8h', label: '8h', hours: 8 },
  { key: '12h', label: '12h', hours: 12 },
  { key: 'today', label: 'Today', today: true },
]
const DEFAULT_WIN = 4 // Today

// The three plotted series. Values are watts; the axis/tooltip render them as kW.
const SERIES = [
  { key: 'solarW', label: 'Solar', color: 'var(--solar)' },
  { key: 'consumptionW', label: 'Home', color: 'var(--house)' },
  { key: 'minerPowerW', label: 'Miner', color: 'var(--miner)' },
]

const M = { top: 14, right: 16, bottom: 26, left: 46 }
const H = 300
const FALLBACK_W = 960 // used before the container is measured (e.g. under jsdom in tests)

const kw = (w) => (w == null ? '—' : `${(w / 1000).toFixed(2)} kW`)
const bisectDate = bisector((d) => d.date).center
const fmtTick = timeFormat('%H:%M')
const fmtFull = timeFormat('%b %d, %H:%M')
const fmtDay = timeFormat('%a, %b %d')

/**
 * Interactive D3 history chart. Pick a window (1h/4h/8h/12h or Today) and step back/forward in time
 * with the arrows. "Today" zooms to the solar-active part of the day (first light → now/last light).
 * Hover the plot for a readout at that instant; hover a marker for the autopilot change details.
 * `authFetch` is the token-aware fetch from the dashboard.
 */
export default function HistoryChart({ authFetch }) {
  const [winIdx, setWinIdx] = useState(DEFAULT_WIN)
  const [offset, setOffset] = useState(0) // 0 = latest/live; higher = further back
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [width, setWidth] = useState(FALLBACK_W)
  const [hover, setHover] = useState(null)
  const [hoverEvent, setHoverEvent] = useState(null)
  const wrapRef = useRef(null)
  const fetchRef = useRef(authFetch)
  fetchRef.current = authFetch

  const win = WINDOWS[winIdx]
  const live = offset === 0

  // Responsive width (falls back to a fixed width under jsdom, where ResizeObserver is absent).
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

  // Load the current window; only auto-refresh while live (offset 0), so a historical view is frozen.
  useEffect(() => {
    let cancelled = false
    const load = () => {
      const { fromMs, toMs } = historyWindow(win, offset, Date.now())
      fetchRef.current(`/api/history?from=${Math.round(fromMs)}&to=${Math.round(toMs)}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((d) => { if (!cancelled && d) setData(d) })
        .catch(() => {})
        .finally(() => { if (!cancelled) setLoading(false) })
    }
    setLoading(true)
    load()
    const t = live ? setInterval(load, 60000) : null
    return () => { cancelled = true; if (t) clearInterval(t) }
  }, [winIdx, offset, win, live])

  const samples = useMemo(
    () => (data?.samples || []).map((s) => ({ ...s, date: new Date(s.at) })),
    [data],
  )
  const events = useMemo(
    () => (data?.events || []).map((e) => ({ ...e, date: new Date(e.at) })),
    [data],
  )

  const retentionDays = data?.retentionDays || 31
  const maxOffset = win.today
    ? retentionDays - 1
    : Math.max(0, Math.floor((retentionDays * 24) / win.hours) - 1)

  const w = Math.max(width, 320)
  const plotW = w - M.left - M.right
  const plotH = H - M.top - M.bottom

  const { x, y } = useMemo(() => {
    const fetchedFrom = data ? new Date(data.from) : new Date(Date.now() - (win.hours || 24) * 3600e3)
    const fetchedTo = data ? new Date(data.to) : new Date()
    // Today zooms to the solar-active span; otherwise show the whole fetched window.
    const light = win.today ? daylightExtent(data?.samples) : null
    const [d0, d1] = light ? [new Date(light[0]), new Date(light[1])] : [fetchedFrom, fetchedTo]
    const peak = max(samples, (s) => Math.max(s.solarW || 0, s.consumptionW || 0, s.minerPowerW || 0)) || 1000
    const x = scaleTime().domain([d0, d1]).range([M.left, M.left + plotW])
    const y = scaleLinear().domain([0, peak * 1.1]).range([M.top + plotH, M.top]).nice()
    return { x, y }
  }, [data, samples, plotW, plotH, win])

  const paths = useMemo(() => SERIES.map((s) => ({
    ...s,
    d: line().defined((p) => p[s.key] != null).x((p) => x(p.date)).y((p) => y(p[s.key]))(samples),
  })), [samples, x, y])

  // Sign-coloured fill of the Solar↔Home difference: green where solar ≥ home (surplus), red where
  // solar < home (deficit). The band between the two lines is drawn once, then clipped to the region
  // above the Home line (→ surplus only) and again to the region below it (→ deficit only). The clip
  // geometry splits at each crossing automatically, so no manual crossing interpolation is needed.
  const fills = useMemo(() => {
    const hasBoth = (p) => p.solarW != null && p.consumptionW != null
    const hasHouse = (p) => p.consumptionW != null
    const px = (p) => x(p.date)
    const top = M.top, bottom = M.top + plotH
    return {
      band: area().defined(hasBoth).x(px).y0((p) => y(p.consumptionW)).y1((p) => y(p.solarW))(samples),
      aboveHouse: area().defined(hasHouse).x(px).y0(() => top).y1((p) => y(p.consumptionW))(samples),
      belowHouse: area().defined(hasHouse).x(px).y0(() => bottom).y1((p) => y(p.consumptionW))(samples),
    }
  }, [samples, x, y, plotH])

  const onMove = (e) => {
    if (!samples.length) return
    const svg = e.currentTarget.ownerSVGElement
    if (!svg) return
    const r = svg.getBoundingClientRect()
    const svgX = pointerToSvgX(e.clientX, r.left, r.width, w)   // cursor → internal SVG x (scale-correct)
    const sample = samples[Math.max(0, Math.min(samples.length - 1, bisectDate(samples, x.invert(svgX))))]
    if (sample) setHover({ sample, x: x(sample.date) })
  }

  const surplus = (s) => (s.solarW != null && s.consumptionW != null ? s.solarW - s.consumptionW : null)
  const yTicks = y.ticks(5)
  const xTicks = x.ticks(Math.max(2, Math.floor(plotW / 90)))

  // Human label for the current window.
  const label = !data ? '' : win.today
    ? (live ? 'Today' : fmtDay(new Date(data.from)))
    : `${fmtDay(new Date(data.from))} · ${fmtTick(new Date(data.from))}–${fmtTick(new Date(data.to))}`

  return (
    <section className="section history">
      <div className="section-head">
        <h2>History</h2>
        <div className="history-windows" role="tablist" aria-label="History window">
          {WINDOWS.map((wnd, i) => (
            <button key={wnd.key} type="button" role="tab" aria-selected={i === winIdx}
              className={`hbtn ${i === winIdx ? 'active' : ''}`}
              onClick={() => { setWinIdx(i); setOffset(0) }}>{wnd.label}</button>
          ))}
        </div>
      </div>

      <div className="history-nav">
        <button type="button" className="hnav" aria-label="Earlier"
          disabled={offset >= maxOffset} onClick={() => setOffset((o) => Math.min(maxOffset, o + 1))}>‹</button>
        <span className="history-range" data-testid="history-range">
          {label}{live && <span className="live-dot" />}
        </span>
        <button type="button" className="hnav" aria-label="Later"
          disabled={live} onClick={() => setOffset((o) => Math.max(0, o - 1))}>›</button>
        {!live && <button type="button" className="hbtn hnow" onClick={() => setOffset(0)}>Now</button>}
      </div>

      <div className="history-legend">
        {SERIES.map((s) => (
          <span className="hleg" key={s.key}>
            <span className="hleg-dot" style={{ background: s.color }} /> {s.label}
          </span>
        ))}
        <span className="hleg"><span className="hleg-fill surplus" /> Surplus</span>
        <span className="hleg"><span className="hleg-fill deficit" /> Deficit</span>
        <span className="hleg hleg-event"><span className="hleg-mark" /> Power change</span>
      </div>

      <div className="history-plot" ref={wrapRef}>
        {samples.length === 0 ? (
          <div className="history-empty">
            {loading ? 'Loading…'
              : win.today && live ? 'No solar recorded yet today — the chart fills in as the sun comes up.'
              : 'No data recorded for this period.'}
          </div>
        ) : (
          <svg width={w} height={H} role="img" aria-label="Power history chart" className="history-svg">
            {yTicks.map((t) => (
              <g key={`y${t}`} className="grid">
                <line x1={M.left} x2={M.left + plotW} y1={y(t)} y2={y(t)} />
                <text x={M.left - 8} y={y(t)} dy="0.32em" textAnchor="end" className="axis-label">
                  {(t / 1000).toFixed(t % 1000 === 0 ? 0 : 1)}
                </text>
              </g>
            ))}
            <text className="axis-unit" x={M.left - 8} y={M.top - 4} textAnchor="end">kW</text>

            {xTicks.map((t, i) => (
              <text key={`x${i}`} x={x(t)} y={H - 8} textAnchor="middle" className="axis-label">{fmtTick(t)}</text>
            ))}

            {/* solar-vs-home difference, coloured by sign (surplus green / deficit red) */}
            <defs>
              <clipPath id="hist-clip-surplus"><path d={fills.aboveHouse || ''} /></clipPath>
              <clipPath id="hist-clip-deficit"><path d={fills.belowHouse || ''} /></clipPath>
            </defs>
            <path d={fills.band || ''} className="surplus-fill" clipPath="url(#hist-clip-surplus)"
              data-testid="history-surplus-fill" />
            <path d={fills.band || ''} className="deficit-fill" clipPath="url(#hist-clip-deficit)"
              data-testid="history-deficit-fill" />

            {events.map((ev, i) => (
              <g key={`ev${i}`} className="hevent"
                onMouseEnter={() => setHoverEvent(ev)} onMouseLeave={() => setHoverEvent(null)}>
                <line x1={x(ev.date)} x2={x(ev.date)} y1={M.top} y2={M.top + plotH} className="hevent-line" />
                <circle cx={x(ev.date)} cy={M.top} r="5" className={`hevent-dot ev-${(ev.action || '').toLowerCase()}`}
                  data-testid="history-event" />
              </g>
            ))}

            {paths.map((p) => (
              <path key={p.key} d={p.d || ''} fill="none" stroke={p.color} strokeWidth="1.8"
                className="hline" data-testid={`history-line-${p.key}`} />
            ))}

            {hover && (
              <g className="hcross">
                <line x1={hover.x} x2={hover.x} y1={M.top} y2={M.top + plotH} />
                {SERIES.map((s) => hover.sample[s.key] != null && (
                  <circle key={s.key} cx={hover.x} cy={y(hover.sample[s.key])} r="3.2" fill={s.color} />
                ))}
              </g>
            )}

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
            <div className="tip-row"><span className="tip-dot" style={{ background: 'var(--miner)' }} />Miner <b>{hover.sample.minerPowerW == null ? 'off' : `${hover.sample.minerPowerW} W`}</b></div>
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
