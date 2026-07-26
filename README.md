# House Energy Monitor & Miner Controller

A single, self-contained app that monitors a home solar setup and controls a Bitcoin miner, in real time:

- **⛏ Miner** — **Braiins OS+** (Antminer S19k Pro) via its local GraphQL API — start/stop, set power target, live status & fans
- **☀ Solar** — Sungrow **SG10RS** inverter via the WiNet-S local WebSocket API
- **🏠 House consumption** — **derived** as `solar − grid export` from the **Powersensor** mains clamp (its local UDP API), which measures **net grid** import/export
- **🔌 Smart plug** — TP-Link **Tapo P110** (present but disabled by default; see [Limitations](#limitations))

The React UI and the Spring Boot backend build into **one runnable jar**. Everything is configured from `.env` — no hardcoded IPs, accounts, or secrets in the source.

---

## Architecture

```
                           ┌─────────────────────────────────────────────┐
   Sungrow SG10RS  ──wss──▶│ WiNetWebSocketClient  ─poll 10s→ SSE        │
   (WiNet-S :443)          │                                             │
   Powersensor    ──UDP───▶│ PowerSensorClient     ─push→    SSE         │──▶ React UI
   (:49476)                │                                             │   (bundled in jar)
   Braiins miner  ─GraphQL▶│ BraiinsMinerClient    ─poll 10s→ SSE        │
   (:80 /graphql)          │  (@HttpExchange declarative client)         │
                           └─────────────────────────────────────────────┘
                                   Spring Boot 4 (WebFlux) — one jar
```

- **Backend:** Spring Boot 4.1.0 (Java 21, WebFlux, Jackson 3). Each device has a client → a service that polls/streams → a reactive `Sinks.Many` broadcast → an SSE endpoint.
- **Frontend:** React + Vite. Subscribes to the SSE streams (`EventSource`); no polling in the browser. Built into `backend/src/main/resources/static/` so `mvn package` bundles it into the jar.
- **Delivery to the UI is always SSE.** Sourcing differs: the inverter and miner are *polled* server-side (default every 10 s); the Powersensor is *push/streamed* (forwarded the instant a reading arrives).

### Module layout

```
miner-controller/
├─ pom.xml                 # aggregator
├─ start.sh                # build + run locally (see below)
├─ deploy.sh               # build here + deploy to a remote host over SSH
├─ .env / .env.example     # all configuration
├─ backend/                # Spring Boot app (io.dmitrykislov.miner)
│  ├─ config/HouseProperties.java      # nested @ConfigurationProperties (house.*)
│  ├─ inverter/            # Sungrow WiNet-S WebSocket client + poller + SSE
│  ├─ powersensor/         # Powersensor UDP client + state + SSE
│  ├─ braiins/             # Braiins GraphQL declarative client + service + SSE
│  ├─ plug/                # Tapo P110 (KLAP + cloud transports) — disabled by default
│  └─ api/                 # SSE + REST controllers
└─ frontend/               # React + Vite UI
```

---

## Quick start

Prerequisites: **Java 21+, Maven, Node/npm**, and a `.env` file (copy from `.env.example`).

```bash
cp .env.example .env      # then edit with your device IPs / credentials
./start.sh                # build UI + backend into the jar, then run
```

Open **http://localhost:8080** (or whatever `SERVER_PORT` you set).

### `start.sh` modes

| Command | What it does |
|---|---|
| `./start.sh` | Build the UI + package the jar, then run it (default) |
| `./start.sh --build` | Build only — produce the runnable jar, don't start |
| `./start.sh --run` | Run the already-built jar (no rebuild) |
| `./start.sh --dev` | Dev mode: backend (`mvn spring-boot:run`) + Vite dev server (`:5173`, live reload) |

`start.sh` sources `.env`, exports the vars, and the jar reads them via `application.yml` `${PLACEHOLDER}` bindings. The jar serves UI + REST + SSE from one process.

### Deploy to a remote host (`deploy.sh`)

`./deploy.sh` builds the jar locally (UI + tests) and deploys it over SSH: copies a staged jar, stops the running app, swaps it in, starts it detached, and waits for it to serve. The remote `.env` is left untouched (device config + `AUTOPILOT_ENABLED` are managed there). Connection settings come from env vars (with sensible defaults):

```bash
DEPLOY_HOST=<ip> DEPLOY_PORT=<port> DEPLOY_USER=<user> DEPLOY_KEY=<path.pem> ./deploy.sh
SKIP_TESTS=1 ./deploy.sh   # build without re-running tests
```

---

## Configuration (`.env`)

All environment-specific values are driven by env vars — the source and `application.yml` contain **no** hardcoded IPs, accounts, or secrets (`.env` is git-ignored).

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8080` | Backend + bundled UI |
| `FRONTEND_PORT` | `5173` | Vite dev server (dev mode only) |
| `LOG_LEVEL` | `INFO` | `io.dmitrykislov.miner` log level |
| `SCHEDULING_POOL_SIZE` | `3` | So the pollers never block each other |
| **Inverter** | | Sungrow SG10RS / WiNet-S |
| `INVERTER_HOST` | — | dongle LAN IP (required) |
| `INVERTER_PORT` | `443` | |
| `INVERTER_WS_PATH` | `/ws/home/overview` | |
| `INVERTER_USERNAME` / `INVERTER_PASSWORD` | — | WiNet local login |
| `INVERTER_POLL_INTERVAL_MS` | `10000` | |
| `INVERTER_REQUEST_TIMEOUT_MS` | `8000` | |
| **Powersensor** | | Net-grid power (mains clamp) |
| `POWERSENSOR_ENABLED` | `true` | |
| `POWERSENSOR_HOST` | — | gateway LAN IP |
| `POWERSENSOR_PORT` | `49476` | |
| `POWERSENSOR_SUBSCRIBE_LIFETIME_SECONDS` | `180` | |
| `POWERSENSOR_RESUBSCRIBE_INTERVAL_SECONDS` | `90` | keep-alive re-subscribe |
| `POWERSENSOR_STALE_AFTER_SECONDS` | `30` | reading older than this ⇒ house consumption (and the margin) is treated as unavailable |
| `POWERSENSOR_CLAMP_MAC` | _(blank)_ | blank = auto-detect the mains clamp (reports no voltage) |
| **Miner** | | Braiins OS+ |
| `MINER_ENABLED` | `true` | |
| `MINER_HOST` | — | miner LAN IP |
| `MINER_POLL_INTERVAL_MS` | `10000` | |
| `MINER_REQUEST_TIMEOUT_MS` | `8000` | |
| `MINER_AUTH_TOKEN` | _(blank)_ | only if the miner API requires a bearer token |
| `MINER_MIN_POWER_W` | `800` | hard floor — miner can't run below this |
| `MINER_MAX_POWER_W` | `3600` | hard ceiling — never exceed |
| **Autopilot** | | Solar-margin control loop (see below) |
| `AUTOPILOT_ENABLED` | `false` | off by default (drives real hardware) |
| `AUTOPILOT_INTERVAL_MS` | `30000` | how often the margin is evaluated |
| `AUTOPILOT_START_MARGIN_W` | `1000` | start / step-up when margin ≥ this |
| `AUTOPILOT_LOW_MARGIN_W` | `100` | back off (step down / stop) when margin < this |
| `AUTOPILOT_STEP_W` | `800` | power step (kept ≤ deadzone `start−low`=900 so it can't oscillate) |
| **Plug** (disabled) | | Tapo P110 |
| `PLUG_ENABLED` | `false` | see [Limitations](#limitations) |
| `PLUG_HOST` / `PLUG_EMAIL` / `PLUG_PASSWORD` / `PLUG_MODE` / `PLUG_MAC` / `PLUG_CLOUD_BASE_URL` | — | |

Config is bound to a single nested record, `HouseProperties` (`house.inverter`, `house.power-sensor`, `house.plug`, `house.miner`, `house.autopilot`).

`.env.example` mirrors `.env` key-for-key with the host/account/password values blanked; `cp .env.example .env` and fill in.

---

## HTTP API

All streams are **Server-Sent Events** (`text/event-stream`).

| Endpoint | Description |
|---|---|
| `GET /api/inverter/stream` · `/latest` | Solar snapshot: power balance (solar, net-grid, house, surplus), 22 metrics, MPPT strings |
| `GET /api/house/stream` · `/latest` | Net-grid power from the Powersensor clamp (signed: + import, − export), pushed live |
| `GET /api/miner/stream` · `/status` | Miner status: state, hashrate, power draw, fans, pools, power target |
| `POST /api/miner/start` · `/stop` | Start/stop BOSMiner |
| `POST /api/miner/power?watts=&apply=` | Set autotuning power target (clamped to the hard [min,max]) |
| `GET /api/plug/stream` · `/status` · `POST /on|off|toggle` | Tapo plug (when enabled) |
| `GET /api/system` | App version, start time, and uptime (for the UI footer) |

---

## UI

- **Live Power Flow** hero: Solar → Home → Grid with animated connectors, and a side panel with a **self-sufficiency ring** and the **surplus margin** (`solar − house`). House consumption is derived from `solar − net-grid` and updates live (with a sparkline). When the Powersensor isn't reporting, house and the margin show as **unavailable** (no assumed value).
- **KPI row:** Today / Lifetime yield, grid frequency, inverter temperature. These promoted values are **not repeated** in the detail sections below (de-duplicated).
- **Miner card:** honest state — **Mining / Suspended / Stopped / Offline** with reason (e.g. "no active pool"), live hashrate, power draw, **fan RPM**, uptime, pool count, editable **power target** + Apply, and **Start/Stop**.
- **Inverter detail sections:** Energy, Power, Grid & AC, DC/PV, Device Status, Per-phase — every reading with an info tooltip (excludes the values already shown as KPIs / in the hero).
- **Footer:** app version, start time, and live uptime (from `/api/system`).
- Theme-aware (light/dark), responsive.

---

## Solar-margin autopilot

Optional control loop (`io.dmitrykislov.miner.autopilot`, **disabled by default**) that soaks up surplus solar by driving the miner. Every `AUTOPILOT_INTERVAL_MS` (30 s) it reads the **margin** (the exportable surplus = `solar − house = −net-grid`, W) and the miner state, then decides via a pure, fully-tested planner.

> **Wiring assumption:** the control law assumes the Powersensor mains clamp sits on the whole-home feed **upstream of the miner**, so the miner's own draw is included in the net-grid reading and the margin already reflects it while mining. If the miner is on a circuit the clamp doesn't see, that assumption breaks (the margin won't drop as the miner ramps) — verify your clamp placement before enabling autopilot. With the assumption holding:

- **Off & margin ≥ 1000 W** → **start** the miner at the 800 W floor (leaving ~200 W headroom).
- **Mining & margin ≥ 1000 W** → **step power up** +800 W (capped at 3600 W).
- **Mining & margin < 100 W** → **step power down** — by at least one step, but **further if a single step wouldn't bring the target under the available surplus**; **stop** if even the 800 W floor would exceed the surplus.
- **Margin in [100, 1000)** → hold (deadzone).

Safety/correctness properties:
- **The running power never exceeds the available surplus.** The surplus a running miner can draw from is `margin + its own draw` (the meter counts the miner). On a sudden solar drop — say the miner is at 3000 W with +330 W to spare and a cloud swings the margin to −1880 W — one fixed step (→2200 W) would still import; instead the planner drops straight to ~1020 W (under the 1120 W now available) in a single tick. It never leaves the miner pulling from the grid.
- **Stops the miner when the margin can't be computed** — if solar is unavailable (inverter offline, e.g. at night) or house consumption is unavailable (Powersensor meter offline), the true margin is unknown, so it's unsafe to keep mining on a guess. The safe fallback is to stop.
- Acts only on **actually-mining** state, never `SUSPENDED` — a suspended miner (e.g. dead pools) draws ~0 W, so its draw is *not* in the margin; the autopilot skips it rather than ramping on phantom surplus.
- Respects the miner's hard **[min, max]** power limits (also enforced in `MinerService` on every set).
- **Stable by default:** the deadzone (`start − low` = 900 W) is ≥ one step (800 W), so a step can't carry the margin across the whole band and flap. A startup **warning** fires if custom thresholds break this.

The control law lives in `MinerAutopilotPlanner` (pure, no I/O); `MinerAutopilot` wires it to the live margin and `MinerService`. Enable with `AUTOPILOT_ENABLED=true`.

---

## Notable device details

- **Sungrow SG10RS / WiNet-S** — real-time data comes from the dongle's local WebSocket API (`wss://…/ws/home/overview`): `connect` → `login` → `devicelist` → `real`/`direct`. (Modbus TCP :502 exists but is firewalled while you're on the dongle's own WiFi AP.) The dongle's self-signed cert has no SAN, so hostname verification is disabled for these LAN clients (set once in `main()`).
- **Powersensor** — UDP pub/sub on :49476: send `subscribe(180)`, receive `instant_power` datagrams, re-subscribe every 90 s. The **mains clamp** (the reading with `voltage == null`) reports **net grid power** (signed: + importing, − exporting); the gateway plug supplies mains voltage. House consumption is derived as `solar + net-grid` (see `PowerBalance`).
- **Braiins OS+ miner** — GraphQL at `/graphql`. Declarative `@HttpExchange` client. `bosminer.start`/`stop` control the BOSMiner **service**; the miner only **hashes** ("Mining") when a live pool is connected — otherwise it self-pauses ("dead pools") and shows **Suspended**. Fans/hashrate are only reported while the service is up.
- **Fans ramp on every power change** because Braiins runs them in **automatic (target-temperature) mode** — more power ⇒ more heat ⇒ higher RPM (and vice-versa). Braiins also offers a **manual fixed-speed** mode (fans hold a set %) and an **immersion** mode, but manual mode disables thermal protection and is [not recommended](https://academy.braiins.com/os/plus-en/Configuration/index_configuration.html) unless set high (≈100%). The better mitigation is to change power **less often** — the autopilot's deadzone/step/interval already limit this; widening them (or raising the target temperature) reduces fan transients without losing thermal safety. There is no separate fan-throttle-on-change knob.

---

## Tests

```bash
cd backend && mvn test        # 139 tests
```

Covers config binding/defaults, power-balance math, i18n label mapping, DTO (Jackson 3) deserialization, snapshot mapping, the WebSocket client's frame correlation, all pollers/services (mocked clients), every controller (`@WebFluxTest`), the **autopilot planner** (exhaustive start/step/stop/deadzone/edge cases), the live margin source, and an **end-to-end WireMock** test that simulates the miner GraphQL API + inverter/house margin and asserts the exact mutations the autopilot sends.

---

## Resource usage

Bundled jar (backend + UI), idle at steady state (pollers every 10 s):

| Metric | Value |
|---|---|
| Resident memory (RSS) | ~210 MB |
| JVM heap used / committed | ~38 MB / ~69 MB (G1) |
| CPU (idle) | ~0% |
| Jar size | ~34 MB |
| Startup | ~1–2 s |

---

## Limitations

- **Miner needs a live pool to actually mine.** With no reachable pool configured, BOSMiner starts but sits **Suspended** ("dead pools"). There is no API to force hashing without a pool. On an isolated IoT network the miner also can't resolve `stratum.braiins.com` (no DNS/internet), which keeps pools "dead".
- **Tapo P110 is disabled** (`PLUG_ENABLED=false`). This unit runs TP-Link's newest **TPAP** encryption locally — unsupported by every open library — and the legacy cloud `passthrough` API no longer controls SMART Tapo devices. The full integration (local KLAP + cloud transports, controller, SSE, UI) is built and will work if the device ever exposes KLAP or TPAP support lands upstream.
- Several devices are cloud/internet-isolated on the LAN (Eero), which is why cloud paths and pool/NTP connectivity fail — a network concern, not an app one.

---

## License

Released under the [MIT License](LICENSE) — © 2026 Dmitry Kislov. Do anything you like with it; just keep the copyright notice.

**Not affiliated with, endorsed by, or sponsored by** Sungrow, Powersensor, Braiins, Bitmain, or TP-Link/Tapo. All product names, trademarks, and device protocols are the property of their respective owners; this project only interoperates with their local APIs and covers its own source code.
