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

    private val json = Json { ignoreUnknownKeys = true }

    // Separate client (no CF interceptor) so solving never recurses.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Solution(val cookieHeader: String, val userAgent: String)

    fun solve(url: String): Solution? {
        if (!enabled) return null
        return try {
            val payload = buildJsonObject {
                put("cmd", "request.get")
                put("url", url)
                put("maxTimeout", 90_000)
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

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host

        // If we've already solved this host, keep sending the solver's UA so the
        // stored cf_clearance cookie is accepted.
        solvedUserAgent[host]?.let { userAgent ->
            request = request.newBuilder().header("User-Agent", userAgent).build()
        }

        val response = chain.proceed(request)
        if (!isChallenge(response)) return response

        // 1) Server-side solve: classic "Just a moment" JS challenges (cheap, fast).
        val solution = FlareSolverr.solve(request.url.toString())
        if (solution != null) {
            response.close()
            injectClearanceCookies(host, solution.cookieHeader)
            solvedUserAgent[host] = solution.userAgent
            return chain.proceed(request.newBuilder().header("User-Agent", solution.userAgent).build())
        }

        // 2) Device-as-egress: for Turnstile (which FlareSolverr can't beat), a
        //    connected phone performs the fetch from its own IP + WebView-cleared
        //    session and streams the bytes back. cf_clearance is IP-locked, so this
        //    is the only free way to serve Turnstile sites.
        val relayed = relayViaDevice(request)
        if (relayed != null) {
            response.close()
            return relayed
        }

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
