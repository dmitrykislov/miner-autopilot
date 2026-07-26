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

const MINER_LABELS = { MINING: 'Mining', SUSPENDED: 'Suspended', STOPPED: 'Stopped', OFFLINE: 'Offline' }

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
  const statusText = !miner ? 'Connecting…' : (MINER_LABELS[state] || (reachable ? 'Unknown' : 'Offline'))
  const dot = mining ? 'on' : state === 'SUSPENDED' ? 'warn' : state === 'OFFLINE' ? 'offline' : 'off'
  const cardCls = mining ? 'is-on' : state === 'OFFLINE' ? 'is-offline' : state === 'SUSPENDED' ? 'is-warn' : 'is-off'
  return { reachable, running, mining, statusText, dot, cardCls, upStr: formatUptime(miner?.uptimeSeconds) }
}
