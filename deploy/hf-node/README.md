---
title: Nyora Parser Node
emoji: 📚
colorFrom: indigo
colorTo: purple
sdk: docker
app_port: 7860
pinned: false
short_description: Extra Nyora parser cluster node (kotatsu-parsers JVM helper).
---

# Nyora parser node

An extra [Nyora](https://web.nyora.xyz) parser cluster member: the kotatsu-parsers
JVM helper (`NyoraRestServer`) behind Caddy on port `7860`. Built entirely from
source (`Nyora-Manga/nyora-shared`) at image-build time.

It exposes the **content-only** parser API (`/sources/*`, `/manga/*`, `/image`,
`/sources/catalog`, `/health`) with the same CORS lockdown (nyora.xyz + subdomains)
and local-route blocking as the VM nodes. It is a **lean node** — no FlareSolverr and
no WARP — so it serves normal sources fast but can't solve Cloudflare-protected or
IP-banned sources (the VM nodes handle those).

## How it joins the cluster

An HF Space is a hostname (`https://<owner>-<space>.hf.space`), not an IP, so it can't
go in `api.nyora.xyz`'s DNS round-robin. Instead the web app load-balances **client-side**
across a list of helper endpoints (`api.nyora.xyz` + this Space), with failover. A small
keepalive task pings `/health` periodically so the free Space never sleeps.

## Deploy (create the Space, push, done)

```bash
# 1. Create a Docker Space on huggingface.co (SDK: Docker, name e.g. "nyora-node").
# 2. From this directory:
git init && git add . && git commit -m "nyora parser node"
git remote add space https://huggingface.co/spaces/<owner>/nyora-node
git push space main            # HF builds the Dockerfile and starts it
# 3. Verify: curl https://<owner>-nyora-node.hf.space/health   → {"status":"ok"}
```

Rebuild picks up the latest `nyora-shared` `main` (`NYORA_SHARED_REF` build arg pins a ref).
