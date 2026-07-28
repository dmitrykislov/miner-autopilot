import React, { useEffect, useState } from 'react'
import { ICONS, Sun, Info, ArrowUp, ArrowDown } from './icons.jsx'
import { metaFor } from './metricMeta.js'
import { fmt, flow, minerView, formatDuration } from './logic.js'
import { useEventSource } from './hooks.js'
import { isAuthed, clearToken, authHeaders, withToken } from './auth.js'
import Login from './Login.jsx'
import HistoryChart from './HistoryChart.jsx'

// ---------------------------------------------------------------- primitives

/** A small circular "i" that reveals an unambiguous description on hover/focus. */
export function InfoDot({ text, align = 'left' }) {
  return (
    <span className={`infodot ${align}`} tabIndex={0} role="button" aria-label={text}>
      <Info size={13} />
      <span className="infodot-bubble" role="tooltip">{text}</span>
    </span>
  )
}

function Icon({ name, size = 20 }) {
  const C = ICONS[name] || ICONS.gauge
  return <C size={size} />
}

/** SVG donut showing how much of the home is covered by solar. */
export function CoverageRing({ pct, covering }) {
  const r = 34, c = 2 * Math.PI * r
  const p = Math.max(0, Math.min(100, pct || 0))
  const off = c * (1 - p / 100)
  return (
    <div className="ring">
      <svg width="88" height="88" viewBox="0 0 88 88">
        <circle cx="44" cy="44" r={r} className="ring-track" />
        <circle cx="44" cy="44" r={r} className={`ring-fill ${covering ? 'good' : 'bad'}`}
          strokeDasharray={c} strokeDashoffset={off} transform="rotate(-90 44 44)" />
      </svg>
      <div className="ring-label">
        <span className="ring-pct">{fmt(p, 0)}<em>%</em></span>
        <span className="ring-cap">solar</span>
      </div>
    </div>
  )
}

/** Minimal inline sparkline for a rolling series of numbers. */
export function Sparkline({ data, color = 'var(--house)' }) {
  const w = 132, h = 34, pad = 3
  if (!data || data.length < 2) return <svg width={w} height={h} className="spark" />
  const min = Math.min(...data), max = Math.max(...data)
  const span = max - min || 1
  const step = (w - pad * 2) / (data.length - 1)
  const pts = data.map((v, i) => {
    const x = pad + i * step
    const y = pad + (h - pad * 2) * (1 - (v - min) / span)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const area = `${pad},${h - pad} ${pts.join(' ')} ${(pad + (data.length - 1) * step).toFixed(1)},${h - pad}`
  return (
    <svg width={w} height={h} className="spark" viewBox={`0 0 ${w} ${h}`}>
      <polygon points={area} fill={color} opacity="0.12" />
      <polyline points={pts.join(' ')} fill="none" stroke={color} strokeWidth="1.8"
        strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={pad + (data.length - 1) * step} cy={pad + (h - pad * 2) * (1 - (data[data.length - 1] - min) / span)}
        r="2.4" fill={color} />
    </svg>
  )
}

// ---------------------------------------------------------------- flow hero

function FlowNode({ icon, tone, label, info, children }) {
  return (
    <div className={`node ${tone}`}>
      <div className="node-icon"><Icon name={icon} size={24} /></div>
      <div className="node-label">{label}<InfoDot text={info} /></div>
      <div className="node-body">{children}</div>
    </div>
  )
}

/** Animated connector. active drives motion; dir = 'right' | 'left'; tone colors it. */
function Flow({ active, dir, tone, value }) {
  return (
    <div className={`flow ${active ? 'on' : 'off'} ${tone} ${dir}`}>
      <div className="flow-track"><span className="flow-dash" /></div>
      <div className="flow-cap">{dir === 'right' ? '▶' : '◀'} <strong>{fmt(value)}</strong> kW</div>
    </div>
  )
}

export function EnergyFlow({ solar, house, spark }) {
  const solarKw = solar ?? 0
  const metered = house.metered
  const houseKw = house.kw
  // House consumption and the margin are only known while Solar Analytics reports.
  const known = metered && Number.isFinite(houseKw)
  const { net, exporting, coverage, gridFlow } = known
    ? flow(solarKw, houseKw)
    : { net: null, exporting: false, coverage: null, gridFlow: null }

  return (
    <section className="hero card">
      <div className="hero-head">
        <div>
          <h2>Live Power Flow</h2>
          {/* Qualitative headline only — the actual numbers live in the nodes and the
              Surplus margin tile, so they aren't repeated here. */}
          <span className="hero-caption">
            {!known
              ? <>Waiting for Solar Analytics — consumption &amp; margin unavailable</>
              : exporting
                ? <>Solar is covering the home, with surplus to spare</>
                : <>Solar can't cover the home — drawing from the grid</>}
          </span>
        </div>
      </div>

      <div className="hero-grid">
        <div className="flowline">
        <FlowNode icon="sun" tone="solar" label="Solar"
          info="Live AC power the SG10RS is generating right now — measured by the inverter.">
          <span className="node-value">{fmt(solarKw)}<em>kW</em></span>
          <span className="node-sub">generating</span>
        </FlowNode>

        <Flow active={known && solarKw > 0.01} dir="right" tone="solar" value={Math.min(solarKw, houseKw || solarKw)} />

        <FlowNode icon="house" tone="house" label="Home"
          info={metered
            ? 'Whole-home consumption measured by Solar Analytics (their CT hardware). Updates live as new readings arrive.'
            : 'Solar Analytics is not reporting, so whole-home consumption is currently unavailable.'}>
          {known ? (
            <>
              <span key={house.ts} className="node-value live flash">{fmt(houseKw)}<em>kW</em></span>
              <Sparkline data={spark} />
              <span className="node-age">
                <span className="live-dot" /> updated{' '}
                {house.ageSec === 0 ? 'just now' : `${house.ageSec}s ago`}
              </span>
            </>
          ) : (
            <>
              <span className="node-value">--<em>kW</em></span>
              <span className="node-sub">waiting for meter…</span>
            </>
          )}
        </FlowNode>

        <Flow active={known && gridFlow > 0.01} dir={exporting ? 'right' : 'left'}
          tone={exporting ? 'export' : 'import'} value={gridFlow} />

        <FlowNode icon="grid" tone={known ? (exporting ? 'export' : 'import') : ''} label="Grid"
          info={!known ? 'Grid flow is unknown until the meter reports.'
            : exporting ? 'Surplus solar exported to the grid.' : 'Shortfall imported from the grid.'}>
          <span className="node-value">{fmt(gridFlow)}<em>kW</em></span>
          <span className="node-sub">{!known ? 'unavailable' : exporting ? 'exporting' : 'importing'}</span>
        </FlowNode>
        </div>

        <aside className="hero-summary">
          <div className="summary-ring">
            <CoverageRing pct={coverage} covering={exporting} />
            <div className="foot-text">
              <span className="foot-label">Self-sufficiency<InfoDot text="Share of the home's current draw being met by solar right now." /></span>
              <span className="foot-sub">{!known ? 'Meter offline' : exporting ? 'Fully covered + surplus' : `${fmt(coverage, 0)}% from solar`}</span>
            </div>
          </div>
          <div className={`summary-margin ${!known ? '' : exporting ? 'good' : 'bad'}`}>
            <span className="sm-icon">{exporting ? <ArrowUp size={18} /> : <ArrowDown size={18} />}</span>
            <div className="foot-text">
              <span className="foot-label">Surplus margin<InfoDot text="Current surplus = Solar − House load. Positive = spare power (exporting); negative = shortfall (drawing from the grid)." /></span>
              <span className="foot-big">{!known ? '--' : `${net >= 0 ? '+' : ''}${fmt(net)}`} <em>kW</em></span>
            </div>
          </div>
        </aside>
      </div>
    </section>
  )
}

// ---------------------------------------------------------------- KPI + cards

function Kpi({ icon, label, value, unit, info }) {
  return (
    <div className="kpi">
      <span className="kpi-icon"><Icon name={icon} size={18} /></span>
      <div className="kpi-body">
        <span className="kpi-label">{label}<InfoDot text={info} align="left" /></span>
        <span className="kpi-value">{value}{unit ? <em> {unit}</em> : null}</span>
      </div>
    </div>
  )
}

function MetricCard({ metric }) {
  const meta = metaFor(metric.key)
  const isDim = metric.value === '--' || metric.value === '' || metric.value == null
  return (
    <div className={`metric ${isDim ? 'dim' : ''}`}>
      <div className="metric-top">
        <span className="metric-icon"><Icon name={meta.icon} size={16} /></span>
        <span className="metric-label">{metric.label}</span>
        <InfoDot text={meta.desc} align="right" />
      </div>
      <div className="metric-value">
        {isDim ? '--' : metric.value}{metric.unit && !isDim ? <em> {metric.unit}</em> : null}
      </div>
    </div>
  )
}

function MpptCard({ s }) {
  return (
    <div className={`metric ${s.powerKw > 0.01 ? '' : 'dim'}`}>
      <div className="metric-top">
        <span className="metric-icon"><Icon name="panel" size={16} /></span>
        <span className="metric-label">{s.name}</span>
        <InfoDot align="right"
          text={`DC input string ${s.name}: ${fmt(s.voltage, 1)} V × ${fmt(s.current, 1)} A = ${fmt(s.powerKw, 3)} kW.`} />
      </div>
      <div className="metric-value">{fmt(s.powerKw, 2)}<em> kW</em></div>
      <div className="metric-sub">{fmt(s.voltage, 1)} V · {fmt(s.current, 1)} A</div>
    </div>
  )
}

export function MinerCard({ miner, pending, onStart, onStop, onSetPower }) {
  const [target, setTarget] = useState('')
  useEffect(() => {
    if (miner?.powerTargetW != null) setTarget(String(miner.powerTargetW))
  }, [miner?.powerTargetW])

  const { reachable, running, mining, statusText, dot, cardCls, upStr } = minerView(miner)

  return (
    <div className={`plug card ${cardCls}`}>
      <div className="plug-icon"><Icon name="chip" size={22} /></div>
      <div className="plug-body">
        <div className="plug-name">
          {miner?.model || 'Braiins Miner'}
          <InfoDot text="Braiins OS+ miner. Start brings up BOSMiner; it only hashes (Mining) when an alive pool is connected — otherwise it sits Suspended." />
        </div>
        <div className="plug-status">
          <span className={`plug-dot ${dot}`} />
          {statusText}
          {running && upStr && <> · up {upStr}</>}
          {reachable && miner?.totalPools > 0 && <> · {miner.activePools}/{miner.totalPools} pools</>}
        </div>
        {miner?.statusReason && !mining && reachable && (
          <div className="plug-substatus">{miner.statusReason}</div>
        )}
        {reachable && mining && (
          <div className="plug-metrics">
            {miner.hashrateThs != null && <span><strong>{fmt(miner.hashrateThs, 1)}</strong> TH/s</span>}
            {miner.powerDrawW != null && <span><strong>{miner.powerDrawW}</strong> W draw</span>}
          </div>
        )}
        {reachable && running && miner.fans?.length > 0 && (
          <div className="fans">
            <span className="fans-label"><Icon name="fan" size={14} /> Fans</span>
            {miner.fans.map((f, i) => (
              <span className="fan" key={f.name || i} title={`${f.name}: ${f.speedPercent}% duty`}>
                <span className={`fan-spin ${f.rpm > 0 ? 'on' : ''}`}><Icon name="fan" size={13} /></span>
                {f.rpm} <em>rpm</em>
              </span>
            ))}
          </div>
        )}
        {!reachable && miner?.error && <div className="plug-error">{miner.error}</div>}
        <div className="miner-power">
          <span className="mp-label">Power target</span>
          <input type="number" step="50" min="0" value={target}
            onChange={(e) => setTarget(e.target.value)} disabled={pending || !reachable} />
          <em>W</em>
          <button className="btn btn-sm" disabled={pending || !reachable || target === ''}
            onClick={() => onSetPower(parseInt(target, 10))}>Apply</button>
        </div>
      </div>
      <div className="miner-actions">
        {/* Start is allowed whenever the miner isn't already up — including when it reads Offline,
            since a stopped Braiins miner reports its API as unavailable but can still be started. */}
        <button className="btn btn-start" disabled={pending || running || !miner}
          onClick={onStart}>Start</button>
        <button className="btn btn-stop" disabled={pending || !reachable || !running}
          onClick={onStop}>Stop</button>
      </div>
    </div>
  )
}

const SECTIONS = [
  { id: 'energy', title: 'Energy', icon: 'sigma', desc: 'Daily and lifetime generation totals and running time.' },
  { id: 'power', title: 'Power', icon: 'bolt', desc: 'Instantaneous power quantities from the inverter.' },
  { id: 'grid', title: 'Grid & AC Output', icon: 'grid', desc: 'AC-side measurements at the grid connection point.' },
  { id: 'dc', title: 'DC / PV Array', icon: 'panel', desc: 'DC-side measurements from the solar panels.' },
  { id: 'status', title: 'Device Status', icon: 'gauge', desc: 'Operating state and internal conditions.' },
  { id: 'other', title: 'Per-phase Detail', icon: 'plug', desc: 'Raw per-phase grid voltage/current (B/C blank on single-phase).' },
]

const STALE_MS = 30000
const SPARK_MAX = 40

// Inverter metric keys already surfaced elsewhere (KPI tiles, the Solar flow node,
// the header state pill) — hidden from the detailed metric sections to avoid showing
// the same value twice.
const PROMOTED_KEYS = new Set([
  'I18N_COMMON_DAILY_POWER_YIELD',       // KPI: Today
  'I18N_COMMON_TOTAL_YIELD',             // KPI: Lifetime
  'I18N_COMMON_GRID_FREQUENCY',          // KPI: Grid Frequency
  'I18N_COMMON_AIR_TEM_INSIDE_MACHINE',  // KPI: Inverter Temp
  'I18N_COMMON_TOTAL_ACTIVE_POWER',      // Solar flow node
  'I18N_COMMON_RUNNING_STATE',           // header state pill
])

// ---------------------------------------------------------------- app

/** Autopilot on/off toggle plus its status and the details of the last change it made. */
export function AutopilotCard({ autopilot, pending, onToggle }) {
  const enabled = !!autopilot?.enabled
  const c = autopilot?.lastChange
  const fmtTime = (iso) => {
    if (!iso) return null
    const d = new Date(iso)
    return Number.isNaN(d.getTime()) ? null : d.toLocaleString()
  }
  const power = (w) => (w == null ? 'off' : `${w} W`)
  // The card is already titled "Autopilot", so drop the redundant "autopilot:" prefix the engine
  // puts on every decision/reason string for a cleaner read.
  const clean = (s) => (s || '').replace(/^autopilot:\s*/i, '')
  const decision = clean(autopilot?.lastDecision) || 'Awaiting first evaluation'
  return (
    <div className={`plug card autopilot ${enabled ? 'is-on' : 'is-off'}`}>
      <div className="plug-icon"><Icon name="bolt" size={22} /></div>
      <div className="plug-body">
        <div className="ap-head">
          <span className="ap-title">Autopilot</span>
          <span className={`pill ${enabled ? 'up' : 'down'}`}><span className="pdot" />{enabled ? 'On' : 'Off'}</span>
          <button type="button" className={`ap-toggle ${enabled ? 'is-off' : 'is-on'}`}
                  disabled={pending} onClick={onToggle}>
            {pending ? '…' : enabled ? 'Disable autopilot' : 'Enable autopilot'}
          </button>
        </div>
        <div className="ap-decision" aria-label="last decision">{decision}</div>
        {c ? (
          <div className="ap-change">
            <span className="ap-change-label">Last action</span>
            <strong>{c.action}</strong>
            <span className="ap-power">{power(c.fromPowerW)} → {power(c.toPowerW)}</span>
            {fmtTime(c.at) && <span className="ap-time muted">{fmtTime(c.at)}</span>}
            {c.detail && <div className="ap-detail muted">{clean(c.detail)}</div>}
          </div>
        ) : (
          <div className="ap-change muted">No changes made yet</div>
        )}
      </div>
    </div>
  )
}

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'advanced', label: 'Advanced' },
]

/** Controlled tab strip. `active` is the current tab id; `onChange(id)` switches. */
export function Tabs({ active, onChange, tabs = TABS }) {
  return (
    <nav className="tabs" role="tablist" aria-label="Dashboard sections">
      {tabs.map((t) => (
        <button key={t.id} type="button" role="tab" aria-selected={active === t.id}
          className={`tab-btn ${active === t.id ? 'active' : ''}`}
          onClick={() => onChange(t.id)}>{t.label}</button>
      ))}
    </nav>
  )
}

/**
 * The detailed inverter metric sections (Energy, Power, Grid, DC, Status, Per-phase) plus the MPPT
 * string cards. Lives under the Advanced tab to keep the main Overview focused on the flow, KPIs,
 * history and controls. Keys already shown elsewhere are hidden to avoid duplication.
 */
export function InverterDetails({ metrics = [], strings = [] }) {
  const byCat = (cat) => metrics.filter((m) => m.category === cat && !PROMOTED_KEYS.has(m.key))
  const sections = SECTIONS.map((sec) => {
    const items = byCat(sec.id)
    const withMppt = sec.id === 'dc'
    if (!items.length && !(withMppt && strings.length)) return null
    return (
      <section className="section" key={sec.id}>
        <div className="section-head">
          <span className="section-icon"><Icon name={sec.icon} size={16} /></span>
          <h2>{sec.title}</h2>
          <InfoDot text={sec.desc} />
        </div>
        <div className="grid">
          {items.map((m) => <MetricCard key={m.key} metric={m} />)}
          {withMppt && strings.map((s) => <MpptCard key={s.name} s={s} />)}
        </div>
      </section>
    )
  }).filter(Boolean)

  if (!sections.length) {
    return <div className="card advanced-empty">No inverter detail available right now.</div>
  }
  return <>{sections}</>
}

function Dashboard({ onLogout }) {
  const [snapshot, setSnapshot] = useState(null)
  const [connected, setConnected] = useState(false)
  const [houseLive, setHouseLive] = useState(null)
  const [spark, setSpark] = useState([])
  const [miner, setMiner] = useState(null)
  const [minerPending, setMinerPending] = useState(false)
  const [now, setNow] = useState(Date.now())
  const [system, setSystem] = useState(null)
  const [autopilot, setAutopilot] = useState(null)
  const [apPending, setApPending] = useState(false)
  const [tab, setTab] = useState('overview')

  // fetch with the bearer token; a 401 means the token is gone/expired → log out.
  const authFetch = (url, opts = {}) =>
    fetch(url, { ...opts, headers: { ...(opts.headers || {}), ...authHeaders() } })
      .then((r) => { if (r.status === 401) { onLogout(); throw new Error('unauthorized') } return r })

  useEffect(() => {
    authFetch('/api/house/latest').then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (d && d.metered) setHouseLive({ ...d, at: Date.now() }) }).catch(() => {})
    authFetch('/api/system').then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (d) setSystem({ version: d.version, startedMs: Date.parse(d.startedAt) }) }).catch(() => {})
  }, [])

  // SSE carries the token as ?token= (EventSource can't set headers).
  useEventSource(withToken('/api/inverter/stream'), setSnapshot,
    { onOpen: () => setConnected(true), onError: () => setConnected(false) })

  useEventSource(withToken('/api/house/stream'), (r) => {
    if (r && r.metered) {
      setHouseLive({ ...r, at: Date.now() })
      setSpark((prev) => [...prev, r.powerKw].slice(-SPARK_MAX)) // measured house consumption
    }
  })

  useEventSource(withToken('/api/miner/stream'), setMiner)
  useEventSource(withToken('/api/autopilot/stream'), setAutopilot)

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])

  const toggleAutopilot = () => {
    setApPending(true)
    const path = autopilot?.enabled ? '/api/autopilot/disable' : '/api/autopilot/enable'
    authFetch(path, { method: 'POST' })
      .then((r) => r.json()).then(setAutopilot)
      .catch(() => {}).finally(() => setApPending(false))
  }

  const minerCmd = (url) => {
    setMinerPending(true)
    authFetch(url, { method: 'POST' })
      .then((r) => r.json()).then((s) => setMiner(s))
      .catch(() => {}).finally(() => setMinerPending(false))
  }
  const minerStart = () => minerCmd('/api/miner/start')
  const minerStop = () => minerCmd('/api/miner/stop')
  const minerSetPower = (watts) => {
    if (!Number.isFinite(watts)) return
    minerCmd(`/api/miner/power?watts=${encodeURIComponent(watts)}&apply=true`)
  }

  const meterFresh = houseLive && (now - houseLive.at) < STALE_MS
  // house = measured whole-home consumption (kW) from Solar Analytics
  const house = meterFresh
    ? { kw: houseLive.powerKw, metered: true,
        ts: houseLive.timestamp, ageSec: Math.max(0, Math.round((now - houseLive.at) / 1000)) }
    : { kw: null, metered: false }

  const online = snapshot?.online
  const solar = snapshot?.powerBalance?.solarPowerKw
  const hl = snapshot?.highlights ?? {}
  const metrics = snapshot?.metrics ?? []
  const strings = snapshot?.strings ?? []

  return (
    <div className="app">
      <header className="appbar">
        <div className="brand">
          <span className="brand-mark"><Sun size={20} /></span>
          <div>
            <h1>{snapshot?.deviceModel || 'SG10RS'}<span className="muted"> Solar Monitor</span></h1>
            <div className="sub">SN {snapshot?.serialNumber || '—'}</div>
          </div>
        </div>
        {/* Tabs live in the header; live status is shown in the Live Power Flow section below. */}
        {snapshot && <Tabs active={tab} onChange={setTab} />}
      </header>

      {snapshot?.error && !online && <div className="banner">Last poll failed: {snapshot.error}</div>}
      {!snapshot && <div className="loading card">Connecting to inverter…</div>}

      {snapshot && tab === 'overview' && (
        <>
          <EnergyFlow solar={solar} house={house} spark={spark} />

          <div className="kpis">
            <Kpi icon="calendar" label="Today" value={fmt(hl.dailyYieldKwh, 1)} unit="kWh"
              info="Energy generated since midnight." />
            <Kpi icon="sigma" label="Lifetime" value={fmt(hl.totalYieldKwh, 0)} unit="kWh"
              info="Total energy generated since installation." />
            <Kpi icon="wave" label="Grid Frequency" value={fmt(hl.gridFrequencyHz, 2)} unit="Hz"
              info="Measured grid frequency (0 while the inverter is in standby)." />
            <Kpi icon="thermometer" label="Inverter Temp" value={fmt(hl.temperatureC, 1)} unit="℃"
              info="Air temperature inside the inverter enclosure." />
          </div>

          <HistoryChart authFetch={authFetch} />

          <section className="section">
            <div className="section-head">
              <span className="section-icon"><Icon name="plug" size={16} /></span>
              <h2>Miner</h2>
              <InfoDot text="Braiins OS+ miner — start/stop and set the autotuning power target over the local GraphQL API." />
            </div>
            <MinerCard miner={miner} pending={minerPending}
              onStart={minerStart} onStop={minerStop} onSetPower={minerSetPower} />
          </section>

          <section className="section">
            <div className="section-head">
              <span className="section-icon"><Icon name="bolt" size={16} /></span>
              <h2>Autopilot</h2>
              <InfoDot text="Solar-margin autopilot — starts, steps, and stops the miner to soak up surplus solar. Drives real hardware; toggle with care." />
            </div>
            <AutopilotCard autopilot={autopilot} pending={apPending} onToggle={toggleAutopilot} />
          </section>
        </>
      )}

      {snapshot && tab === 'advanced' && (
        <InverterDetails metrics={metrics} strings={strings} />
      )}

      <footer className="foot">
        <div>
          Solar via Sungrow WiNet-S (polled) · house consumption via Solar Analytics (polled) ·
          margin = solar − measured house; unavailable while consumption data is stale.
        </div>
        <div className="foot-sys">
          {system && <>
            <strong>v{system.version}</strong>
            {Number.isFinite(system.startedMs) && <>
              {' · '}started {new Date(system.startedMs).toLocaleString()}
              {' · '}up {formatDuration(Math.floor((now - system.startedMs) / 1000))}
            </>}
            {' · '}
          </>}
          <button type="button" className="logout" onClick={onLogout}>Log out</button>
        </div>
      </footer>
    </div>
  )
}

/** Auth gate: show the password page until a token is stored, then the dashboard. */
export default function App() {
  const [authed, setAuthed] = useState(isAuthed())
  if (!authed) return <Login onSuccess={() => setAuthed(true)} />
  return <Dashboard onLogout={() => { clearToken(); setAuthed(false) }} />
}
