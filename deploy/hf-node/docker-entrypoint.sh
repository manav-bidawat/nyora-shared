#!/usr/bin/env bash
# Start the Java parser helper on loopback:8788, wait for health, then exec Caddy
# on :7860 in the foreground (PID1-friendly under tini).
set -euo pipefail

HELPER_PORT="${NYORA_HELPER_PORT:-8788}"
HELPER_JAR="${NYORA_HELPER_JAR:-/opt/nyora/nyora-helper.jar}"
JAVA_OPTS="${JAVA_OPTS:--Xmx1024m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC}"

log() { printf '[entrypoint] %s\n' "$*" >&2; }

log "starting helper on 127.0.0.1:${HELPER_PORT}"
NYORA_HELPER_PORT="$HELPER_PORT" java ${JAVA_OPTS} -jar "$HELPER_JAR" &
HELPER_PID=$!
trap 'kill "$HELPER_PID" 2>/dev/null || true' TERM INT

for i in $(seq 1 60); do
	if curl -fsS "http://127.0.0.1:${HELPER_PORT}/health" >/dev/null 2>&1; then
		log "helper healthy"
		break
	fi
	sleep 2
done

log "starting caddy on :7860"
exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
