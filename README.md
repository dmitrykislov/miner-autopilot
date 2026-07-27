# Antminer S19k Pro Autopilot based on solar energy surplus

A single, self-contained app that monitors a home solar setup and controls a Bitcoin miner in real time — and can **automatically run the miner on surplus solar**.

![Dashboard](screenshot.png)

- **⛏ Miner** — **Braiins OS+** (Antminer S19k Pro) via its local GraphQL API — start/stop, set power target, live status & fans
- **☀ Solar** — Sungrow **SG10RS** inverter via the WiNet-S local WebSocket API
- **🏠 House consumption** — measured whole-home load from **Solar Analytics** (their CT monitoring) via their cloud API
- **⚡ Autopilot** — optional smoothed control loop that ladder-tracks the **time-averaged** solar surplus to auto **start / step / stop** the miner, never importing from the grid; toggle it live from the UI
- **📈 History** — a light, file-backed 1-month telemetry log drawn as an interactive **D3 chart** (solar / house / miner power over time) with hover-for-detail markers at every autopilot power change
- **🔒 Access control** — a single password (stored only as a bcrypt hash) gates every endpoint with a bearer token

The React UI and the Spring Boot backend build into **one runnable jar** (~22 MB). Everything is configured from `.env` — no hardcoded IPs, accounts, or secrets in the source. Lean enough to run on a **416 MB Raspberry Pi** (~140 MB RSS).

---

## Architecture

```
                           ┌─────────────────────────────────────────────┐
   Sungrow SG10RS  ──wss──▶│ WiNetWebSocketClient  ─poll 10s→ SSE        │
   (WiNet-S :443)          │                                             │
   Solar Analytics ─HTTPS─▶│ SolarAnalyticsClient  ─poll 15s→ SSE        │──▶ React UI
   (cloud API)             │                                             │   (bundled in jar)
   Braiins miner  ─GraphQL▶│ BraiinsMinerClient    ─poll 10s→ SSE        │
   (:80 /graphql)          │  (@HttpExchange declarative client)         │
                           └─────────────────────────────────────────────┘
                                   Spring Boot 4 (WebFlux) — one jar
```

- **Backend:** Spring Boot 4.1.0 (Java 21, WebFlux, Jackson 3). Each device has a client → a service that polls/streams → a reactive `Sinks.Many` broadcast → an SSE endpoint.
- **Frontend:** React + Vite. Subscribes to the SSE streams (`EventSource`); no polling in the browser. Built into `backend/src/main/resources/static/` so `mvn package` bundles it into the jar.
- **Delivery to the UI is always SSE.** Sourcing: the inverter, miner, and Solar Analytics consumption are all *polled* server-side (10–15 s) and fanned out to SSE.

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
│  ├─ solaranalytics/      # Solar Analytics client + consumption state + SSE
│  ├─ braiins/             # Braiins GraphQL declarative client + service + SSE
│  └─ api/                 # SSE + REST controllers
└─ frontend/               # React + Vite UI
```

---

## Quick start

Prerequisites: **Java 21+, Maven, Node/npm**, and a `.env` file (copy from `.env.example`).

```bash
cp .env.example .env      # then edit: device IPs/credentials + set the UI password hash (see Access control)
./start.sh                # build UI + backend into the jar, then run
```

Open **http://localhost:8899** (the `SERVER_PORT` in `.env.example`; the app's built-in default is `8080`) and log in with the password whose hash you put in `.env`.

### `start.sh` modes

| Command | What it does |
|---|---|
| `./start.sh` | Build the UI + package the jar, then run it (default) |
| `./start.sh --build` | Build only — produce the runnable jar, don't start |
| `./start.sh --run` | Run the already-built jar (no rebuild) |
| `./start.sh --dev` | Dev mode: backend (`mvn spring-boot:run`) + Vite dev server (`:5173`, live reload) |

`start.sh` sources `.env`, exports the vars, and the jar reads them via `application.yml` `${PLACEHOLDER}` bindings. The jar serves UI + REST + SSE from one process.

### Deploy to a remote host (`deploy.sh`)

`./deploy.sh` builds the jar locally and swaps it onto the remote host over SSH, safely:

1. preflight the SSH connection, build (UI + tests unless `SKIP_TESTS=1`), and `scp` a staged `<jar>.new`;
2. **verify the jar's sha256** on the remote before touching anything;
3. stop the old app, **free `SERVER_PORT`** (kills whatever still listens on it), keep the previous jar as `<jar>.bak`;
4. start the new jar detached with the `.env`'s `JAVA_OPTS`, then health-check `/api/system` (a `401` counts as "up" — auth is on);
5. **auto-roll-back** to `.bak` if the new jar doesn't become healthy.

The remote `.env` is **not** modified — device config, `AUTOPILOT_ENABLED`, the auth hash, and `JAVA_OPTS` are managed on the device (copy your `.env` there once). Connection settings come from the local (git-ignored) `.env` or env vars:

```bash
# put these in .env, or pass inline:
DEPLOY_HOST=<ip> DEPLOY_PORT=<port> DEPLOY_USER=<user> DEPLOY_KEY=<path.pem> ./deploy.sh
SKIP_TESTS=1 ./deploy.sh   # build without re-running the test suites
```

---

## Configuration (`.env`)

All environment-specific values are driven by env vars — the source and `application.yml` contain **no** hardcoded IPs, accounts, or secrets (`.env` is git-ignored).

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8080` | Backend + bundled UI |
| `FRONTEND_PORT` | `5173` | Vite dev server (dev mode only) |
| `LOG_LEVEL` | `INFO` | `io.dmitrykislov.miner` log level |
| `SCHEDULING_POOL_SIZE` | `6` | One thread per scheduled task (inverter poll / consumption poll / energy sampler / autopilot tick / miner poll / history recorder) so none blocks the others |
| `JAVA_OPTS` | _(blank)_ | Extra JVM flags. On a small Pi use the heap/footprint caps in `.env.example` (`-Xmx128m` + SerialGC + …) → ~140 MB RSS, no OOM |
| **Inverter** | | Sungrow SG10RS / WiNet-S |
| `INVERTER_HOST` | — | dongle LAN IP (required) |
| `INVERTER_PORT` | `443` | |
| `INVERTER_WS_PATH` | `/ws/home/overview` | |
| `INVERTER_USERNAME` / `INVERTER_PASSWORD` | — | WiNet local login |
| `INVERTER_POLL_INTERVAL_MS` | `10000` | |
| `INVERTER_REQUEST_TIMEOUT_MS` | `8000` | |
| **Solar Analytics** | | Whole-home consumption (cloud) |
| `SOLARANALYTICS_ENABLED` | `true` | |
| `SOLARANALYTICS_HOST` | `…/api/v3` | API base URL |
| `SOLARANALYTICS_USER` / `SOLARANALYTICS_PASSWORD` | — | account email + password (HTTP Basic) |
| `SOLARANALYTICS_SITE_ID` | _(blank)_ | blank = auto-detect the first active site |
| `SOLARANALYTICS_POLL_INTERVAL_MS` | `15000` | how often consumption is polled |
| `SOLARANALYTICS_STALE_AFTER_SECONDS` | `60` | reading older than this ⇒ consumption (and surplus) unavailable |
| `SOLARANALYTICS_MIN_SOLAR_W` | `800` | only call the consumption API when solar generation exceeds this (no usable surplus below it) |
| **Miner** | | Braiins OS+ |
| `MINER_ENABLED` | `true` | |
| `MINER_HOST` | — | miner LAN IP |
| `MINER_POLL_INTERVAL_MS` | `10000` | |
| `MINER_REQUEST_TIMEOUT_MS` | `8000` | |
| `MINER_AUTH_TOKEN` | _(blank)_ | only if the miner API requires a bearer token |
| `MINER_MIN_POWER_W` | `800` | hard floor — miner can't run below this |
| `MINER_MAX_POWER_W` | `3600` | hard ceiling — never exceed |
| **Autopilot** | | Smoothed surplus control loop (see below) |
| `AUTOPILOT_ENABLED` | `false` | off by default (drives real hardware) |
| `AUTOPILOT_INTERVAL_MS` | `30000` | how often it evaluates + acts |
| `AUTOPILOT_FLOOR_W` | `1200` | lowest run power (≥ `MINER_MIN_POWER_W`); below → stop |
| `AUTOPILOT_STEP_W` | `400` | ladder rung spacing |
| `AUTOPILOT_HEADROOM_W` | `200` | anti-import buffer (target ≤ surplus − headroom) |
| `AUTOPILOT_START_SURPLUS_W` | `1600` | surplus to (re)start from off (> floor → hysteresis) |
| `AUTOPILOT_UP_MAX_RUNGS` | `2` | rungs a single up-move may climb |
| `AUTOPILOT_EMERGENCY_GAP_W` | `800` | over-draw that bypasses the down interval |
| `AUTOPILOT_UP_INTERVAL_MS` | `900000` | dampening between up/start steps (≥ long window) |
| `AUTOPILOT_DOWN_INTERVAL_MS` | `300000` | between routine down steps |
| `AUTOPILOT_SHORT_WINDOW_MS` | `180000` | averaging window for down/stop (fast) |
| `AUTOPILOT_LONG_WINDOW_MS` | `900000` | averaging window for up/start (conservative) |
| `AUTOPILOT_FRESH_WITHIN_MS` | `90000` | a feed older than this ⇒ surplus unknown |
| `AUTOPILOT_SHORT_COVERAGE_MS` | `60000` | min span for a trusted short average |
| `AUTOPILOT_LONG_COVERAGE_MS` | `300000` | min span for a trusted long average |
| **History** | | Telemetry log for the trend chart (see [History & trends](#history--trends-chart)) |
| `HISTORY_ENABLED` | `true` | record telemetry + serve the chart |
| `HISTORY_DIR` | `data/history` | append-only day-files live here (created if absent) |
| `HISTORY_RECORD_INTERVAL_MS` | `60000` | how often a sample is appended |
| `HISTORY_RETENTION_DAYS` | `31` | keep ~1 month, then discard (memory + disk) |
| **Access control** | | Password gate (see [Access control](#access-control)) |
| `AUTH_ENABLED` | `true` | when off, all endpoints are open (dev only) |
| `AUTH_PASSWORD_HASH` | — | bcrypt hash of the UI password; blank ⇒ fail-closed (everything rejected) |
| `AUTH_TOKEN_TTL_DAYS` | `30` | how long a login stays valid |

Config is bound to nested records: `HouseProperties` (`house.inverter`, `house.solar-analytics`, `house.miner`, `house.autopilot`) and `AuthProperties` (`auth.*`).

`.env.example` mirrors `.env` key-for-key with the host/account/password values blanked; `cp .env.example .env` and fill in.

---

## HTTP API

All streams are **Server-Sent Events** (`text/event-stream`).

| Endpoint | Description |
|---|---|
| `GET /api/inverter/stream` · `/latest` | Solar snapshot: power balance (solar, net-grid, house, surplus), 22 metrics, MPPT strings |
| `GET /api/house/stream` · `/latest` | Whole-home consumption (Solar Analytics), pushed live |
| `GET /api/miner/stream` · `/status` | Miner status: state, hashrate, power draw, fans, pools, power target |
| `POST /api/miner/start` · `/stop` | Start/stop BOSMiner |
| `POST /api/miner/power?watts=&apply=` | Set autotuning power target (clamped to the hard [min,max]) |
| `GET /api/autopilot` · `/stream` | Autopilot status: enabled, last decision, and last change (action, from→to, time) |
| `POST /api/autopilot/enable` · `/disable` | Turn the autopilot on/off at runtime; returns the new status |
| `GET /api/system` | App version, start time, and uptime (for the UI footer) |
| `POST /api/auth/login` | Exchange `{password}` for a bearer token (the one open endpoint) |

Every endpoint except `/api/auth/login` (and static assets) requires the token — sent as
`Authorization: Bearer <token>`, or `?token=<token>` for the SSE streams (see [Access control](#access-control)).

---

## Access control

All endpoints are protected by a single shared password. The password is never stored or
committed in plaintext — only its **bcrypt** hash lives in `.env` (`AUTH_PASSWORD_HASH`).

### Configure the password (3 steps)

**1 — Generate a bcrypt hash** of your chosen password. The single quotes matter: a bcrypt
hash contains `$`, which the shell would otherwise expand.

```bash
# with Apache htpasswd (-B = bcrypt, C 10 = cost factor 10); prints just the $2y$… hash
htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'

# no htpasswd? any bcrypt tool works, e.g. Python:
python3 -c "import bcrypt; print(bcrypt.hashpw(b'your-password', bcrypt.gensalt(10)).decode())"
```

**2 — Put it in `.env`, single-quoted** (so `source .env` doesn't mangle the `$`):

```bash
AUTH_ENABLED=true
AUTH_PASSWORD_HASH='$2y$10$Xa9....(the hash from step 1)....'
AUTH_TOKEN_TTL_DAYS=30           # how long a login lasts
```

**3 — Restart the app** (or redeploy). Open the UI and log in with the *plaintext* password
from step 1. To change the password later, regenerate the hash and restart. `htpasswd`'s
`$2y$` hashes are accepted (Spring's `BCryptPasswordEncoder` handles `$2a/$2b/$2y`).

> Set `AUTH_ENABLED=false` only for local dev — it opens every endpoint. In production keep it
> `true` and always set a hash (a blank hash while enabled is **fail-closed**: all requests are
> rejected, so a misconfig can never leave the app open).

**How it works:**
- The UI shows a password page until you log in. `POST /api/auth/login` checks the password
  against the bcrypt hash and returns a stateless, HMAC-signed bearer token (signed with a key
  derived from the hash, so a token survives a restart).
- The token is kept in the browser's `localStorage` and sent on every request. It is valid for
  `AUTH_TOKEN_TTL_DAYS` (default **30 days**) — enforced both client-side and server-side.
- **Log out** (footer button) clears the stored token, so the app immediately re-locks.
- **Fail-closed:** if `AUTH_ENABLED=true` but no hash is set, *every* request is rejected — a
  missing hash can never leave the app accidentally open.

> The password is set only via its bcrypt hash in the git-ignored `.env` — never in the
> source or in git. Generate the hash as above and keep the plaintext to yourself.

---

## UI

- **Live Power Flow** hero: Solar → Home → Grid with animated connectors, and a side panel with a **self-sufficiency ring** and the **surplus margin** (`solar − house`). House consumption is measured by Solar Analytics and updates live (with a sparkline). When Solar Analytics isn't reporting, house and the margin show as **unavailable** (no assumed value).
- **KPI row:** Today / Lifetime yield, grid frequency, inverter temperature. These promoted values are **not repeated** in the detail sections below (de-duplicated).
- **History chart** (under the KPIs): interactive D3 trend of solar / house / miner power over **24h / 7d / 30d** with hover readouts and markers at every autopilot power change — see [History & trends chart](#history--trends-chart).
- **Miner card:** honest state — **Mining / Suspended / Stopped / Offline** with reason (e.g. "no active pool"), live hashrate, power draw, **fan RPM**, uptime, pool count, editable **power target** + Apply, and **Start/Stop**.
- **Inverter detail sections:** Energy, Power, Grid & AC, DC/PV, Device Status, Per-phase — every reading with an info tooltip (excludes the values already shown as KPIs / in the hero).
- **Autopilot card:** On/Off badge, an Enable/Disable button, the last decision, and the last change it made (action, from→to power, time) — live over SSE.
- **Login gate:** a password page guards the whole app; a successful login is remembered for a month. The **footer** has a **Log out** button that clears it.
- **Footer:** app version, start time, and live uptime (from `/api/system`).
- Theme-aware (light/dark), responsive.

---

## Smoothed solar-surplus autopilot

### In plain terms

The goal: **run the miner on spare solar you'd otherwise export, and never pay the grid to mine.**

Think of the miner's power as a dial with fixed notches — `1200, 1600, 2000, … 3600 W` (the *ladder*). The autopilot's whole job is to keep that dial at the highest notch your **spare solar** can pay for, leaving a small safety buffer so you never import.

"Spare solar" = how much the sun is making minus what the *rest of the house* is using (fridge, aircon, etc.). Solar Analytics measures the whole house — miner included — so the autopilot adds the miner's own draw back to work out the house-without-the-miner figure, and that's what's genuinely free for mining.

Because clouds come and go, it doesn't react to the instantaneous number — it looks at **averages**:

- **Turning up (or on) is slow and cautious.** It uses a **15-minute average** and only nudges up once that average has clearly been high for a while — at most **2 notches at a time, once every ~15 minutes**. Ramping gently avoids thermal shock to the miner and stops it chasing a sunny patch that won't last.
- **Turning down (or off) is fast.** It watches a **3-minute average**, and if a real drop means the miner is now pulling more than the spare solar, it drops however many notches it takes — in one go. If it's pulling *a lot* more (≥ 800 W over), it reacts immediately instead of waiting.
- **In between it just holds.** A little 50–100 W wobble never moves the dial, because the notches are 400 W apart — that spacing *is* the deadband.
- **Start/stop don't flap.** It only starts when the 15-min spare is ≥ 1600 W, but keeps running down to a lower floor — so it can't rapidly toggle on/off around one threshold.
- **If it can't see, it stops.** Inverter offline (night), the meter stops reporting, or a poller stalls → the true surplus is unknown → it safely stops a running miner rather than guess. (Right after a restart it *holds* a healthy miner for a minute while its averages fill, instead of stopping it.)

Net effect: on a clear day it walks the miner up to full power and back down as the sun fades, ignores passing clouds, and the moment the maths says you'd import, it backs off — dropping straight to a safe notch (or off) in a single step.

The knobs (all in `.env`): `FLOOR_W`, `STEP_W`, `HEADROOM_W` (the buffer), `START_SURPLUS_W`, the two averaging windows and intervals, and `EMERGENCY_GAP_W`. Defaults are tuned for the S19k Pro; the rest of this section is the precise mechanism.

### The mechanism

Optional control loop (`io.dmitrykislov.miner.autopilot`, **disabled by default**) that soaks up surplus solar by driving the miner across a fixed **power ladder** (`FLOOR_W .. MINER_MAX_POWER_W` by `STEP_W`, e.g. `1200, 1600, … 3600`). Rather than reacting to instantaneous readings, it tracks the **time-averaged** surplus so brief clouds are ridden through. Every `AUTOPILOT_INTERVAL_MS` (30 s) it reads the miner state and the averaged surplus, then decides via a pure, exhaustively-tested governor.

> **Assumption:** the miner is part of the Solar-Analytics-monitored home, so its draw is included in the measured house consumption. The **available surplus** a running miner can draw is therefore `avg(solar − house) + its own current draw` (= solar − base-house load, which is miner-independent). If the miner is on a separate supply, that assumption breaks — verify before enabling.

**The engine** (three pure, clock-injected pieces):
- **`RollingWindow`** — a time-bounded rolling **mean** of samples (gap-robust: a simple mean degrades gracefully across missed polls, where a step-hold average would carry a stale pre-gap value forward and over-estimate surplus). Reports empty when stale or too sparse.
- **`EnergyAverages`** — maintains solar and consumption windows and exposes the averaged **surplus** over a short (3 min) and a long (15 min) window, plus a freshness flag. A `EnergySampler` feeds it from each inverter snapshot.
- **`AutopilotGovernor`** — the ladder controller. Deliberately **asymmetric**: ramps **up** slowly (long window, 15 min dampening, ≤ 2 rungs/move) only on well-established surplus; steps **down / stops** fast (short window, 5 min interval, uncapped, with an emergency bypass) to protect against import.

Decision model (surplus `S`; target = highest ladder rung ≤ `S − headroom`):
- **Off & long-window surplus ≥ `START_SURPLUS_W`** (1600) → **start** at the floor. Start/stop **hysteresis** (start 1600, stop below floor+headroom) prevents flapping.
- **Mining & sustained surplus supports a higher rung** → **step up** (≤ 2 rungs/move, no more than once per up-interval), but only after mining ≥ the long window so the average is valid.
- **Mining & short-window surplus dropped** → **step down** to the rung the surplus supports (uncapped — can drop several rungs at once); if the over-draw ≥ `EMERGENCY_GAP_W` (800) it bypasses the down interval; if even the floor can't be held → **stop**.
- **Otherwise** → hold. Rung quantization is the deadband: a 50–100 W wobble never triggers a change.

Safety/correctness properties:
- **The running power never exceeds the available surplus** — because `headroom > 0`, the target is always strictly below the surplus. On a sudden collapse (say 3600 W and the 15-min surplus falls to 1800 W) the governor steps straight down to the highest rung the surplus holds (1600 W) in one move, never leaving the miner importing.
- **Stops the miner when the surplus is unknown** — if solar is unavailable (inverter offline, e.g. at night), consumption is unavailable (Solar Analytics stale/offline/gated), the inverter reading is stale (poller stalled), *or* the rolling windows themselves have gone stale, the surplus can't be trusted. The safe fallback is to stop. A **stale feed** (→ stop a running miner) is distinguished from a merely **sparse window** right after boot (→ hold, don't disrupt a healthy miner before the window fills).
- **Always uses live miner state** — each tick reads a fresh miner status (never cached), and every mutating op (start / step / stop) re-verifies the state immediately before acting.
- Skips `SUSPENDED` — a suspended miner (e.g. dead pools) draws ~0 W, so it isn't reflected in the surplus and there's nothing to protect against; the autopilot leaves it alone.
- Respects the miner's hard **[min, max]** power limits (also enforced in `MinerService` on every set).
- **Safe-by-construction config:** the governor validates its config at boot and refuses to start on any setting that would break an invariant — e.g. `start-surplus > floor` (hysteresis), `up-interval ≥ long-window` (a just-made change can't contaminate the average driving the next up-move), positive intervals/step, `floor ≥ miner-min` and `max > floor`.

The engine lives in `RollingWindow` / `EnergyAverages` / `AutopilotGovernor` (all pure, no I/O); `MinerAutopilot` wires them to the live feed and `MinerService`, and `EnergySampler` feeds the windows.

**Runtime control:** `AUTOPILOT_ENABLED` now sets the **boot state** of a runtime toggle (and still gates the boot-time config guards). Once running, the UI has an **Autopilot** card — an Enable/Disable button plus live status: whether it's on, the last decision (including "hold"/skips), and the last change it actually made (action, from→to power, time, reason). Backend: `GET /api/autopilot` (+ `/stream` SSE) and `POST /api/autopilot/enable|disable`. The env var only seeds the starting position; the toggle overrides it at runtime.

---

## History & trends chart

A lightweight, **file-backed** telemetry log feeds an interactive **D3 chart** under the KPI row, so you can see how solar, house load and miner power moved together and exactly when the autopilot changed things.

- **What's recorded** (`io.dmitrykislov.miner.history`): once a minute the `TelemetryRecorder` appends one sample — solar (W), house consumption (W), miner power target + live draw (W), miner state — read from the same in-memory snapshots the SSE streams already serve (no extra device I/O). Every autopilot power change is recorded as a discrete **event** (action, from→to, reason).
- **Storage is deliberately tiny** — no database. Append-only text files, one per UTC day, under `HISTORY_DIR` (default `data/history`): `samples-YYYY-MM-DD.log` (comma-separated) and `events-YYYY-MM-DD.log` (tab-separated). A month at one sample/minute is ~5 MB on disk and a few MB in heap — fine for a Raspberry Pi. Files (and in-memory rows) older than `HISTORY_RETENTION_DAYS` (default **31**) are discarded automatically; the store reloads on restart.
- **The chart** (`GET /api/history?hours=`): pick **24h / 7d / 30d**. Three lines — Solar, Home, Miner — with the miner line breaking to a gap when it's off. Server-side **downsampling** caps each request to ~1500 points so even a month draws smoothly. **Hover the plot** for a readout at that instant (solar / home / miner / surplus); **hover a change marker** (the dashed verticals) to see the autopilot action, the from→to power, and the reason. It refreshes every minute.
- **Turn it off** with `HISTORY_ENABLED=false` (nothing is recorded and the chart shows an empty state).

## Notable device details

- **Sungrow SG10RS / WiNet-S** — real-time data comes from the dongle's local WebSocket API (`wss://…/ws/home/overview`): `connect` → `login` → `devicelist` → `real`/`direct`. (Modbus TCP :502 exists but is firewalled while you're on the dongle's own WiFi AP.) The dongle's self-signed cert has no SAN, so hostname verification is disabled for these LAN clients (set once in `main()`).
- **Solar Analytics** — polls `GET /api/v3/live_site_data` (HTTP Basic auth with the account email/password) every ~15 s and reads `consumed` (watts) as whole-home consumption. Their CT hardware measures the load directly, so — unlike the SG10RS, which has no energy meter — this yields true house consumption. Margin = `solar − consumed` (see `PowerBalance`). The poll is **gated on live solar**: it only calls the cloud API while the inverter is generating more than `SOLARANALYTICS_MIN_SOLAR_W` (default 800 W) — below that no surplus is possible, so the call is skipped and consumption is marked **unavailable** — the autopilot then treats the surplus as unknown and safely stops a running miner (rather than holding it on a now-stale reading that omits the miner's own draw, which would import from the grid). The gate reads the latest inverter snapshot (a lock-free in-memory value), so it never blocks the poll or introduces a dependency cycle.
- **Braiins OS+ miner** — GraphQL at `/graphql`. Declarative `@HttpExchange` client. `bosminer.start`/`stop` control the BOSMiner **service**; the miner only **hashes** ("Mining") when a live pool is connected — otherwise it self-pauses ("dead pools") and shows **Suspended**. Fans/hashrate are only reported while the service is up.
- **Fans ramp on every power change** because Braiins runs them in **automatic (target-temperature) mode** — more power ⇒ more heat ⇒ higher RPM (and vice-versa). Braiins also offers a **manual fixed-speed** mode (fans hold a set %) and an **immersion** mode, but manual mode disables thermal protection and is [not recommended](https://academy.braiins.com/os/plus-en/Configuration/index_configuration.html) unless set high (≈100%). The better mitigation is to change power **less often** — the autopilot's averaging windows, rung step, and up/down intervals already limit this; widening them (or raising the target temperature) reduces fan transients without losing thermal safety. There is no separate fan-throttle-on-change knob.

---

## Tests

```bash
mvn clean install     # 182 backend + 57 UI tests (UI tests run as part of the build)
cd backend && mvn test # backend only (182)
```

Covers config binding/defaults, power-balance math, i18n label mapping, DTO (Jackson 3) deserialization, snapshot mapping, the WebSocket client's frame correlation, all pollers/services (mocked clients), every controller (`@WebFluxTest`), the **autopilot engine** — `RollingWindow` (rolling mean, freshness, coverage, out-of-order samples), `EnergyAverages` (short/long surplus, stale-vs-sparse), and `AutopilotGovernor` (exhaustive start/step/stop, divergent short/long windows, emergency + stop boundaries, never-import property sweep, config guards) — the autopilot **orchestration** (live-state re-verification, feed validity), and an **end-to-end WireMock** test that boots the full Spring context and drives the real surplus chain against simulated devices (`MockMiner`, `MockSolarAnalytics`, `MockInverter`), asserting the exact mutations the autopilot sends. The **history** layer is covered too — the file-backed `TelemetryStore` (record/query, retention pruning of memory + day-files, restart persistence, line (de)serialization incl. corrupt-line skipping), the `TelemetryRecorder` (building a sample from the cached streams, once-only event capture), `Downsampling`, and the `HistoryController` (window clamping + downsampling); the React D3 chart has its own Vitest suite (series, event markers + hover detail, window switching, empty state). **Access control** is covered by unit tests (bcrypt verify, token issue/validate/expiry/tamper, fail-closed) and a full-context test of the filter + login flow. The React UI has its own Vitest suite (including the auth token helpers and the login/logout gate) that runs during `mvn install`.

---

## Resource usage

Bundled jar (backend + UI), idle at steady state, running with the Pi footprint caps
(`JAVA_OPTS` from `.env.example`: `-Xmx128m` + SerialGC + capped metaspace/stacks +
`TieredStopAtLevel=1` + lazy-init):

| Metric | Value |
|---|---|
| Resident memory (RSS) | **~140 MB** (measured on the Pi) |
| JVM heap | 128 MB cap; only a few MB live at idle (SerialGC, 1 GC thread) |
| CPU (idle) | ~0% |
| Jar size | **~22 MB** (HTTP/3 QUIC native libs excluded) |
| Startup | ~1–2 s on a laptop; ~25 s on a 416 MB Pi (weak CPU + lazy-init) |

Without those caps the JVM's default ergonomic heap + startup JIT spike can OOM a 416 MB Pi;
the `JAVA_OPTS` in `.env.example` keep it at ~140 MB RSS with ~140 MB headroom. See
[Configuration](#configuration-env).

---

## Limitations

- **Miner needs a live pool to actually mine.** With no reachable pool configured, BOSMiner starts but sits **Suspended** ("dead pools"). There is no API to force hashing without a pool. On an isolated IoT network the miner also can't resolve `stratum.braiins.com` (no DNS/internet), which keeps pools "dead".
- Several devices are cloud/internet-isolated on the LAN (Eero), which is why cloud paths and pool/NTP connectivity fail — a network concern, not an app one.
- **No TLS.** The UI and login are served over plain HTTP, so the password crosses the network in cleartext — fine on a trusted LAN, but put it behind a TLS reverse proxy if you expose it beyond your network.
- **No auto-start on boot.** `start.sh`/`deploy.sh` launch the jar detached (not a systemd service), so it won't survive a device reboot. Add a small `systemd` unit (running `java $JAVA_OPTS -jar …` with `Restart=always`) if you need it to come back automatically.

---

## License

Released under the [MIT License](LICENSE) — © 2026 Dmitry Kislov. Do anything you like with it; just keep the copyright notice.

**Not affiliated with, endorsed by, or sponsored by** Sungrow, Solar Analytics, Braiins, or Bitmain. All product names, trademarks, and device protocols are the property of their respective owners; this project only interoperates with their local APIs and covers its own source code.
