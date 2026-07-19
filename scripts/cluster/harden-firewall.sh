#!/usr/bin/env bash
# harden-firewall.sh — idempotent host firewall hardening for a Nyora cluster node.
#
# Adds a per-source-IP SYN-flood rate limit on 80/443 (catches only floods —
# legit users and CGNAT pools stay well under the threshold), and EXEMPTS
# Cloudflare's published IP ranges from it, because the nyora.xyz hosts are
# orange-clouded so all their legit traffic arrives from CF's edge and must never
# be throttled. Direct-to-origin traffic (the co-hosted grey hasanraza.tech
# vhosts) keeps the flood protection.
#
# Safe by design: only ever ADDS rules for tcp/80,443; SSH (22), loopback, and
# established connections are untouched, and the default INPUT policy stays
# ACCEPT — so a mistake here can never lock you out of SSH. Re-runnable.
#
# Requires: iptables, netfilter-persistent (Debian/Ubuntu), outbound to
# cloudflare.com. Run as root: sudo ./harden-firewall.sh
set -euo pipefail

CFV4="$(curl -fsS --max-time 15 https://www.cloudflare.com/ips-v4)"
[ "$(printf '%s\n' "$CFV4" | grep -c '/')" -ge 10 ] || { echo "CF range fetch failed"; exit 1; }

SYN_RULE=(-p tcp -m multiport --dports 80,443 --syn
          -m hashlimit --hashlimit-name web_syn --hashlimit-mode srcip
          --hashlimit-above 200/sec --hashlimit-burst 400 -j DROP)

# 1. SYN-flood limit — insert at the top so it sits above the broad port ACCEPTs.
if ! iptables -C INPUT "${SYN_RULE[@]}" 2>/dev/null; then
  iptables -I INPUT 1 "${SYN_RULE[@]}"
  echo "+ added SYN-flood limit (200/s per IP on 80,443)"
fi

# 2. Exempt each Cloudflare range ABOVE the limit (insert at top; idempotent).
added=0
for r in $CFV4; do
  if ! iptables -C INPUT -p tcp -m multiport --dports 80,443 -s "$r" \
        -m comment --comment nyora-cf-exempt -j ACCEPT 2>/dev/null; then
    iptables -I INPUT 1 -p tcp -m multiport --dports 80,443 -s "$r" \
        -m comment --comment nyora-cf-exempt -j ACCEPT
    added=$((added + 1))
  fi
done
echo "+ ${added} Cloudflare-exempt ACCEPT rule(s)"

netfilter-persistent save >/dev/null
echo "firewall hardened + persisted (/etc/iptables/rules.v4)"
