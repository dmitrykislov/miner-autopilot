#!/usr/bin/env bash
#
# start.sh — build & run the House Energy Monitor (Sungrow SG10RS + Solar Analytics
#            + Braiins miner) as ONE self-contained Spring Boot jar with the
#            React UI bundled inside, configured entirely from .env.
#
# Usage:
#   ./start.sh            Build UI + backend into the jar, then run it (default).
#   ./start.sh --build    Build only — produce the runnable jar, don't start.
#   ./start.sh --run      Run the already-built jar (no rebuild).
#   ./start.sh --dev      Dev mode: backend (mvn spring-boot:run) + Vite (:5173).
#
# Everything is env-driven: values come from .env (see .env.example) and are read
# by application.yml ${PLACEHOLDER} bindings. The jar serves UI + REST + SSE from
# one process at http://localhost:${SERVER_PORT}.

set -euo pipefail
cd "$(dirname "$0")"

# --- load .env --------------------------------------------------------------
if [[ -f .env ]]; then
  echo "▶ loading .env"
  set -a; # shellcheck disable=SC1091
  source .env; set +a
else
  echo "⚠ no .env found — copy .env.example to .env and fill it in"
fi
PORT="${SERVER_PORT:-8080}"

# --- prerequisites ----------------------------------------------------------
require() { command -v "$1" >/dev/null 2>&1 || { echo "✖ '$1' not found on PATH"; exit 1; }; }

find_jar() { ls -t backend/target/*.jar 2>/dev/null | grep -v 'original' | head -1; }

build() {
  require node; require npm; require mvn; require java
  echo "▶ [1/2] building React UI → backend/src/main/resources/static"
  rm -rf backend/src/main/resources/static   # avoid stale bundles accumulating
  ( cd frontend && (npm ci || npm install) && npm run build )
  echo "▶ [2/2] packaging Spring Boot jar (UI bundled inside)"
  ( cd backend && mvn -q -DskipTests clean package )   # clean drops stale bundled assets
  local jar; jar="$(find_jar)"
  echo "✔ built: $jar ($(du -h "$jar" | cut -f1))"
}

run() {
  require java
  local jar; jar="$(find_jar)"
  [[ -n "$jar" ]] || { echo "✖ no jar found — run './start.sh --build' first"; exit 1; }
  echo "▶ starting on http://localhost:${PORT}  (inverter=${INVERTER_HOST:-?} miner=${MINER_HOST:-?})"
  exec java -jar "$jar"
}

run_dev() {
  require node; require npm; require mvn
  echo "▶ dev mode: backend :${PORT} + Vite :${FRONTEND_PORT:-5173}"
  ( cd backend && mvn -q spring-boot:run ) &  BACK=$!
  ( cd frontend && (npm ci || npm install) && npm run dev ) &  FRONT=$!
  trap 'echo; echo "▶ stopping"; kill $BACK $FRONT 2>/dev/null || true' INT TERM
  wait $BACK $FRONT
}

case "${1:-prod}" in
  --build|build) build; echo "▶ ready — start it with: ./start.sh --run" ;;
  --run|run)     run ;;
  --dev|dev)     run_dev ;;
  *)             build; run ;;
esac
