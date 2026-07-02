#!/usr/bin/env bash
#
# restart-helper.sh — restart the Nyora parser helper on the VM and regenerate
# the STATIC artifacts Caddy serves in front of it, so they never drift out of
# sync with the live source list.
#
# Caddy (api.hasanraza.tech) serves:
#   /sources/catalog        -> static snapshot  /var/www/nyora/catalog.json
#   /sources/{popular,...}  -> browse-cache sidecar (nyora-cache-proxy, :8790)
#   everything else         -> the helper itself (:8788)
# So after a helper restart (or a catalog/source-list change) the static
# catalog snapshot and the sidecar cache must be refreshed too — that's what
# this script does. Run it on the VM after deploying a new nyora-helper.jar or
# whenever the installed source set changes.
#
# Usage:  sudo ./restart-helper.sh
# Env overrides: NYORA_HELPER_PORT (8788), NYORA_STATIC_DIR (/var/www/nyora)
set -euo pipefail

HELPER_PORT="${NYORA_HELPER_PORT:-8788}"
HELPER="http://127.0.0.1:${HELPER_PORT}"
STATIC_DIR="${NYORA_STATIC_DIR:-/var/www/nyora}"
CATALOG="${STATIC_DIR}/catalog.json"

log() { echo "[nyora] $*"; }
die() { echo "[nyora] ERROR: $*" >&2; exit 1; }

# 1) Restart the parser helper.
log "restarting nyora-parser…"
sudo systemctl restart nyora-parser

# 2) Wait for it to come back up (poll /health, up to 90s).
log "waiting for helper /health…"
up=0
for i in $(seq 1 90); do
  if curl -fsS --max-time 3 "${HELPER}/health" >/dev/null 2>&1; then up=1; break; fi
  sleep 1
done
[ "$up" -eq 1 ] || die "helper did not become healthy within 90s"
log "helper is up."

# 3) Regenerate the static catalog snapshot from the live helper.
#    Validate it's non-empty JSON before publishing; on any failure keep the
#    existing snapshot (never publish a broken/empty catalog).
log "regenerating ${CATALOG} …"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
if curl -fsS --max-time 90 "${HELPER}/sources/catalog" -o "$tmp" && [ -s "$tmp" ] \
   && python3 -c "import json,sys; d=json.load(open('$tmp')); sys.exit(0 if d else 1)" 2>/dev/null; then
  sudo mkdir -p "$STATIC_DIR"
  sudo cp "$tmp" "$CATALOG"
  sudo chmod 644 "$CATALOG"
  log "catalog.json updated ($(wc -c < "$tmp" | tr -d ' ') bytes)."
else
  echo "[nyora] WARNING: catalog fetch failed or was not valid JSON — keeping the existing ${CATALOG}" >&2
fi

# 4) Refresh the browse-cache sidecar so it revalidates against the fresh helper.
if systemctl list-unit-files 2>/dev/null | grep -q '^nyora-cache-proxy'; then
  log "restarting browse-cache sidecar (nyora-cache-proxy)…"
  sudo systemctl restart nyora-cache-proxy || echo "[nyora] WARNING: sidecar restart failed" >&2
fi

log "done. api.hasanraza.tech now serves a fresh catalog + revalidated browse cache."
