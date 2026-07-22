package com.nyora.hasan72341.shared.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Fetches a URL through the macOS app's WKWebView instead of this JVM's OkHttp.
 *
 * A Cloudflare `cf_clearance` is bound to the browser SESSION that solved it — verified that
 * no external client (OkHttp, or curl-impersonate with any JA3/UA) can replay it. The only
 * thing that can use it is the WebView itself, so once a host is CF-solved we route its
 * requests to the app's relay server (NYORA_WEBVIEW_RELAY_URL), which runs `fetch()` inside
 * the solved page and returns the bytes. This is the macOS equivalent of nyora-android
 * routing its WebView requests through the shared network client.
 *
 * No-op on the headless cluster, where NYORA_WEBVIEW_RELAY_URL is unset.
 */
object WebViewRelay {
    private val relayUrl: String? = System.getenv("NYORA_WEBVIEW_RELAY_URL")?.takeIf { it.isNotBlank() }
    val isConfigured: Boolean get() = relayUrl != null

    // Hosts we've solved and should now serve through the WebView. Populated when the app
    // POSTs a clearance to /cloudflare/clearance (see NyoraRestServer.handleCloudflareClearance).
    private val relayHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun enableForHost(host: String) { relayHosts.add(host) }
    fun isRelayHost(host: String): Boolean = relayHosts.contains(host)

    private val json = Json { ignoreUnknownKeys = true }

    // A dedicated client with NO Cloudflare interceptor (so relaying never recurses) and a
    // generous timeout (the WebView fetch + base64 round-trip is slower than a raw request).
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch [request] through the WebView relay and return an OkHttp [Response] built from the
     * relay result, or null if the relay is unavailable or the fetch failed.
     */
    fun fetch(request: Request): Response? {
        val url = relayUrl ?: return null
        val headers = buildJsonObject {
            for (i in 0 until request.headers.size) {
                // The WebView owns Host/Cookie/User-Agent for the solved session; don't override.
                val name = request.headers.name(i)
                if (name.equals("Cookie", true) || name.equals("Host", true) ||
                    name.equals("User-Agent", true) || name.equals("Accept-Encoding", true)
                ) continue
                put(name, request.headers.value(i))
            }
        }
        // Forward the request body (Madara sources browse via a POST to admin-ajax.php — a
        // relay with no body just 403s). Capture the body bytes and its content type.
        val requestBody = request.body
        val bodyBytes: ByteArray? = requestBody?.let { rb ->
            try { okio.Buffer().also { rb.writeTo(it) }.readByteArray() } catch (_: Exception) { null }
        }
        val payload = buildJsonObject {
            put("url", request.url.toString())
            put("method", request.method)
            put("headers", headers)
            if (bodyBytes != null) {
                put("bodyBase64", Base64.getEncoder().encodeToString(bodyBytes))
                requestBody?.contentType()?.let { put("bodyContentType", it.toString()) }
            }
        }
        val relayReq = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val body: JsonObject = try {
            client.newCall(relayReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
            }
        } catch (_: Exception) {
            return null
        }

        val status = body["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val b64 = body["bodyBase64"]?.jsonPrimitive?.content ?: return null
        val bytes = try { Base64.getDecoder().decode(b64) } catch (_: Exception) { return null }
        runCatching {
            val hdrs = body["headers"]?.jsonObject
            fun h(name: String) = hdrs?.get(name)?.jsonPrimitive?.content ?: "-"
            val preview = String(bytes.copyOf(minOf(bytes.size, 600)))
                .replace("\n", " ").replace("\r", "")
            java.io.File("/tmp/nyora-cf.log").appendText(
                "[cf] RELAY resp status=$status len=${bytes.size} " +
                    "cf-mitigated=${h("cf-mitigated")} cf-ray=${h("cf-ray")} server=${h("server")} " +
                    "url=${request.url} body300=${preview.take(300)}\n"
            )
        }
        val contentType = body["headers"]?.jsonObject?.get("content-type")?.jsonPrimitive?.content
            ?: "text/html; charset=utf-8"

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status in 200..299) "OK" else "Relayed")
            .body(bytes.toResponseBody(contentType.toMediaType()))
            .build()
    }
}
