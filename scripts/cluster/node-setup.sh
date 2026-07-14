#!/usr/bin/env bash
# node-setup.sh — turn a fresh Oracle Ampere (ARM64) Ubuntu box into a Nyora
# cluster node. Idempotent: safe to re-run. Run AS the ubuntu user with sudo.
#
#   1) fill /etc/default/nyora-cluster   (copy from nyora-cluster.env.example)
#   2) sudo ./node-setup.sh
#   3) deploy the artifacts (helper jar, /srv/web, /srv/site) — see deploy-cluster.sh
#
# Installs: Temurin JDK 21 (arm64), Caddy, Cloudflare WARP (proxy :40000),
# and wires the systemd units + self-healing DNS timer. FlareSolverr is only
# set up on the node whose NYORA_FLARE_HOST is 127.0.0.1 (one shared solver).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f /etc/default/nyora-cluster ] || { echo "!! create /etc/default/nyora-cluster first (see nyora-cluster.env.example)"; exit 1; }
. /etc/default/nyora-cluster
say() { echo -e "\n== $* =="; }

say "packages"
sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y curl gpg python3 apt-transport-https ca-certificates

say "Temurin JDK 21 (arm64)"
if ! command -v java >/dev/null; then
  curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
  sudo apt-get update -y && sudo apt-get install -y temurin-21-jdk
fi

say "Caddy"
if ! command -v caddy >/dev/null; then
  sudo apt-get install -y debian-keyring debian-archive-keyring
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  sudo apt-get update -y && sudo apt-get install -y caddy
fi

say "Cloudflare WARP (proxy mode :40000) — this node's independent egress exit"
if ! command -v warp-cli >/dev/null; then
  curl -fsSL https://pkg.cloudflareclient.com/pubkey.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/cloudflare-warp-archive-keyring.gpg
  echo "deb [signed-by=/usr/share/keyrings/cloudflare-warp-archive-keyring.gpg] https://pkg.cloudflareclient.com/ $(. /etc/os-release; echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/cloudflare-client.list >/dev/null
  sudo apt-get update -y && sudo apt-get install -y cloudflare-warp
fi
warp-cli --accept-tos registration show >/dev/null 2>&1 || warp-cli --accept-tos registration new
warp-cli --accept-tos mode proxy || true
warp-cli --accept-tos proxy port 40000 || true
warp-cli --accept-tos connect || true

say "systemd units + scripts"
sudo install -m 0755 "$HERE/dns-health.sh" /usr/local/bin/nyora-dns-health.sh
sudo cp "$HERE/systemd/nyora-parser.service" /etc/systemd/system/nyora-parser.service
sudo cp "$HERE/systemd/nyora-dns-health.service" /etc/systemd/system/nyora-dns-health.service
sudo cp "$HERE/systemd/nyora-dns-health.timer"   /etc/systemd/system/nyora-dns-health.timer

say "Caddy: cluster config + read the per-node env"
sudo cp "$HERE/Caddyfile.cluster" /etc/caddy/Caddyfile
sudo mkdir -p /etc/systemd/system/caddy.service.d
printf '[Service]\nEnvironmentFile=/etc/default/nyora-cluster\n' | sudo tee /etc/systemd/system/caddy.service.d/nyora-env.conf >/dev/null

if [ "${NYORA_FLARE_HOST:-}" = "127.0.0.1" ]; then
  say "FlareSolverr (this is the shared-solver node)"
  echo "   -> install FlareSolverr into /home/ubuntu/flaresolverr + enable flaresolverr.service (see runbook)"
fi

say "enable + (re)start"
sudo systemctl daemon-reload
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl enable --now nyora-parser.service nyora-dns-health.timer
sudo systemctl restart caddy nyora-parser
sudo systemctl restart nyora-dns-health.service || true
echo -e "\n✓ node ${NYORA_NODE_ID:-?} ready. Verify: curl -fsS http://127.0.0.1:8788/health && curl --socks5 127.0.0.1:40000 https://ifconfig.me"
