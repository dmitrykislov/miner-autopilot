#!/usr/bin/env bash
#
# Build the miner-controller jar locally and deploy it to the Raspberry Pi over SSH.
#
# Pipeline: build (Maven, incl. UI + tests) → checksum → scp to a staged path →
# verify checksum on the Pi → stop the old app → FREE THE PORT → swap the jar
# (keeping a .bak) → start detached → health-check → roll back on failure.
#
# Resilience:
#   • Frees SERVER_PORT by killing whatever LISTENs on it (not just our process),
#     so a stale/orphan process can't block the new jar from binding.
#   • Verifies the jar's sha256 after transfer before swapping it in.
#   • Keeps the previous jar as <jar>.bak and auto-rolls-back + restarts it if the
#     new build fails to become healthy — a bad deploy never leaves the Pi down.
#
# The remote .env is NOT modified — device config, ports, and AUTOPILOT_ENABLED are
# managed there. Connection settings come from the (git-ignored) local .env or env:
#
#   DEPLOY_HOST   remote host/IP           (required)
#   DEPLOY_USER   remote SSH user          (required)
#   DEPLOY_PORT   SSH port                 (default 22)
#   DEPLOY_KEY    path to the SSH key      (default ./deploy_key.pem)
#   DEPLOY_DIR    remote dir with jar+.env (default /home/$DEPLOY_USER)
#   SKIP_TESTS=1                           build with -DskipTests (faster)
#
# No host/IP/user/key is baked in — put them in .env (git-ignored):
#   DEPLOY_HOST=1.2.3.4
#   DEPLOY_USER=youruser
#   DEPLOY_PORT=22
#   DEPLOY_KEY=/path/to/key.pem
#
# Usage:  ./deploy.sh              # full build + deploy
#         SKIP_TESTS=1 ./deploy.sh
set -euo pipefail

# --- resolve paths relative to this script (so it works from any CWD) ------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load connection settings from .env if present (keeps host/IP/user out of git).
if [[ -f "$SCRIPT_DIR/.env" ]]; then set -a; . "$SCRIPT_DIR/.env"; set +a; fi

HOST="${DEPLOY_HOST:?DEPLOY_HOST not set — add it to .env or pass DEPLOY_HOST=...}"
USER="${DEPLOY_USER:?DEPLOY_USER not set — add it to .env or pass DEPLOY_USER=...}"
PORT="${DEPLOY_PORT:-22}"
KEY="${DEPLOY_KEY:-$SCRIPT_DIR/deploy_key.pem}"
DIR="${DEPLOY_DIR:-/home/$USER}"

log() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f "$KEY" ]] || die "SSH key not found: $KEY (set DEPLOY_KEY in .env)"

# SSH refuses a world-/group-readable private key; tighten it rather than failing cryptically.
KEY_PERMS="$(stat -f '%Lp' "$KEY" 2>/dev/null || stat -c '%a' "$KEY" 2>/dev/null || echo '')"
if [[ -n "$KEY_PERMS" && "$KEY_PERMS" != "600" && "$KEY_PERMS" != "400" ]]; then
  log "Tightening permissions on $KEY (was $KEY_PERMS) → 600"
  chmod 600 "$KEY"
fi

SSH_OPTS=(-i "$KEY" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=20
          -o ServerAliveInterval=15 -o ServerAliveCountMax=4
          -o StrictHostKeyChecking=accept-new)
SCP_OPTS=(-i "$KEY" -P "$PORT" -o BatchMode=yes -o ConnectTimeout=20
          -o StrictHostKeyChecking=accept-new)

# --- 0. preflight: can we reach the Pi at all? -----------------------------------
log "Checking SSH connectivity to $USER@$HOST:$PORT"
ssh "${SSH_OPTS[@]}" "$USER@$HOST" 'echo ok >/dev/null' \
  || die "cannot SSH to $USER@$HOST:$PORT — check DEPLOY_HOST/USER/PORT/KEY and that the Pi is up"

# --- 1. build --------------------------------------------------------------------
log "Building jar (mvn clean package${SKIP_TESTS:+, skipping tests})"
MVN_ARGS=(-q clean package)
[[ "${SKIP_TESTS:-0}" == "1" ]] && MVN_ARGS+=(-DskipTests)
( cd backend && mvn "${MVN_ARGS[@]}" )

JAR="$(ls -t backend/launcher/target/miner-controller-launcher-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
[[ -n "$JAR" && -f "$JAR" ]] || die "build produced no jar under backend/launcher/target"
JAR_NAME="$(basename "$JAR")"

# sha256 for an integrity check after transfer (macOS: shasum, Linux: sha256sum).
if command -v sha256sum >/dev/null 2>&1; then
  JAR_SHA="$(sha256sum "$JAR" | awk '{print $1}')"
else
  JAR_SHA="$(shasum -a 256 "$JAR" | awk '{print $1}')"
fi
log "Built $JAR_NAME ($(du -h "$JAR" | cut -f1), sha256 ${JAR_SHA:0:12}…)"

# --- 2. stage the jar on the Pi (old one keeps running until we swap) -------------
log "Copying to $USER@$HOST:$DIR/$JAR_NAME.new"
scp "${SCP_OPTS[@]}" "$JAR" "$USER@$HOST:$DIR/$JAR_NAME.new"

# --- 3. remote: verify → stop → free port → swap → start → verify (→ rollback) ---
# Args are passed positionally (safe for spaces); the process pattern is defined
# remotely. The bracket in the pattern keeps pgrep/pkill from matching this session.
log "Deploying on the Pi (stop old · free port · swap · start · health-check)"
ssh "${SSH_OPTS[@]}" "$USER@$HOST" bash -s -- "$JAR_NAME" "$DIR" "$JAR_SHA" <<'REMOTE'
set -euo pipefail
JAR_NAME="$1"; DIR="$2"; JAR_SHA="$3"
PAT='[j]ava .*-jar .*miner-controller-launcher'   # matches our app, not this session
cd "$DIR"

# Load remote config (ports, AUTOPILOT_ENABLED, optional JAVA_OPTS) from the Pi's .env.
if [[ -f .env ]]; then set -a; . ./.env; set +a; else echo "  ⚠ no .env — using built-in defaults"; fi
APP_PORT="${SERVER_PORT:-8080}"
# When TLS is enabled the app serves HTTPS, so the health probe must use https and skip
# cert verification (the self-signed cert isn't in the box's trust store). Otherwise http.
if [[ "${TLS_ENABLED:-false}" == "true" ]]; then
  SCHEME="https"; CURL_K="-k"; WGET_K="--no-check-certificate"
else
  SCHEME="http"; CURL_K=""; WGET_K=""
fi

# --- preflight ---
command -v java >/dev/null 2>&1 || { echo "  ✗ java not found on PATH"; exit 1; }
[[ -s "$JAR_NAME.new" ]] || { echo "  ✗ staged jar missing or empty"; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
  got="$(sha256sum "$JAR_NAME.new" | awk '{print $1}')"
  [[ "$got" == "$JAR_SHA" ]] || { echo "  ✗ checksum mismatch (got ${got:0:12}… want ${JAR_SHA:0:12}…) — transfer corrupt"; exit 1; }
  echo "  checksum OK (${got:0:12}…)"
fi

# --- helpers ---
pids_on_port() {                                   # PIDs LISTENing on $1 (ss→lsof→fuser)
  local p="$1" out=""
  if command -v ss >/dev/null 2>&1; then
    out="$(ss -ltnp 2>/dev/null | grep -E ":$p([^0-9]|$)" | grep -oE 'pid=[0-9]+' | cut -d= -f2 || true)"
  fi
  if [[ -z "$out" ]] && command -v lsof >/dev/null 2>&1; then
    out="$(lsof -ti "tcp:$p" -sTCP:LISTEN 2>/dev/null || true)"
  fi
  if [[ -z "$out" ]] && command -v fuser >/dev/null 2>&1; then
    out="$(fuser "$p/tcp" 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$' || true)"
  fi
  echo "$out" | grep -E '^[0-9]+$' | sort -u || true
}

stop_app() {                                       # graceful SIGTERM to our jar, then SIGKILL
  local pid; pid="$(pgrep -f "$PAT" || true)"
  if [[ -n "$pid" ]]; then
    echo "  stopping app PID(s): $(echo $pid | tr '\n' ' ')"
    kill $pid 2>/dev/null || true
    for _ in $(seq 1 20); do pgrep -f "$PAT" >/dev/null || break; sleep 1; done
    if pgrep -f "$PAT" >/dev/null; then echo "  graceful timeout → SIGKILL"; pkill -9 -f "$PAT" || true; sleep 2; fi
  else
    echo "  (app not running)"
  fi
}

free_port() {                                      # ensure nothing holds $1
  local p="$1" pids
  pids="$(pids_on_port "$p")"
  if [[ -z "$pids" ]]; then echo "  port $p already free"; return 0; fi
  echo "  port $p held by PID(s): $(echo $pids | tr '\n' ' ')— terminating"
  kill $pids 2>/dev/null || true
  for _ in $(seq 1 15); do [[ -z "$(pids_on_port "$p")" ]] && break; sleep 1; done
  pids="$(pids_on_port "$p")"
  if [[ -n "$pids" ]]; then echo "  force-killing $(echo $pids | tr '\n' ' ')"; kill -9 $pids 2>/dev/null || true; sleep 2; fi
  [[ -z "$(pids_on_port "$p")" ]] || { echo "  ✗ could not free port $p"; return 1; }
  echo "  port $p is free"
}

start_app() {                                      # start detached; rotate one previous log
  [[ -f miner-controller.log ]] && mv -f miner-controller.log miner-controller.log.1
  # JAVA_OPTS (optional, from .env) is intentionally word-split for multiple flags.
  setsid java ${JAVA_OPTS:-} -jar "$JAR_NAME" < /dev/null > miner-controller.log 2>&1 &
  echo "  started (autopilot enabled = ${AUTOPILOT_ENABLED:-<default:false>}, JAVA_OPTS='${JAVA_OPTS:-}')"
}

healthy() {                                        # 0 if the app is serving within the timeout
  local p="$1" code
  for _ in $(seq 1 60); do
    if command -v curl >/dev/null 2>&1; then
      # --max-time so a thrashing/half-up app can never hang the probe (and the deploy).
      code="$(curl -s $CURL_K --max-time 5 -o /dev/null -w '%{http_code}' "$SCHEME://localhost:$p/api/system" 2>/dev/null || true)"
    elif command -v wget >/dev/null 2>&1; then
      code="$(wget -q $WGET_K -T 5 -t 1 -O /dev/null -S "$SCHEME://localhost:$p/api/system" 2>&1 | awk '/HTTP\//{print $2; exit}' || true)"
    else
      (exec 3<>"/dev/tcp/localhost/$p") 2>/dev/null && { exec 3>&- 3<&-; code=200; } || code=000
    fi
    # 200 = serving; 401 = serving AND the access-control filter is active (auth enabled).
    # Both mean the app is up; only a connection failure (000) means "not yet / down".
    [[ "$code" == "200" || "$code" == "401" ]] && return 0
    grep -qiE "APPLICATION FAILED TO START|Error starting|Web server failed to start" miner-controller.log 2>/dev/null && return 1
    sleep 3
  done
  return 1
}

# --- stop the old app and clear the port -----------------------------------------
stop_app
free_port "$APP_PORT" || { echo "  ✗ aborting: port $APP_PORT still occupied (staged jar left as $JAR_NAME.new)"; exit 1; }

# --- swap in the new jar, keeping the old one for rollback ------------------------
if [[ -f "$JAR_NAME" ]]; then cp -p "$JAR_NAME" "$JAR_NAME.bak"; fi
mv -f "$JAR_NAME.new" "$JAR_NAME"
echo "  installed $(ls -la "$JAR_NAME" | awk '{print $5, $NF}')"

# --- start and verify; roll back to the previous jar if it won't come up ---------
start_app
if healthy "$APP_PORT"; then
  code="$(curl -s $CURL_K --max-time 5 -o /dev/null -w '%{http_code}' "$SCHEME://localhost:$APP_PORT/api/system" 2>/dev/null || echo '?')"
  echo "  ✓ healthy on :$APP_PORT ($SCHEME /api/system → HTTP $code${code:+; 401 = auth active})"
  grep -E "Started MinerControllerApplication" miner-controller.log | tail -1 || true
else
  echo "  ✗ new jar did not become healthy — rolling back"; tail -20 miner-controller.log || true
  stop_app; free_port "$APP_PORT" || true
  if [[ -f "$JAR_NAME.bak" ]]; then
    mv -f "$JAR_NAME.bak" "$JAR_NAME"
    start_app
    if healthy "$APP_PORT"; then echo "  ✓ rolled back to previous jar, healthy on :$APP_PORT"; else echo "  ✗ ROLLBACK ALSO UNHEALTHY — manual intervention needed"; fi
  else
    echo "  ✗ no .bak to roll back to — app is DOWN"
  fi
  exit 1
fi
REMOTE

log "Deploy complete → http(s)://$HOST:<SERVER_PORT>/"
