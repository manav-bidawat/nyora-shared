---
title: Nyora One
emoji: 📚
colorFrom: indigo
colorTo: purple
sdk: gradio
app_file: app.py
pinned: false
short_description: Extra Nyora parser cluster node (JVM helper).
---

# Nyora parser node

An extra [Nyora](https://web.nyora.xyz) parser cluster member: the
kotatsu-parsers JVM helper (`NyoraRestServer`) wrapped in a small Gradio app so
it can run on this free Space. `app.py` launches the helper on loopback and
reverse-proxies it on the Space port.

It exposes the **content-only** parser API (`/n/sources/*`, `/n/manga/*`, `/n/image`,
`/n/sources/catalog`, `/n/health`) with local-route blocking, like the VM nodes. It
is a **lean node** — no FlareSolverr, no WARP — so it serves normal sources locally
but can't fetch Cloudflare-protected or IP-banned ones. Those it **relays** to the
WARP-backed cluster (`NYORA_UPSTREAM`, default `https://api.nyora.xyz`), so it never
returns a broken CF result and is safe to put in the client round-robin.

Unlike the VM nodes, **CORS is not locked to nyora.xyz here**: HF's Spaces edge proxy
injects a reflect-all `Access-Control-Allow-Origin` on every response (to keep Spaces
embeddable), which overrides anything the app sets. Since this node serves only
public, read-only, unauthenticated parser data, permissive CORS exposes nothing
sensitive.

An HF Space is a hostname, not an IP, so it can't join `api.nyora.xyz`'s DNS
round-robin. The web app load-balances **client-side** across a list of helper
endpoints (`api.nyora.xyz` + this Space) with failover, and a small keepalive task
pings `/n/health` so the free Space never sleeps.

## Rebuild & redeploy

The live Space is `mdhasanraza/nyora-one` (free **Gradio + ZeroGPU** — Docker Spaces
need PRO). `nyora-helper.jar` is a build artifact (gitignored here; the Space keeps
it via git-LFS). To ship a new helper build + these files:

```sh
# 1. Build the helper jar from nyora-shared and stage it here
./gradlew helperJar
cp build/libs/nyora-helper.jar deploy/hf-gradio-node/

# 2. Upload the folder to the Space. Use the Python API, NOT `hf upload` — the CLI
#    calls repos/create and 402s on a free Gradio Space even when it exists.
python - <<'PY'
from huggingface_hub import HfApi, get_token
HfApi(token=get_token()).upload_folder(
    folder_path="deploy/hf-gradio-node", repo_id="mdhasanraza/nyora-one",
    repo_type="space", commit_message="update nyora node")
PY
```

Notes: ZeroGPU kills any container with no `@spaces.GPU` function, so `app.py` keeps a
decoy one. HF must start the app via `demo.launch()` (a raw `uvicorn.run` is SIGTERM'd).
Keepalive lives on the `kbg` Oracle micro box (`ssh hf-pinger`), not on a helper VM.
