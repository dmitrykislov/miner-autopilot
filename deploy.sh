#!/usr/bin/env bash
#
# Build the miner-controller jar locally and deploy it to the Raspberry Pi over SSH.
#
# Steps: build (Maven, incl. UI + tests) → scp the jar to a staged path → stop the
# running app → swap in the new jar → start it detached → wait for it to serve.
#
# The remote .env is NOT modified — device config and AUTOPILOT_ENABLED are managed
# there. Connection settings come from the (git-ignored) .env, or the environment:
#
#   DEPLOY_HOST   remote host/IP           (required)
#   DEPLOY_USER   remote SSH user          (required)
#   DEPLOY_PORT   SSH port                 (default 22)
#   DEPLOY_KEY    path to the SSH key      (default ./deploy_key.pem)
#   DEPLOY_DIR    remote dir with jar+.env (default /home/$DEPLOY_USER)
#   SKIP_TESTS=1                           build with -DskipTests (faster)
#
# No host/IP/user is baked into this script — put them in .env (which is git-ignored):
#   DEPLOY_HOST=1.2.3.4
#   DEPLOY_USER=youruser
#   DEPLOY_PORT=22
#   DEPLOY_KEY=/path/to/key.pem
#
# Usage:  ./deploy.sh            # full build + deploy
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

SSH_OPTS=(-i "$KEY" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new)
SCP_OPTS=(-i "$KEY" -P "$PORT" -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=accept-new)

log() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f "$KEY" ]] || die "SSH key not found: $KEY"

# --- 1. build --------------------------------------------------------------------
log "Building jar (mvn clean package${SKIP_TESTS:+, skipping tests})"
MVN_ARGS=(-q clean package)
[[ "${SKIP_TESTS:-0}" == "1" ]] && MVN_ARGS+=(-DskipTests)
( cd backend && mvn "${MVN_ARGS[@]}" )

JAR="$(ls -t backend/target/miner-controller-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
[[ -n "$JAR" && -f "$JAR" ]] || die "build produced no jar under backend/target"
JAR_NAME="$(basename "$JAR")"
log "Built $JAR_NAME ($(du -h "$JAR" | cut -f1))"

# --- 2. stage the jar on the Pi (old one keeps running until we swap) -------------
log "Copying to $USER@$HOST:$DIR/$JAR_NAME.new"
scp "${SCP_OPTS[@]}" "$JAR" "$USER@$HOST:$DIR/$JAR_NAME.new"

# --- 3. stop → swap → start → verify (single remote session) ---------------------
# Args are passed positionally (safe for spaces); the process pattern is defined
# remotely. The bracket in the pattern keeps pgrep/pkill from matching this session.
log "Stopping old app, swapping jar, and starting the new one"
ssh "${SSH_OPTS[@]}" "$USER@$HOST" bash -s -- "$JAR_NAME" "$DIR" <<'REMOTE'
set -euo pipefail
JAR_NAME="$1"
DIR="$2"
PAT='[j]ava -jar miner-controller-backend'
cd "$DIR"

# stop (graceful, then force)
pid="$(pgrep -f "$PAT" || true)"
if [[ -n "$pid" ]]; then
  echo "  stopping PID $pid"
  kill "$pid" || true
  for _ in $(seq 1 20); do pgrep -f "$PAT" >/dev/null || break; sleep 1; done
  if pgrep -f "$PAT" >/dev/null; then echo "  graceful timeout → SIGKILL"; pkill -9 -f "$PAT" || true; sleep 2; fi
else
  echo "  (nothing running)"
fi

# swap in the new jar
[[ -f "$JAR_NAME.new" ]] || { echo "staged jar missing!" >&2; exit 1; }
rm -f "$JAR_NAME"
mv "$JAR_NAME.new" "$JAR_NAME"
echo "  installed $(ls -la "$JAR_NAME" | awk '{print $5, $NF}')"

# start detached, loading env from .env (AUTOPILOT_ENABLED etc. come from there)
[[ -f .env ]] && { set -a; . ./.env; set +a; } || echo "  ⚠ no .env — using built-in defaults"
setsid java -jar "$JAR_NAME" < /dev/null > miner-controller.log 2>&1 &
echo "  started; autopilot enabled = ${AUTOPILOT_ENABLED:-<default:false>}"

# wait until it serves (or the app reports a startup failure)
port="${SERVER_PORT:-8080}"
for _ in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$port/api/system" 2>/dev/null || true)"
  [[ "$code" == "200" ]] && break
  grep -qiE "APPLICATION FAILED TO START|Error starting" miner-controller.log 2>/dev/null && { echo "  STARTUP FAILED"; tail -20 miner-controller.log; exit 1; }
  sleep 3
done
[[ "${code:-}" == "200" ]] || { echo "  did not become healthy in time"; tail -20 miner-controller.log; exit 1; }

echo "  /api/system → $(curl -s "http://localhost:$port/api/system")"
grep -E "Started MinerControllerApplication" miner-controller.log | tail -1
REMOTE

log "Deploy complete → http://$HOST:<SERVER_PORT>/"
