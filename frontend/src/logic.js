// Pure, DOM-free UI logic — the numeric/formatting/state-mapping bits that are
// most edge-case-prone. Kept separate so they can be unit-tested directly.

/** Format a number to `d` decimals; anything non-numeric renders as "--". */
export const fmt = (n, d = 2) =>
  n === null || n === undefined || Number.isNaN(Number(n)) ? '--' : Number(n).toFixed(d)

/**
 * Solar-vs-house power flow from measured solar and measured house consumption
 * (Solar Analytics). net = surplus margin = solar − house; grid flow = |net|.
 * @returns {{net:number, exporting:boolean, coverage:number, gridFlow:number}}
 * net = surplus (kW, + exporting / − importing); exporting when net ≥ 0;
 * coverage = % of house met by solar (0..100, capped; 100 when house ≤ 0).
 */
export function flow(solarKw, houseKw) {
  const solar = Number.isFinite(solarKw) ? solarKw : 0
  const house = Number.isFinite(houseKw) ? houseKw : 0
  const net = +(solar - house).toFixed(3)
  const exporting = net >= 0
  const coverage = house > 0 ? Math.min(100, (solar / house) * 100) : 100
  return { net, exporting, coverage, gridFlow: Math.abs(net) }
}

/** Human uptime: null → null; ≥1h → "Xh Ym"; else "Xm". */
export function formatUptime(seconds) {
  if (seconds == null || Number.isNaN(Number(seconds))) return null
  const s = Math.max(0, Math.floor(seconds))
  return s >= 3600 ? `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m` : `${Math.floor(s / 60)}m`
}

/**
 * Full duration for the app-uptime footer: "--" for missing/negative;
 * ≥1d → "Xd Yh Zm"; ≥1h → "Xh Ym"; ≥1m → "Xm Ys"; else "Xs".
 */
export function formatDuration(seconds) {
  if (seconds == null || Number.isNaN(Number(seconds)) || seconds < 0) return '--'
  const s = Math.floor(seconds)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (d > 0) return `${d}d ${h}h ${m}m`
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${sec}s`
  return `${sec}s`
}

/**
 * The [fromMs, toMs] window for the history chart.
 * @param win    a fixed span `{ hours }` or a calendar day `{ today: true }`
 * @param offset how many windows/days back from the latest (0 = current/live; clamped to ≥ 0)
 * @param nowMs  reference "now" (epoch ms)
 * For a span: the window is `offset` whole spans back from now. For a day: local-midnight to the
 * next local midnight (DST-correct), with the end capped at `now` for today.
 */
export function historyWindow(win, offset, nowMs) {
  const off = Math.max(0, Math.floor(offset || 0))
  if (win.today) {
    const start = new Date(nowMs)
    start.setHours(0, 0, 0, 0)
    start.setDate(start.getDate() - off)      // midnight of the target local day
    const end = new Date(start)
    end.setDate(end.getDate() + 1)            // next local midnight (handles DST-length days)
    return { fromMs: start.getTime(), toMs: Math.min(nowMs, end.getTime()) }
  }
  const span = win.hours * 3600e3
  const toMs = nowMs - off * span
  return { fromMs: toMs - span, toMs }
}

/**
 * Convert a mouse `clientX` into the chart's internal SVG x-coordinate, correcting for the SVG's
 * on-screen scale (its rendered width vs its internal coordinate width). Using the SVG's own
 * bounding box — not the inner plot rect — keeps the crosshair exactly under the cursor regardless
 * of container width / browser zoom, and never forces the pointer outside the plot to reach an edge.
 */
export function pointerToSvgX(clientX, rectLeft, rectWidth, svgWidth) {
  if (!rectWidth) return 0
  return ((clientX - rectLeft) / rectWidth) * svgWidth
}

/**
 * The solar-active span within a set of samples: [firstMs, lastMs] of the samples whose solar
 * exceeds `thresholdW` — i.e. "from the moment solar appears to the moment it disappears". Returns
 * null when there is no (or only a single instant of) solar, so callers can fall back to the full
 * window. `at` may be epoch ms or an ISO string.
 */
export function daylightExtent(samples, thresholdW = 50) {
  let first = null, last = null
  for (const s of samples || []) {
    if (!s || s.solarW == null || !(s.solarW > thresholdW)) continue
    const t = typeof s.at === 'number' ? s.at : Date.parse(s.at)
    if (!Number.isFinite(t)) continue
    if (first === null) first = t
    last = t
  }
  return first !== null && last > first ? [first, last] : null
}

const MINER_LABELS = { MINING: 'Mining', SUSPENDED: 'Suspended', STOPPED: 'Stopped', OFFLINE: 'Off' }

/**
 * Derives the miner card's display state from a status object (or null).
 * @returns {{reachable:boolean, running:boolean, mining:boolean, statusText:string,
 *            dot:string, cardCls:string, upStr:(string|null)}}
 */
export function minerView(miner) {
  const reachable = !!miner?.reachable
  const running = !!miner?.running
  const state = miner?.state
  const mining = state === 'MINING'
  // A cleanly-off miner (stopped BOSMiner) reads as "Off"; only a genuine transport error shows
  // "Offline" (and the error line) — a stopped miner reports no error.
  const statusText = !miner ? 'Connecting…'
    : state === 'OFFLINE' ? (miner.error ? 'Offline' : 'Off')
    : (MINER_LABELS[state] || (reachable ? 'Unknown' : 'Off'))
  const dot = mining ? 'on' : state === 'SUSPENDED' ? 'warn' : state === 'OFFLINE' ? 'offline' : 'off'
  const cardCls = mining ? 'is-on' : state === 'OFFLINE' ? 'is-offline' : state === 'SUSPENDED' ? 'is-warn' : 'is-off'
  return { reachable, running, mining, statusText, dot, cardCls, upStr: formatUptime(miner?.uptimeSeconds) }
}
