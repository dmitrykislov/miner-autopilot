// Minimal inline SVG icon set (no external dependency). Each takes {size}.
const base = (size = 22) => ({
  width: size, height: size, viewBox: '0 0 24 24', fill: 'none',
  stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round',
})

export const Sun = ({ size }) => (
  <svg {...base(size)}><circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </svg>
)
export const House = ({ size }) => (
  <svg {...base(size)}><path d="M3 10.5 12 3l9 7.5" /><path d="M5 9.5V21h14V9.5" /><path d="M9 21v-6h6v6" /></svg>
)
export const Grid = ({ size }) => (
  <svg {...base(size)}><path d="M12 2v6M9 8h6M8 8l-2 14M16 8l2 14M8.5 12h7M8 16h8M4 22h16" /></svg>
)
export const Bolt = ({ size }) => (
  <svg {...base(size)}><path d="M13 2 4 14h7l-1 8 9-12h-7l1-8z" /></svg>
)
export const Plug = ({ size }) => (
  <svg {...base(size)}><path d="M9 2v6M15 2v6" /><path d="M6 8h12v3a6 6 0 0 1-12 0V8z" /><path d="M12 17v5" /></svg>
)
export const Gauge = ({ size }) => (
  <svg {...base(size)}><path d="M12 13l4-3" /><path d="M4 18a8 8 0 1 1 16 0" /><circle cx="12" cy="13" r="1.5" fill="currentColor" /></svg>
)
export const Wave = ({ size }) => (
  <svg {...base(size)}><path d="M2 12c2.5-6 5.5-6 8 0s5.5 6 8 0" /></svg>
)
export const Thermometer = ({ size }) => (
  <svg {...base(size)}><path d="M10 13.5V5a2 2 0 1 1 4 0v8.5a4 4 0 1 1-4 0z" /></svg>
)
export const Calendar = ({ size }) => (
  <svg {...base(size)}><rect x="3" y="4" width="18" height="17" rx="2" /><path d="M3 9h18M8 2v4M16 2v4" /></svg>
)
export const Sigma = ({ size }) => (
  <svg {...base(size)}><path d="M18 4H6l6 8-6 8h12" /></svg>
)
export const Clock = ({ size }) => (
  <svg {...base(size)}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
)
export const Panel = ({ size }) => (
  <svg {...base(size)}><rect x="3" y="4" width="18" height="12" rx="1" /><path d="M3 8h18M3 12h18M9 4v12M15 4v12M12 16v4M9 20h6" /></svg>
)
export const Shield = ({ size }) => (
  <svg {...base(size)}><path d="M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6l7-3z" /></svg>
)
export const Chip = ({ size }) => (
  <svg {...base(size)}><rect x="6" y="6" width="12" height="12" rx="1" /><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4" /></svg>
)
export const ArrowUp = ({ size }) => (
  <svg {...base(size)}><path d="M12 19V5M6 11l6-6 6 6" /></svg>
)
export const ArrowDown = ({ size }) => (
  <svg {...base(size)}><path d="M12 5v14M6 13l6 6 6-6" /></svg>
)
export const Info = ({ size = 15 }) => (
  <svg {...base(size)} strokeWidth={2}><circle cx="12" cy="12" r="9" /><path d="M12 11v5" /><circle cx="12" cy="7.6" r="0.6" fill="currentColor" stroke="none" /></svg>
)
export const Fan = ({ size }) => (
  <svg {...base(size)}><circle cx="12" cy="12" r="1.6" fill="currentColor" />
    <path d="M12 10.4C12 6 12.8 3 15 3s2.4 4-.5 6.7M13.6 12c3.8-2.2 6.9-2.6 8-.7s-1.6 3.9-5.6 3.4M12 13.6c1.9 3.8 1.9 6.9-.2 7.9s-3-2.6-2.2-6.6M10.4 12C6.6 14.2 3.4 14.3 2.4 12.2S4.6 8.5 8.6 9.4" />
  </svg>
)

// Maps a metadata icon name to a component.
export const ICONS = {
  sun: Sun, house: House, grid: Grid, bolt: Bolt, plug: Plug, gauge: Gauge,
  wave: Wave, thermometer: Thermometer, calendar: Calendar, sigma: Sigma,
  clock: Clock, panel: Panel, shield: Shield, chip: Chip, fan: Fan,
}
