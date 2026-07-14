#!/usr/bin/env bash
# dns-health.sh — self-healing DNS for the Nyora cluster (no paid load balancer).
#
# Runs on every node via nyora-dns-health.timer (every ~30s). It probes THIS
# node's local health and then makes the node's PUBLIC IP present-or-absent in
# the Cloudflare round-robin A records (NYORA_LB_RECORDS):
#
#   healthy   -> ensure an A record  <name> -> NYORA_PUBLIC_IP exists
#   unhealthy -> delete that A record, so clients stop resolving to this node
#
# Because each node manages only its OWN record, three nodes converge on a
# health-checked round-robin with ~30s failout — HA without a load balancer.
# When a node recovers it re-adds itself automatically.
set -euo pipefail
: "${CF_API_TOKEN:?}"; : "${CF_ZONE_ID:?}"; : "${NYORA_PUBLIC_IP:?}"; : "${NYORA_LB_RECORDS:?}"

HELPER="http://127.0.0.1:${NYORA_HELPER_PORT:-8788}/health"
api() { curl -fsS --max-time 8 -H "Authorization: Bearer ${CF_API_TOKEN}" -H "Content-Type: application/json" "$@"; }

# Local health = helper answers /health AND Caddy is listening on 443.
healthy=1
curl -fsS --max-time 4 "$HELPER" >/dev/null 2>&1 || healthy=0
(exec 3<>/dev/tcp/127.0.0.1/443) 2>/dev/null && exec 3>&- || healthy=0

for NAME in $NYORA_LB_RECORDS; do
  # Find an existing A record for THIS node's IP under this name.
  rid=$(api "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/dns_records?type=A&name=${NAME}&content=${NYORA_PUBLIC_IP}" \
        | python3 -c 'import sys,json; r=json.load(sys.stdin)["result"]; print(r[0]["id"] if r else "")')

  if [ "$healthy" -eq 1 ] && [ -z "$rid" ]; then
    api -X POST "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/dns_records" \
        --data "{\"type\":\"A\",\"name\":\"${NAME}\",\"content\":\"${NYORA_PUBLIC_IP}\",\"ttl\":60,\"proxied\":false}" >/dev/null
    echo "[dns-health] +added ${NAME} -> ${NYORA_PUBLIC_IP}"
  elif [ "$healthy" -eq 0 ] && [ -n "$rid" ]; then
    api -X DELETE "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/dns_records/${rid}" >/dev/null
    echo "[dns-health] -removed ${NAME} -> ${NYORA_PUBLIC_IP} (unhealthy)"
  fi
done
