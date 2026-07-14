#!/usr/bin/env bash
# deploy-cluster.sh — push the helper jar + static bundles to all 3 nodes and
# roll a zero-downtime restart (one node at a time; self-healing DNS pulls each
# node out of rotation while it restarts, so users never hit a restarting node).
#
# Run from your laptop. Assumes ssh aliases n1/n2/n3 (or edit NODES) with the
# ubuntu key. Artifacts:
#   $JAR        nyora-helper.jar  (built from nyora-shared)
#   $WEB_DIR    built nyora-web    -> /srv/web
#   $SITE_DIR   built nyora-site   -> /srv/site
set -euo pipefail
NODES=(${NYORA_NODES:-n1 n2 n3})
JAR="${JAR:-$HOME/Desktop/nyora-helper.jar}"
WEB_DIR="${WEB_DIR:-$HOME/Desktop/kotatsu/Nyora/nyora-web/web/}"
SITE_DIR="${SITE_DIR:-$HOME/Desktop/kotatsu/Nyora/nyora-site/dist/}"

roll() {
  local host="$1"
  echo "== $host: pull out of rotation, deploy, restart, re-add =="
  # 1) mark unhealthy so DNS drops it (stop helper -> health probe fails)
  ssh "$host" 'sudo systemctl stop nyora-parser && sudo /usr/local/bin/nyora-dns-health.sh || true'
  sleep 3
  # 2) ship artifacts
  [ -f "$JAR" ]      && scp "$JAR"  "$host:/home/ubuntu/nyora-parser/nyora-helper.jar"
  [ -d "$WEB_DIR" ]  && rsync -az --delete "$WEB_DIR"  "$host:/srv/web/"
  [ -d "$SITE_DIR" ] && rsync -az --delete "$SITE_DIR" "$host:/srv/site/"
  # 3) restart + regenerate catalog snapshot, then re-add to DNS when healthy
  ssh "$host" 'sudo systemctl start nyora-parser && sudo NYORA_STATIC_DIR=/var/www/nyora bash -lc "curl -fsS --retry 20 --retry-delay 2 http://127.0.0.1:8788/health >/dev/null" && sudo /usr/local/bin/nyora-dns-health.sh'
  echo "   $host back in rotation."
}

for n in "${NODES[@]}"; do roll "$n"; sleep 5; done
echo "✓ rolling deploy complete across: ${NODES[*]}"
