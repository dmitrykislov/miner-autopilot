#!/usr/bin/env bash
# Run the pre-built jar on the Pi, loading configuration from .env in this dir.
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
exec java -jar miner-controller-backend-1.0-SNAPSHOT.jar
