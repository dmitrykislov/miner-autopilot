#!/usr/bin/env bash
# Manually run the pre-built jar on the Pi, loading configuration from .env in this
# dir. deploy.sh handles normal deploys (stop/port-free/swap/health-check); this is
# a simple foreground fallback for debugging on the Pi itself.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env ]]; then
  set -a           # export everything sourced
  # shellcheck disable=SC1091
  source .env
  set +a
else
  echo "⚠ no .env next to the jar — using built-in defaults"
fi

# Resolve the jar by glob so a version bump doesn't require editing this script.
JAR="$(ls -t miner-controller-launcher-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
[[ -n "$JAR" && -f "$JAR" ]] || { echo "✖ no miner-controller-launcher-*.jar found in $(pwd)"; exit 1; }

echo "▶ running $JAR on :${SERVER_PORT:-8080}"
# JAVA_OPTS (optional, from .env) is intentionally word-split for multiple flags.
exec java ${JAVA_OPTS:-} -jar "$JAR"
