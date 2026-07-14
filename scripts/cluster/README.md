# Nyora 3-node cluster (Oracle Free Tier)

Turn your **3 × Ampere A1 (1 vCPU / 6 GB, ARM64)** Oracle boxes into one
resilient Nyora backend that gives you **HA + throughput + Cloudflare-resistance**
— entirely on the always-free tier, no paid load balancer.

## Why this shape

| Goal | How it's met |
|------|--------------|
| **High availability** | Each node's Caddy prefers its *local* helper and fails over to siblings on 5xx. A self-healing DNS timer removes a dead node from the `api`/`sync` round-robin within ~30 s and re-adds it on recovery. |
| **Throughput** | 3× helper capacity. The heavy bytes — page/cover **images** and **static** app/site — are `immutable` and served from **Cloudflare's edge cache**, so the 10 Mbps free pipe is never the bottleneck. |
| **Beat Cloudflare** | Every node runs its **own WARP exit** (`socks5://127.0.0.1:40000`). A source blocked on node1's exit is retried on node2/3 (different exits). JS-interstitial sources go to one shared **FlareSolverr**. |

> **Oracle reality:** all 3 IPs are in Oracle's ranges (same tenancy/region), so
> a CF ban tends to hit all three — that's why the anti-CF story is **WARP exits +
> cross-node retry**, not "3 different datacenter IPs".

## Topology

```
Cloudflare (free): nyora.xyz / web.nyora.xyz / img cached at edge (origin ≈ 0)
                   api.nyora.xyz / sync.nyora.xyz -> health-checked round-robin
   node1 (DB)            node2                 node3
   caddy :443           caddy :443            caddy :443
   helper :8788 ◄─ 5xx failover between nodes ─► helper :8788
   sync :8787 + SQLite  (sync -> node1)        (sync -> node1)
   FlareSolverr :8191   (flare -> node1)       (flare -> node1)
   WARP exit A          WARP exit B            WARP exit C
```

Only **one** thing is stateful: the sync **SQLite** DB (single-writer) → it lives
on **node1**; node2/3 forward `sync.nyora.xyz` to node1. Reading works fully even
if node1/sync is down — only cross-device sync pauses.

## Files

| File | Purpose |
|------|---------|
| `nyora-cluster.env.example` | the **only** per-node file → `/etc/default/nyora-cluster` |
| `Caddyfile.cluster` | identical on all nodes; reads per-node env |
| `systemd/nyora-parser.service` | helper unit (heap raised for 6 GB, WARP-aware) |
| `systemd/nyora-dns-health.{service,timer}` | self-healing DNS every 30 s |
| `dns-health.sh` | add/remove this node's IP in the CF round-robin by health |
| `node-setup.sh` | one-shot provisioner (JDK/Caddy/WARP + wiring) |
| `deploy-cluster.sh` | rolling, zero-downtime deploy of jar + static |

## Bring-up runbook

**0. Oracle console (once):** put all 3 instances in one **VCN/subnet**; in the
subnet security list allow, *from the VCN CIDR only*, TCP **8788** (helper) and
**8787** (sync) so nodes can reach each other; allow **443** from anywhere. Note
each node's **private** and **public** IP.

**1. Per node:**
```bash
sudo cp nyora-cluster.env.example /etc/default/nyora-cluster
sudo nano /etc/default/nyora-cluster      # set NODE_ID, PEER1/2, SYNC_HOST, FLARE_HOST, CF token, public IP
sudo ./node-setup.sh                       # installs JDK/Caddy/WARP + units
```
- **node1**: `NYORA_SYNC_HOST=127.0.0.1`, `NYORA_FLARE_HOST=127.0.0.1` (runs DB + solver).
- **node2/3**: point both at **node1's private IP**.

**2. Deploy artifacts (from your laptop):** build the helper jar + `nyora-web` +
`nyora-site`, set ssh aliases `n1/n2/n3`, then:
```bash
./deploy-cluster.sh
```

**3. DNS (Cloudflare, nyora.xyz zone):**
- `api.nyora.xyz` and `sync.nyora.xyz`: let the health timer manage the A records
  (start with all 3 public IPs; unhealthy nodes drop out automatically). Keep
  `sync` pointed at **node1 only** unless you migrate SQLite→Postgres.
- `web.nyora.xyz`, `nyora.xyz`, and the image path: **orange-cloud (proxied)** so
  CF edge-caches them; origin = the same round-robin.

**4. Verify:**
```bash
curl -fsS https://api.nyora.xyz/health
for n in n1 n2 n3; do ssh $n 'curl -s --socks5 127.0.0.1:40000 https://ifconfig.me; echo " <- $(hostname)"'; done  # 3 distinct WARP exits
# kill a node's helper -> within ~30s it leaves DNS and api.nyora.xyz still answers
```

## Upgrade paths (optional)

- **Real HA sync:** migrate SQLite → Postgres (or Oracle always-free Autonomous
  DB) so all 3 nodes are stateless and sync survives a node1 loss.
- **Managed LB:** swap the self-healing DNS for Oracle's always-free Flexible LB
  (health-checked) if you prefer a single stable entry IP; keep images on CF edge
  to stay under the 10 Mbps LB cap.
- **Helper-level CF retry:** add `NYORA_PEER_HELPERS` support in the helper so it
  retries a blocked source through a sibling *before* returning, instead of
  relying on Caddy's 5xx failover.
```
