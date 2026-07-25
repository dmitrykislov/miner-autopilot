import React, { useEffect, useRef, useState } from 'react'
import { ICONS, Sun, House, Grid, Info, ArrowUp, ArrowDown } from './icons.jsx'
import { metaFor } from './metricMeta.js'
import { fmt, flow, minerView } from './logic.js'

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

export function EnergyFlow({ solar, house, spark, onHouseChange }) {
  const solarKw = solar ?? 0
  const houseKw = house.kw
  const metered = house.metered
  const { net, exporting, coverage, gridFlow } = flow(solarKw, houseKw)

  return (
    <section className="hero card">
      <div className="hero-head">
        <div>
          <h2>Live Power Flow</h2>
          <span className="hero-caption">
            {exporting
              ? <>Solar covers the home — exporting <strong className="pos">{fmt(gridFlow)} kW</strong></>
              : <>Grid is topping up <strong className="neg">{fmt(gridFlow)} kW</strong></>}
          </span>
        </div>
        <span className={`meter-badge ${metered ? 'on' : 'off'}`}>
          {metered ? '● consumption metered' : '○ consumption assumed'}
        </span>
      </div>

      <div className="hero-grid">
        <div className="flowline">
        <FlowNode icon="sun" tone="solar" label="Solar"
          info="Live AC power the SG10RS is generating right now — measured by the inverter.">
          <span className="node-value">{fmt(solarKw)}<em>kW</em></span>
          <span className="node-sub">generating</span>
        </FlowNode>

        <Flow active={solarKw > 0.01} dir="right" tone="solar" value={Math.min(solarKw, houseKw || solarKw)} />

        <FlowNode icon="house" tone="house" label="Home"
          info={metered
            ? 'Whole-home consumption measured live by the Powersensor mains clamp. Updates the instant it changes.'
            : 'Assumed household consumption — the Powersensor is not reporting, so this is an editable baseline, not a measurement.'}>
          {metered ? (
            <>
              <span key={house.ts} className="node-value live flash">{fmt(houseKw)}<em>kW</em></span>
              <Sparkline data={spark} />
              <span className="node-age">
                <span className="live-dot" /> {fmt(house.powerW, 0)} W · updated{' '}
                {house.ageSec === 0 ? 'just now' : `${house.ageSec}s ago`}
              </span>
            </>
          ) : (
            <>
              <div className="house-edit">
                <input type="number" step="0.1" min="0" value={houseKw}
                  onChange={(e) => onHouseChange(parseFloat(e.target.value) || 0)} />
                <em>kW</em>
              </div>
              <input className="house-slider" type="range" min="0" max="12" step="0.1"
                value={houseKw} onChange={(e) => onHouseChange(parseFloat(e.target.value))} />
              <span className="node-sub assumed-sub">assumed · adjustable</span>
            </>
          )}
        </FlowNode>

        <Flow active={gridFlow > 0.01} dir={exporting ? 'right' : 'left'}
          tone={exporting ? 'export' : 'import'} value={gridFlow} />

        <FlowNode icon="grid" tone={exporting ? 'export' : 'import'} label="Grid"
          info={exporting ? 'Surplus solar exported to the grid.' : 'Shortfall imported from the grid.'}>
          <span className="node-value">{fmt(gridFlow)}<em>kW</em></span>
          <span className="node-sub">{exporting ? 'exporting' : 'importing'}</span>
        </FlowNode>
        </div>

        <aside className="hero-summary">
          <div className="summary-ring">
            <CoverageRing pct={coverage} covering={exporting} />
            <div className="foot-text">
              <span className="foot-label">Self-sufficiency<InfoDot text="Share of the home's current draw being met by solar right now." /></span>
              <span className="foot-sub">{exporting ? 'Fully covered + surplus' : `${fmt(coverage, 0)}% from solar`}</span>
            </div>
          </div>
          <div className={`summary-margin ${exporting ? 'good' : 'bad'}`}>
            <span className="sm-icon">{exporting ? <ArrowUp size={18} /> : <ArrowDown size={18} />}</span>
            <div className="foot-text">
              <span className="foot-label">Net margin<InfoDot text="Solar − Home. Positive = surplus exported; negative = drawn from the grid." /></span>
              <span className="foot-big">{net >= 0 ? '+' : ''}{fmt(net)} <em>kW</em></span>
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
        <button className="btn btn-start" disabled={pending || !reachable || running}
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

// ---------------------------------------------------------------- app

export default function App() {
  const [snapshot, setSnapshot] = useState(null)
  const [connected, setConnected] = useState(false)
  const [manualKw, setManualKw] = useState(0.5)
  const [houseLive, setHouseLive] = useState(null)
  const [spark, setSpark] = useState([])
  const [miner, setMiner] = useState(null)
  const [minerPending, setMinerPending] = useState(false)
  const [now, setNow] = useState(Date.now())
  const postTimer = useRef(null)

  useEffect(() => {
    fetch('/api/inverter/house-load').then((r) => r.json())
      .then((d) => { if (typeof d.houseLoadKw === 'number') setManualKw(d.houseLoadKw) }).catch(() => {})
    fetch('/api/house/latest').then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (d && d.metered) setHouseLive({ ...d, at: Date.now() }) }).catch(() => {})
  }, [])

  useEffect(() => {
    const es = new EventSource('/api/inverter/stream')
    es.onopen = () => setConnected(true)
    es.onerror = () => setConnected(false)
    es.onmessage = (e) => { try { setSnapshot(JSON.parse(e.data)) } catch { /* ignore */ } }
    return () => es.close()
  }, [])

  useEffect(() => {
    const es = new EventSource('/api/house/stream')
    es.onmessage = (e) => {
      try {
        const r = JSON.parse(e.data)
        if (r && r.metered) {
          setHouseLive({ ...r, at: Date.now() })
          setSpark((prev) => [...prev, r.powerKw].slice(-SPARK_MAX))
        }
      } catch { /* ignore */ }
    }
    return () => es.close()
  }, [])

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])

  // Miner status SSE + control.
  useEffect(() => {
    const es = new EventSource('/api/miner/stream')
    es.onmessage = (e) => { try { setMiner(JSON.parse(e.data)) } catch { /* ignore */ } }
    return () => es.close()
  }, [])

  const minerCmd = (url) => {
    setMinerPending(true)
    fetch(url, { method: 'POST' })
      .then((r) => r.json()).then((s) => setMiner(s))
      .catch(() => {}).finally(() => setMinerPending(false))
  }
  const minerStart = () => minerCmd('/api/miner/start')
  const minerStop = () => minerCmd('/api/miner/stop')
  const minerSetPower = (watts) => {
    if (!Number.isFinite(watts)) return
    minerCmd(`/api/miner/power?watts=${encodeURIComponent(watts)}&apply=true`)
  }

  const changeHouse = (kw) => {
    setManualKw(kw)
    clearTimeout(postTimer.current)
    postTimer.current = setTimeout(() => {
      fetch(`/api/inverter/house-load?kw=${encodeURIComponent(kw)}`, { method: 'POST' }).catch(() => {})
    }, 350)
  }

  const meterFresh = houseLive && (now - houseLive.at) < STALE_MS
  const house = meterFresh
    ? { kw: houseLive.powerKw, metered: true, powerW: houseLive.powerW, voltage: houseLive.mainsVoltageV,
        ts: houseLive.timestamp, ageSec: Math.max(0, Math.round((now - houseLive.at) / 1000)) }
    : { kw: manualKw, metered: false }

  const online = snapshot?.online
  const solar = snapshot?.powerBalance?.solarPowerKw
  const hl = snapshot?.highlights ?? {}
  const metrics = snapshot?.metrics ?? []
  const strings = snapshot?.strings ?? []
  const byCat = (cat) => metrics.filter((m) => m.category === cat)
  const state = snapshot?.runningState || (connected ? '…' : 'Connecting')

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
        <div className="status-group">
          <span className={`pill state-${(snapshot?.runningState || '').toLowerCase()}`}><span className="pdot" />{state}</span>
          <span className={`pill meter ${house.metered ? 'up' : 'down'}`}><span className="pdot" />{house.metered ? 'Meter live' : 'Meter offline'}</span>
          <span className={`pill conn ${connected && online ? 'up' : 'down'}`}>
            <span className="pdot" />{connected ? (online ? 'Live' : 'Inverter offline') : 'Reconnecting…'}
            {snapshot?.timestamp && <span className="ts">{new Date(snapshot.timestamp).toLocaleTimeString()}</span>}
          </span>
        </div>
      </header>

      {snapshot?.error && !online && <div className="banner">Last poll failed: {snapshot.error}</div>}
      {!snapshot && <div className="loading card">Connecting to inverter…</div>}

      {snapshot && (
        <>
          <EnergyFlow solar={solar} house={house} spark={spark} onHouseChange={changeHouse} />

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

          <section className="section">
            <div className="section-head">
              <span className="section-icon"><Icon name="plug" size={16} /></span>
              <h2>Miner</h2>
              <InfoDot text="Braiins OS+ miner — start/stop and set the autotuning power target over the local GraphQL API." />
            </div>
            <MinerCard miner={miner} pending={minerPending}
              onStart={minerStart} onStop={minerStop} onSetPower={minerSetPower} />
          </section>

          {SECTIONS.map((sec) => {
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
          })}
        </>
      )}

      <footer className="foot">
        Solar via Sungrow WiNet-S (polled) · house consumption via Powersensor mains clamp (streamed live) ·
        margin falls back to an assumed baseline when the meter is offline.
      </footer>
    </div>
  )
}
