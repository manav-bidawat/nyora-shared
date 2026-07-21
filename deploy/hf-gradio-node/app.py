"""Nyora parser node — free HF Gradio Space wrapper.

Gradio owns the Space port 7860 (HF's runtime requires demo.launch() as the
entrypoint), and a thin FastAPI reverse proxy to the kotatsu-parsers JVM helper
(NyoraRestServer, loopback:8788) is mounted under /n. The parser API therefore
lives at `https://<space>.hf.space/n/...`. Local-device-only routes are 404'd
in-app; CORS can't be restricted here (HF's edge forces reflect-all — see below).
"""

import atexit
import json
import os
import re
import shutil
import subprocess
import threading
import time

import gradio as gr
import httpx
import spaces  # ZeroGPU: the supervisor expects the spaces package + a GPU fn
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse

HELPER_PORT = int(os.environ.get("NYORA_HELPER_PORT", "8788"))
HELPER = f"http://127.0.0.1:{HELPER_PORT}"
JAR = os.environ.get("NYORA_HELPER_JAR", "nyora-helper.jar")
PORT = int(os.environ.get("PORT", "7860"))


def _sysinfo():
    """Log this container's real cgroup RAM/CPU + disk (ZeroGPU is 'dynamic')."""
    def _read(p):
        try:
            return open(p).read().strip()
        except OSError:
            return None

    def _gib(n):
        try:
            n = int(n)
            return "unlimited" if n > (1 << 62) else f"{n / (1 << 30):.2f} GiB"
        except (TypeError, ValueError):
            return str(n)

    mem_max = _read("/sys/fs/cgroup/memory.max") or _read("/sys/fs/cgroup/memory/memory.limit_in_bytes")
    mem_cur = _read("/sys/fs/cgroup/memory.current") or _read("/sys/fs/cgroup/memory/memory.usage_in_bytes")
    meminfo = {}
    for line in (_read("/proc/meminfo") or "").splitlines():
        k, _, v = line.partition(":")
        meminfo[k.strip()] = v.strip()
    cpu_max = _read("/sys/fs/cgroup/cpu.max")  # "<quota> <period>" or "max <period>"
    cpu_quota = "n/a"
    if cpu_max and cpu_max.split()[0] != "max":
        q, p = cpu_max.split()[:2]
        cpu_quota = f"{int(q) / int(p):.2f} cores"
    du = shutil.disk_usage("/")
    print(
        "[nyora-node][sysinfo] "
        f"cgroup_mem_limit={_gib(mem_max)} cgroup_mem_used={_gib(mem_cur)} "
        f"host_MemTotal={meminfo.get('MemTotal','?')} host_MemAvailable={meminfo.get('MemAvailable','?')} "
        f"os_cpu_count={os.cpu_count()} cgroup_cpu_quota={cpu_quota} "
        f"disk_total={du.total/(1<<30):.1f}GiB disk_free={du.free/(1<<30):.1f}GiB",
        flush=True,
    )


_sysinfo()

# Local-device-only surface — meaningless on a public scraping node.
_LOCAL = re.compile(r"^/(library|downloads|supabase|ota|local)(/.*)?$")
_DROP = {"content-encoding", "content-length", "transfer-encoding", "connection", "host"}

# Launch the JVM helper on loopback. Small heap keeps it inside the free tier's RAM.
_JAVA_CMD = ["java", "-Xmx512m", "-Xss512k", "-XX:MaxMetaspaceSize=96m",
             "-XX:ReservedCodeCacheSize=48m", "-XX:+UseSerialGC", "-jar", JAR]
_JAVA_ENV = {**os.environ, "NYORA_HELPER_PORT": str(HELPER_PORT)}


def _spawn_helper():
    return subprocess.Popen(_JAVA_CMD, env=_JAVA_ENV)


_helper = _spawn_helper()
atexit.register(lambda: _helper.terminate())


def _watchdog():
    # Self-heal: if the JVM helper dies (crash/OOM), respawn it so the node keeps
    # serving locally instead of relaying every request to the WARP cluster.
    global _helper
    while True:
        time.sleep(15)
        if _helper.poll() is not None:
            code = _helper.returncode
            print(f"[nyora-node] helper exited (code {code}); respawning", flush=True)
            try:
                _helper = _spawn_helper()
            except Exception as e:  # noqa: BLE001 - keep the watchdog alive
                print(f"[nyora-node] respawn failed: {e}", flush=True)


threading.Thread(target=_watchdog, daemon=True, name="helper-watchdog").start()

_client = httpx.AsyncClient(base_url=HELPER, timeout=httpx.Timeout(90.0))

# This node is lean (no WARP), so Cloudflare/IP-banned sources fail locally. When
# they do, relay that request to the WARP-backed VM cluster so the caller still
# gets data — making this a safe cluster member, not a source of broken results.
UPSTREAM = os.environ.get("NYORA_UPSTREAM", "https://api.nyora.xyz")
_relay = httpx.AsyncClient(base_url=UPSTREAM, timeout=httpx.Timeout(90.0))


def _local_failed(status, content_type, body):
    """True if the local helper couldn't serve this (CF/IP block, 5xx) and it's
    worth retrying on the WARP cluster."""
    if status >= 500:
        return True
    if "application/json" in (content_type or "") and body[:1] == b"{":
        try:
            j = json.loads(body)
            return isinstance(j, dict) and "error" in j
        except ValueError:
            return False
    return False


# Reverse proxy to the JVM helper. Mounted under /n; paths here are mount-relative.
# Blocks local-device routes, serves normal sources from the local helper, and
# relays CF/IP-blocked sources to the WARP cluster (UPSTREAM). CORS is out of our
# hands here (HF's edge forces reflect-all).
proxy = FastAPI()


def _out(resp):
    headers = {k: v for k, v in resp.headers.items() if k.lower() not in _DROP}
    return Response(resp.content, status_code=resp.status_code,
                    media_type=resp.headers.get("content-type"), headers=headers)


@proxy.api_route("/{path:path}", methods=["GET", "POST", "DELETE"])
async def _proxy(path: str, request: Request):
    if _LOCAL.match("/" + path):
        return JSONResponse({"error": "Not found"}, status_code=404)
    fwd = {k: v for k, v in request.headers.items() if k.lower() not in _DROP}
    body = await request.body()
    url = httpx.URL(path="/" + path, query=request.url.query.encode())
    try:
        up = await _client.request(request.method, url, headers=fwd, content=body)
    except httpx.HTTPError:
        up = None  # local helper unreachable → go straight to the WARP cluster

    # Relay to the WARP cluster if the local (proxy-less) helper couldn't serve it.
    if up is None or _local_failed(up.status_code, up.headers.get("content-type"), up.content):
        try:
            return _out(await _relay.request(request.method, url, headers=fwd, content=body))
        except httpx.HTTPError as e:
            if up is not None:
                return _out(up)  # keep the local error if the relay also failed
            return JSONResponse({"error": f"upstream unreachable: {e}"}, status_code=502)
    return _out(up)


@spaces.GPU(duration=5)
def _gpu_ping():  # registers a GPU fn so the ZeroGPU supervisor keeps us alive
    return "ok"


with gr.Blocks(title="Nyora node") as demo:
    gr.Markdown(
        "### Nyora parser node\n"
        "Extra Nyora cluster member running the kotatsu-parsers JVM helper. "
        "The parser API is served under this Space's `/n/` path "
        "(`/n/health`, `/n/sources/catalog`, `/n/manga/*`, `/n/image`). "
        "This page is just a liveness placeholder."
    )
    gr.Button("ping", visible=False).click(_gpu_ping, outputs=gr.Textbox(visible=False))

# HF's gradio runtime requires the app to come up via demo.launch() (a raw
# uvicorn app gets SIGTERM'd). ssr_mode=False avoids the Node SSR sidecar. We
# grab the FastAPI app gradio serves and bolt the parser proxy onto it at /n.
gradio_app, _local, _share = demo.launch(
    server_name="0.0.0.0",
    server_port=PORT,
    ssr_mode=False,
    prevent_thread_lock=True,
)
gradio_app.mount("/n", proxy)
print("[nyora-node] parser proxy mounted at /n", flush=True)

# NOTE: unlike the VM nodes, CORS cannot be locked to nyora.xyz here. HF's Spaces
# edge proxy injects a reflect-all `Access-Control-Allow-Origin` on every response
# (to keep Spaces embeddable), overriding anything the app sets. This node serves
# only public, read-only, unauthenticated parser data, so permissive CORS exposes
# nothing sensitive — it just means any site could call this free node from a
# browser. The local-route 404 block above is enforced in-app and does hold.

# prevent_thread_lock returned control to us; keep the process alive so gradio's
# server thread (and the JVM helper) stay up.
demo.block_thread()
