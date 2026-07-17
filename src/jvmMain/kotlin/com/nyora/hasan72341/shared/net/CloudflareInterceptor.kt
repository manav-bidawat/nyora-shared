package com.nyora.hasan72341.shared.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Talks to a local FlareSolverr instance (default http://127.0.0.1:8191/v1) to
 * solve a Cloudflare challenge. FlareSolverr runs a real Chrome on THIS host, so
 * the `cf_clearance` it returns is bound to this machine's IP + Chrome UA — which
 * is exactly what the helper's own OkHttp requests use, so the clearance is valid
 * for them. (Solves classic "Just a moment" JS challenges; cannot beat Turnstile.)
 */
internal object FlareSolverr {
    private val endpoint: String = System.getenv("FLARESOLVERR_URL") ?: "http://127.0.0.1:8191/v1"

    /** Absent env → enabled; set NYORA_FLARESOLVERR_DISABLED=1 to turn off. */
    private val enabled: Boolean = System.getenv("NYORA_FLARESOLVERR_DISABLED").isNullOrBlank()

    // Each solve spawns a headless Chrome. A batched all-source search hits many
    // CF sources at once, so cap concurrent solves (default 2) to stop a Chrome
    // swarm from saturating the small VM. Extra solves wait for a permit.
    private val maxConcurrent: Int = System.getenv("NYORA_FLARESOLVERR_CONCURRENCY")?.toIntOrNull()?.coerceIn(1, 8) ?: 2
    private val gate = java.util.concurrent.Semaphore(maxConcurrent)

    private val json = Json { ignoreUnknownKeys = true }

    // Separate client (no CF interceptor) so solving never recurses.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Solution(val cookieHeader: String, val userAgent: String)

    fun solve(url: String): Solution? {
        if (!enabled) return null
        if (!gate.tryAcquire(120, TimeUnit.SECONDS)) return null // don't queue forever
        return try {
            val payload = buildJsonObject {
                put("cmd", "request.get")
                put("url", url)
                // Shorter than the old 90s so a broad search isn't pinned per solve.
                put("maxTimeout", System.getenv("NYORA_FLARESOLVERR_TIMEOUT_MS")?.toIntOrNull() ?: 30_000)
            }.toString()
            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") return null
                val solution = root["solution"]?.jsonObject ?: return null
                val userAgent = solution["userAgent"]?.jsonPrimitive?.contentOrNull ?: return null
                val cookies = solution["cookies"]?.jsonArray ?: return null
                val header = cookies.mapNotNull {
                    val obj = it.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    val value = obj["value"]?.jsonPrimitive?.contentOrNull
                    if (name != null && value != null) "$name=$value" else null
                }.joinToString("; ")
                if (header.isBlank()) null else Solution(header, userAgent)
            }
        } catch (_: Throwable) {
            null
        } finally {
            gate.release()
        }
    }
}

/**
 * OkHttp interceptor that transparently clears Cloudflare challenges via a local
 * FlareSolverr. On a challenge response it solves once, injects the returned
 * cf_clearance into [sharedCookieJar], remembers the solver's User-Agent for that
 * host (cf_clearance is UA-bound), and retries. Subsequent requests to the host
 * reuse the cached UA + jar cookie, so we don't re-solve every call.
 *
 * A singleton so the per-host UA cache is shared across every OkHttpClient the
 * helper builds (fetchText/fetchBytes create fresh clients per call).
 */
object CloudflareInterceptor : Interceptor {
    private val solvedUserAgent = ConcurrentHashMap<String, String>()

    // When the host app can solve challenges itself, signal the challenge to it rather
    // than spawning a headless Chrome. The macOS app sets this: it owns a WKWebView,
    // which is already part of the OS, runs on the user's own residential IP, and can
    // beat Turnstile because a human can click it — none of which FlareSolverr can do.
    // The app catches "Cloudflare challenge: <host>", solves, POSTs the cf_clearance to
    // /cloudflare/clearance, and retries.
    //
    // Off by default, so the headless cluster (which has no WebView and a real
    // FlareSolverr on 127.0.0.1:8191) keeps its existing behaviour untouched.
    private val nativeSolver: Boolean = !System.getenv("NYORA_NATIVE_CF_SOLVER").isNullOrBlank()

    // While a broad global search is fanning out, skip ALL Cloudflare escalation
    // (FlareSolverr/proxy/device) and fail fast on blocked sources — solving CF for
    // hundreds of sources at once would pin the VM. Blocked sources simply don't
    // appear in search results. Incremented/decremented around each search fetch.
    val searchFanoutActive = java.util.concurrent.atomic.AtomicInteger(0)

    // Hosts that were blocked AND couldn't be solved recently. Skip the expensive
    // escalation (FlareSolverr/proxy/device) for them so a broad all-source search
    // doesn't re-solve the same unbeatable Cloudflare sites over and over and pin
    // the VM (headless-Chrome storm). Cleared when a host later succeeds.
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val blockTtlMs: Long = System.getenv("NYORA_BLOCK_TTL_MS")?.toLongOrNull() ?: 600_000L // 10 min

    private fun hostBlocked(host: String): Boolean {
        val until = blockedUntil[host] ?: return false
        if (System.currentTimeMillis() > until) { blockedUntil.remove(host); return false }
        return true
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host

        // If we've already solved this host, keep sending the solver's UA so the
        // stored cf_clearance cookie is accepted.
        solvedUserAgent[host]?.let { userAgent ->
            request = request.newBuilder().header("User-Agent", userAgent).build()
        }

        val response = chain.proceed(request)
        // Escalate on ANY 403/503 — either a JS challenge (has CF markers) OR a hard
        // datacenter-IP block (e.g. MangaFire on claw returns a bare 403 with no
        // challenge markers). Both mean "this server IP can't get through" → the fix
        // is the same: reach the site from a different (residential) egress.
        if (response.code != 403 && response.code != 503) {
            blockedUntil.remove(host) // recovered
            return response
        }
        // During a broad search, never solve CF — fail fast so blocked sources are
        // just skipped instead of pinning the VM with hundreds of Chrome solves.
        if (searchFanoutActive.get() > 0) return response
        // Fail fast for hosts we already know we can't get through — no re-solve churn.
        if (hostBlocked(host)) return response
        val challenge = isChallenge(response)

        // 1) Native solve: hand the challenge to the host app's own WebView (macOS
        //    WKWebView). It solves on the user's real IP — passively first, and
        //    interactively if Turnstile wants a click — then POSTs the cf_clearance to
        //    /cloudflare/clearance and retries. The app's WebView UA is byte-identical to
        //    NYORA_BROWSER_UA (see HelperNetworkSettings), so the clearance it returns is
        //    valid for our own requests without any UA juggling.
        if (challenge && nativeSolver) {
            response.close()
            throw IOException("Cloudflare challenge: $host")
        }

        // 1b) Server-side solve: classic "Just a moment" JS challenges (cheap, fast).
        //     Headless-Chrome path, for hosts with no native WebView (the cluster).
        if (challenge) {
            val solution = FlareSolverr.solve(request.url.toString())
            if (solution != null) {
                response.close()
                injectClearanceCookies(host, solution.cookieHeader)
                solvedUserAgent[host] = solution.userAgent
                return chain.proceed(request.newBuilder().header("User-Agent", solution.userAgent).build())
            }
        }

        // 2) Residential-proxy egress: some sources (e.g. MangaFire) block ALL
        //    datacenter IPs at the edge — they only serve residential IPs. Retry via
        //    a user-supplied residential proxy. No-op unless NYORA_RESIDENTIAL_PROXY
        //    is set. Works for every client (web + iOS) transparently.
        if (ResidentialProxy.isConfigured) {
            val proxied = ResidentialProxy.fetch(request)
            if (proxied != null && proxied.code != 403 && proxied.code != 503) {
                response.close()
                return proxied
            }
            proxied?.close()
        }

        // 3) Device-as-egress: a connected client (iOS phone) fetches from ITS OWN
        //    residential IP + WebView-cleared session and streams the bytes back —
        //    the free way to use the CUSTOMER's IP despite the helper being on a VM.
        //    Beats both Turnstile AND datacenter-IP blocks (phone IP is residential).
        val relayed = relayViaDevice(request)
        if (relayed != null && relayed.code != 403 && relayed.code != 503) {
            response.close()
            return relayed
        }

        // Nothing got through — remember this host as blocked so we fail fast next
        // time instead of re-solving it on every search.
        blockedUntil[host] = System.currentTimeMillis() + blockTtlMs
        return response
    }

    private fun relayViaDevice(request: Request): Response? {
        if (!DeviceRelay.deviceAvailable()) return null
        // Forward the parser's headers (Referer, X-Requested-With, Accept, …) but
        // drop Cookie/UA/Host — the device uses its own cleared browser session.
        val headers = LinkedHashMap<String, String>()
        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i)
            if (name.equals("Cookie", ignoreCase = true) ||
                name.equals("User-Agent", ignoreCase = true) ||
                name.equals("Host", ignoreCase = true)
            ) continue
            headers[name] = request.headers.value(i)
        }
        val body = request.body?.let { requestBody ->
            // OkHttp keeps a POST body's Content-Type on the body, not in headers —
            // relay it so the device sends a parseable request (e.g. WP admin-ajax
            // form posts 500 without application/x-www-form-urlencoded).
            requestBody.contentType()?.let { contentType ->
                if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    headers["Content-Type"] = contentType.toString()
                }
            }
            try {
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                buffer.readByteArray()
            } catch (_: Throwable) {
                null
            }
        }
        val result = DeviceRelay.fetch(request.url.toString(), request.method, headers, body) ?: return null
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(result.status)
            .message(if (result.status in 200..299) "OK" else "Relayed")
            .body(result.body.toResponseBody(result.contentType?.toMediaTypeOrNull()))
            .apply { result.contentType?.let { header("Content-Type", it) } }
            .build()
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        if (response.header("cf-mitigated") == "challenge") return true
        val peek = try {
            response.peekBody(16 * 1024).string().lowercase()
        } catch (_: Throwable) {
            return false
        }
        return "just a moment" in peek ||
            "cf-chl" in peek ||
            "challenge-platform" in peek ||
            "enable javascript and cookies" in peek
    }
}
