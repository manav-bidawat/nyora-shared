# syntax=docker/dockerfile:1.7
# ============================================================================
# Nyora parser NODE for a Hugging Face Docker Space — an extra cluster member
# behind the client-side helper round-robin (NOT the DNS round-robin; an HF
# Space is a hostname, not an IP).
#
# Layout inside the container:
#   Caddy :7860 (HF's required app_port; HF's edge terminates TLS)
#     ├─ CORS locked to nyora.xyz + subdomains
#     ├─ 404 the local-device-only surface (library/downloads/supabase/ota/local)
#     └─ reverse_proxy → Java helper 127.0.0.1:8788
#   Java helper :8788 (loopback) — the kotatsu-parsers engine + NyoraRestServer.
#
# Lean node: no SPA, no FlareSolverr, no WARP — so it serves normal sources fast
# and simply can't solve Cloudflare-protected / IP-banned sources (the VMs do).
# ============================================================================

# ---- Stage 1: build the helper fat jar from nyora-shared -------------------
FROM eclipse-temurin:17-jdk AS helper
ARG NYORA_SHARED_REF=main
ARG NYORA_SHARED_REPO=https://github.com/Nyora-Manga/nyora-shared.git
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
# The vendored self-contained Gradle project that builds only the :shared module.
COPY helper/ /build/helper/
RUN git clone --depth 1 --branch "${NYORA_SHARED_REF}" "${NYORA_SHARED_REPO}" /build/helper/nyora-shared
WORKDIR /build/helper
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x ./gradlew \
    && ./gradlew :shared:helperJar --no-daemon --console=plain \
    && cp shared/build/libs/nyora-helper.jar /nyora-helper.jar \
    && test -s /nyora-helper.jar

# ---- Stage 2: lean runtime — JRE + Caddy ----------------------------------
FROM eclipse-temurin:17-jre AS runtime
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        curl ca-certificates tini debian-keyring debian-archive-keyring apt-transport-https gnupg; \
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
        | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg; \
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
        | tee /etc/apt/sources.list.d/caddy-stable.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends caddy; \
    apt-get purge -y --auto-remove gnupg apt-transport-https; \
    rm -rf /var/lib/apt/lists/*

# HF Spaces run as an arbitrary non-root UID; keep all writable dirs world-writable.
RUN mkdir -p /opt/nyora /data /config \
    && chmod -R 777 /data /config
COPY --from=helper /nyora-helper.jar /opt/nyora/nyora-helper.jar
COPY Caddyfile            /etc/caddy/Caddyfile
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENV NYORA_HELPER_PORT=8788 \
    NYORA_HELPER_JAR=/opt/nyora/nyora-helper.jar \
    NYORA_ALLOWED_ORIGINS=nyora.xyz \
    XDG_DATA_HOME=/data XDG_CONFIG_HOME=/data XDG_CACHE_HOME=/data HOME=/data \
    JAVA_OPTS="-Xmx1024m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC"

EXPOSE 7860
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD curl -fsS http://127.0.0.1:7860/health || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
