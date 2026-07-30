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

find_jar() { ls -t autopilot-launcher/target/*.jar 2>/dev/null | grep -v 'original' | head -1; }

build() {
  require node; require npm; require mvn; require java
  echo "▶ building React UI + packaging Spring Boot jar (UI bundled inside)"
  rm -rf autopilot-launcher/src/main/resources/static   # avoid stale bundles accumulating
  # The Maven build already builds the UI (exec-maven-plugin: npm ci + vite build, bound
  # to generate-resources), so the UI is built exactly once here — no separate npm step.
  mvn -q -DskipTests clean package   # reactor root; clean drops stale bundled assets
  local jar; jar="$(find_jar)"
  echo "✔ built: $jar ($(du -h "$jar" | cut -f1))"
}

# TLS is on by default; if it's enabled but no cert exists yet, generate a self-signed one so a
# fresh checkout serves HTTPS without manual setup. Set TLS_ENABLED=false in .env for plain HTTP.
ensure_tls_cert() {
  [[ "${TLS_ENABLED:-true}" == "false" ]] && return 0
  local cert="${TLS_CERT:-file:certs/cert.pem}"; cert="${cert#file:}"
  if [[ ! -f "$cert" ]]; then
    echo "▶ TLS is on but no cert at $cert — generating a self-signed one (LAN use)"
    ./scripts/gen-tls-cert.sh
  fi
}

scheme() { [[ "${TLS_ENABLED:-true}" == "false" ]] && echo http || echo https; }

run() {
  require java
  local jar; jar="$(find_jar)"
  [[ -n "$jar" ]] || { echo "✖ no jar found — run './start.sh --build' first"; exit 1; }
  ensure_tls_cert
  echo "▶ starting on $(scheme)://localhost:${PORT}  (inverter=${INVERTER_HOST:-?} miner=${MINER_HOST:-?})"
  exec java -jar "$jar"
}

run_dev() {
  require node; require npm; require mvn
  ensure_tls_cert
  echo "▶ dev mode: backend :${PORT} ($(scheme)) + Vite :${FRONTEND_PORT:-5173}"
  mvn -q -DskipUi=true -pl autopilot-launcher -am spring-boot:run &  BACK=$!
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
