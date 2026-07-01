package com.nyora.hasan72341.shared.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps

@Serializable
data class HelperNetworkSettings(
    val proxyType: String = "direct",
    val proxyAddress: String = "127.0.0.1",
    val proxyPort: Int = 8080,
    val dnsOverHttps: String = "none",
    val githubMirror: String = "KEIYOUSHI",
    val imagesProxy: String = "none",
    val sslBypass: Boolean = false,
    val disableConnectivityCheck: Boolean = false,
)

class HelperNetworkConfig(initial: HelperNetworkSettings = HelperNetworkSettings()) {
    @Volatile
    private var current = initial.normalized()

    fun snapshot(): HelperNetworkSettings = current

    fun update(next: HelperNetworkSettings): HelperNetworkSettings {
        current = next.normalized()
        return current
    }
}

fun HelperNetworkSettings.normalized(): HelperNetworkSettings {
    val cleanedType = proxyType.lowercase()
    val cleanedGithubMirror = githubMirror.uppercase()
    val cleanedImagesProxy = imagesProxy.lowercase()
    val cleanedDns = dnsOverHttps.lowercase()
    return copy(
        proxyType = when (cleanedType) {
            "http", "socks5" -> cleanedType
            else -> "direct"
        },
        proxyAddress = proxyAddress.trim(),
        proxyPort = proxyPort.coerceIn(1, 65535),
        dnsOverHttps = cleanedDns,
        githubMirror = cleanedGithubMirror,
        imagesProxy = cleanedImagesProxy,
    )
}

fun OkHttpClient.Builder.applyNetworkSettings(settings: HelperNetworkSettings): OkHttpClient.Builder {
    buildProxy(settings)?.let { proxy(it) }
    buildDns(settings)?.let { dns(it) }
    if (settings.sslBypass) {
        val trustAll = trustAllTls()
        sslSocketFactory(trustAll.context.socketFactory, trustAll.manager)
        hostnameVerifier { _, _ -> true }
    }
    return this
}

/**
 * Process-wide session cookie jar. Lets a Cloudflare `cf_clearance` (solved by
 * the app's WebView and POSTed to /cloudflare/clearance) persist across all
 * subsequent parser/image requests for that host.
 */
val sharedCookieJar = com.nyora.hasan72341.shared.parser.SimpleCookieJar()

/** Inject solved-challenge cookies for a host into the shared jar. */
fun injectClearanceCookies(domain: String, cookieHeader: String) {
    val url = ("https://" + domain.removePrefix("https://").removePrefix("http://").trimEnd('/') + "/")
        .toHttpUrlOrNull() ?: return
    sharedCookieJar.put(url, cookieHeader)
}

fun buildOkHttpClient(settings: HelperNetworkSettings): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(sharedCookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .applyNetworkSettings(settings)
        .build()

fun fetchText(
    url: String,
    settings: HelperNetworkSettings,
    headers: Map<String, String> = emptyMap(),
): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", defaultHelperUserAgent())
        .apply { headers.forEach { (key, value) -> header(key, value) } }
        .get()
        .build()
    buildOkHttpClient(settings).newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            error("Request failed with HTTP ${response.code}")
        }
        return response.body?.string().orEmpty()
    }
}

fun fetchBytes(
    url: String,
    settings: HelperNetworkSettings,
    headers: Map<String, String> = emptyMap(),
): ByteArray {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", defaultHelperUserAgent())
        .apply { headers.forEach { (key, value) -> header(key, value) } }
        .get()
        .build()
    buildOkHttpClient(settings).newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            error("Request failed with HTTP ${response.code}")
        }
        return response.body?.bytes() ?: ByteArray(0)
    }
}

fun rewriteRemoteUrl(url: String, settings: HelperNetworkSettings, kind: RemoteUrlKind = RemoteUrlKind.General): String {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return url
    var rewritten = rewriteGithubUrl(url, settings.githubMirror)
    if (kind == RemoteUrlKind.Image) {
        rewritten = rewriteImageUrl(rewritten, settings.imagesProxy)
    }
    return rewritten
}

/**
 * Canonical browser User-Agent for ALL outbound helper requests.
 *
 * Cloudflare binds a solved `cf_clearance` cookie to the exact User-Agent (and IP)
 * that solved it. The WebView solver (MacCloudflareSolver.userAgent in Swift) and
 * the native parser fetch path (KotatsuLoaderContext.getDefaultUserAgent) both send this
 * Chrome string, so every request that may carry a cf_clearance cookie — page images
 * and covers via [fetchBytes], catalog HTML via [fetchText] — MUST send it too.
 * Using a different UA here (the old "Nyora/1.0" identifier) made Cloudflare reject
 * image/cover loads immediately after a successful challenge solve.
 *
 * Keep this string byte-identical to MacCloudflareSolver.userAgent and BROWSER_UA.
 */
const val NYORA_BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

fun defaultHelperUserAgent(): String = NYORA_BROWSER_UA

enum class RemoteUrlKind {
    General,
    Image,
}

private fun buildProxy(settings: HelperNetworkSettings): Proxy? {
    if (settings.proxyType == "direct" || settings.proxyAddress.isBlank()) return null
    val type = when (settings.proxyType) {
        "socks5" -> Proxy.Type.SOCKS
        else -> Proxy.Type.HTTP
    }
    return Proxy(type, InetSocketAddress(settings.proxyAddress, settings.proxyPort))
}

private fun buildDns(settings: HelperNetworkSettings): okhttp3.Dns? {
    val provider = settings.dnsOverHttps
    if (provider == "none") return null
    val (endpoint, bootstrapHosts) = when (provider) {
        "cloudflare" -> "https://1.1.1.1/dns-query" to listOf("1.1.1.1", "1.0.0.1")
        "google" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
        "quad9" -> "https://9.9.9.9/dns-query" to listOf("9.9.9.9", "149.112.112.112")
        else -> return null
    }
    val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .apply {
            buildProxy(settings)?.let { proxy(it) }
            if (settings.sslBypass) {
                val trustAll = trustAllTls()
                sslSocketFactory(trustAll.context.socketFactory, trustAll.manager)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()
    return DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url(endpoint.toHttpUrl())
        .bootstrapDnsHosts(*bootstrapHosts.map(InetAddress::getByName).toTypedArray())
        .includeIPv6(false)
        .post(true)
        .build()
}

private fun rewriteGithubUrl(url: String, mirror: String): String {
    if (mirror == "KEIYOUSHI") return url
    val uri = runCatching { URI.create(url) }.getOrNull() ?: return url
    val host = uri.host?.lowercase() ?: return url
    return when (mirror) {
        "FASTGIT" -> when {
            host == "raw.githubusercontent.com" -> url.replace("https://raw.githubusercontent.com/", "https://raw.fastgit.org/")
            host == "github.com" -> url.replace("https://github.com/", "https://hub.fastgit.xyz/")
            else -> url
        }
        "GHPROXY" -> when {
            host.contains("github.com") || host.contains("githubusercontent.com") -> "https://ghproxy.com/$url"
            else -> url
        }
        else -> url
    }
}

private fun rewriteImageUrl(url: String, proxy: String): String {
    return when (proxy) {
        "weserv" -> {
            val withoutScheme = url.removePrefix("https://").removePrefix("http://")
            "https://images.weserv.nl/?url=${URLEncoder.encode(withoutScheme, StandardCharsets.UTF_8)}"
        }
        "statically" -> {
            val withoutScheme = url.removePrefix("https://").removePrefix("http://")
            "https://cdn.statically.io/img/$withoutScheme"
        }
        else -> url
    }
}

private data class TrustAllTls(
    val context: SSLContext,
    val manager: X509TrustManager,
)

private fun trustAllTls(): TrustAllTls {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    return TrustAllTls(context = context, manager = trustManager)
}
