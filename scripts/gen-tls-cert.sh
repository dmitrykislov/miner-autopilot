#!/usr/bin/env bash
#
# gen-tls-cert.sh — generate a self-signed TLS certificate + key for the miner-controller.
#
# For LAN / home use. Browsers show a one-time "not trusted" warning for a self-signed
# cert; to avoid it on your own devices, generate with `mkcert` instead and install its
# local CA — the app config is identical (same TLS_CERT / TLS_KEY files).
#
# Usage:
#   ./scripts/gen-tls-cert.sh [OUT_DIR] [EXTRA_HOST ...]
#     OUT_DIR      where to write cert.pem + key.pem      (default: ./certs)
#     EXTRA_HOST   extra DNS name or IP for the SAN list  (repeatable)
#
# Examples:
#   ./scripts/gen-tls-cert.sh                              # localhost + this host's IP
#   ./scripts/gen-tls-cert.sh certs 192.168.4.134 pi.local # + a fixed IP and hostname
#
# The generated key.pem is a secret and is git-ignored (*.pem). Point TLS_CERT / TLS_KEY
# in .env at these files and set TLS_ENABLED=true. See README → "HTTPS / TLS".
#
set -euo pipefail
OUT="${1:-certs}"
[[ $# -gt 0 ]] && shift

command -v openssl >/dev/null 2>&1 || { echo "✖ openssl not found on PATH"; exit 1; }

# Subject Alternative Names: always localhost + loopback, plus this host's primary IP,
# plus any DNS names / IPs passed as extra args. (A cert is only valid for the names in
# its SAN, so include every address you'll reach the box by.)
lan_ip="$( { hostname -I 2>/dev/null || ipconfig getifaddr en0 2>/dev/null; } | awk '{print $1}' )"
sans=("DNS:localhost" "IP:127.0.0.1")
[[ -n "${lan_ip:-}" ]] && sans+=("IP:$lan_ip")
for h in "$@"; do
  if [[ "$h" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then sans+=("IP:$h"); else sans+=("DNS:$h"); fi
done
san_csv="$(IFS=,; echo "${sans[*]}")"

mkdir -p "$OUT"
# EC P-256 key: small, fast, widely supported; -nodes = unencrypted key (the app needs
# to read it unattended); 10-year validity since it's a self-managed LAN cert.
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout "$OUT/key.pem" -out "$OUT/cert.pem" -days 3650 \
  -subj "/CN=miner-controller" -addext "subjectAltName=${san_csv}"
chmod 600 "$OUT/key.pem"

echo "✔ wrote $OUT/cert.pem and $OUT/key.pem"
echo "  SAN: ${san_csv}"
echo "  next: set TLS_ENABLED=true and point TLS_CERT/TLS_KEY at these files in .env"
