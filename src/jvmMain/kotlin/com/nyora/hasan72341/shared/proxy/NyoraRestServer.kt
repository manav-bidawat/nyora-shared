package com.nyora.hasan72341.shared.proxy

import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.data.ExtensionInstaller
import com.nyora.hasan72341.shared.data.SourceCatalogClient
import com.nyora.hasan72341.shared.download.DownloadFormat
import com.nyora.hasan72341.shared.download.DownloadSettings
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.net.HelperNetworkSettings
import com.nyora.hasan72341.shared.net.SsrfGuard
import com.nyora.hasan72341.shared.model.Library
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.reader.PageImageLoader
import com.sun.net.httpserver.Filter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * REST surface for the SwiftUI client.
 *
 * Endpoints (all loopback):
 *
 *  GET    /health
 *  GET    /sources
 *  POST   /sources/refresh
 *  POST   /sources/install?id=
 *  DELETE /sources/uninstall?id=
 *  POST   /sources/pin?id=
 *  GET    /sources/popular?id=&page=
 *  GET    /sources/latest?id=&page=
 *  GET    /sources/search?id=&q=&page=
 *  GET    /manga/details?id=&url=
 *  GET    /manga/pages?id=&url=
 *  GET    /image?u=<url>&h=<header-name>:<value>&h=...
 */
class NyoraRestServer(
    private val facade: NyoraFacade,
    private val catalog: SourceCatalogClient,
    private val installer: ExtensionInstaller,
    private val pageLoader: PageImageLoader = PageImageLoader(),
    private val downloads: com.nyora.hasan72341.shared.download.DownloadManager? = null,
    private val networkConfig: HelperNetworkConfig = HelperNetworkConfig(),
    private val host: InetAddress = InetAddress.getLoopbackAddress(),
    // --- Web-mode options (defaults preserve desktop/loopback behaviour) ---
    // Fixed port to bind (0 = ephemeral, as the desktop clients expect).
    private val port: Int = 0,
    // Classpath prefix (e.g. "web") for static UI assets. When set, the
    // catch-all "/" route serves the bundled single-page web app instead of
    // returning JSON. Serving the UI from the same origin as the API is what
    // lets a normal browser talk to the parser without tripping CORS.
    private val staticRoot: String? = null,
    // Emit permissive CORS headers on responses. Always on: the hosted API
    // (api.hasanraza.tech) is consumed cross-origin by the web app (and third
    // parties); harmless for the same-origin desktop/localhost helper. Set
    // NYORA_CORS=0 to disable.
    private val corsEnabled: Boolean = System.getenv("NYORA_CORS") != "0",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private var server: HttpServer? = null

    // Background worker for serve-stale-while-revalidate. SINGLE thread on purpose:
    // a stale Cloudflare-protected source triggers a FlareSolverr (headless Chrome)
    // solve, and firing several at once swamps the small VM. Serializing to one
    // keeps at most one solve in flight. Daemon so it never blocks exit.
    private val backgroundExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nyora-revalidate").apply { isDaemon = true }
    }
    private val revalidating = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val baseUrl: String
        get() = server?.address?.let { "http://${it.address.hostAddress}:${it.port}" }.orEmpty()

    val isRunning: Boolean
        get() = server != null

    fun start(): String {
        if (server != null) return baseUrl
        val httpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
            guardedContext("/health") { respondJson(it, 200, buildJsonObject { put("status", "ok") }) }
            guardedContext("/docs") { handleDocs(it) }
            guardedContext("/openapi.yaml") { handleOpenApiSpec(it) }
            guardedContext("/device/relay/poll") { handleDeviceRelayPoll(it) }
            guardedContext("/device/relay/result") { handleDeviceRelayResult(it) }
            guardedContext("/sources/refresh") { handleRefresh(it) }
            guardedContext("/sources/catalog") { handleCatalog(it) }
            guardedContext("/sources/install") { handleInstall(it) }
            guardedContext("/sources/uninstall") { handleUninstall(it) }
            guardedContext("/sources/pin") { handlePin(it) }
            guardedContext("/sources/filters") { handleFilters(it) }
            guardedContext("/sources/popular") { handleBrowse(it, BrowseMode.POPULAR) }
            guardedContext("/sources/latest") { handleBrowse(it, BrowseMode.LATEST) }
            guardedContext("/sources/search") { handleBrowse(it, BrowseMode.SEARCH) }
            guardedContext("/cloudflare/clearance") { handleCloudflareClearance(it) }
            guardedContext("/sources") { handleSources(it) }
            guardedContext("/manga/details") { handleDetails(it) }
            guardedContext("/manga/pages") { handlePages(it) }
            guardedContext("/image") { handleImage(it) }
            guardedContext("/library/history/record") { handleHistoryRecord(it) }
            guardedContext("/library/history/remove") { handleHistoryRemove(it) }
            guardedContext("/library/history/clear") { handleHistoryClear(it) }
            guardedContext("/library/history") { handleHistory(it) }
            guardedContext("/library/clear") { handleLibraryClear(it) }
            guardedContext("/library/favourites/toggle") { handleFavouriteToggle(it) }
            guardedContext("/library/favourites/check") { handleFavouriteCheck(it) }
            guardedContext("/library/favourites") { handleFavourites(it) }
            guardedContext("/library/bookmarks/add") { handleBookmarkAdd(it) }
            guardedContext("/library/bookmarks/remove") { handleBookmarkRemove(it) }
            guardedContext("/library/bookmarks/check") { handleBookmarkCheck(it) }
            guardedContext("/library/bookmarks") { handleBookmarks(it) }
            guardedContext("/library/updates/refresh") { handleUpdatesRefresh(it) }
            guardedContext("/library/updates/seen") { handleUpdatesSeen(it) }
            guardedContext("/library/updates") { handleUpdates(it) }
            guardedContext("/local/scan") { handleLocalScan(it) }
            guardedContext("/local/chapter") { handleLocalChapter(it) }
            guardedContext("/local/image") { handleLocalImage(it) }
            guardedContext("/search/global") { handleGlobalSearch(it) }
            guardedContext("/library/categories/create") { handleCategoryCreate(it) }
            guardedContext("/library/categories/rename") { handleCategoryRename(it) }
            guardedContext("/library/categories/delete") { handleCategoryDelete(it) }
            guardedContext("/library/categories/add") { handleCategoryAdd(it) }
            guardedContext("/library/categories/remove") { handleCategoryRemove(it) }
            guardedContext("/library/categories/manga") { handleCategoriesForManga(it) }
            guardedContext("/library/categories") { handleCategories(it) }
            guardedContext("/manga/prefs/save") { handleMangaPrefsSave(it) }
            guardedContext("/manga/prefs/clear") { handleMangaPrefsClear(it) }
            guardedContext("/manga/prefs") { handleMangaPrefs(it) }
            guardedContext("/downloads/start") { handleDownloadStart(it) }
            guardedContext("/downloads/enqueue") { handleDownloadEnqueue(it) }
            guardedContext("/downloads/cancel") { handleDownloadCancel(it) }
            guardedContext("/downloads/settings") { handleDownloadSettings(it) }
            guardedContext("/settings/network") { handleNetworkSettings(it) }
            guardedContext("/downloads") { handleDownloads(it) }
            guardedContext("/stats") { handleStats(it) }
            guardedContext("/suggestions") { handleSuggestions(it) }
            guardedContext("/manga/alternatives") { handleAlternatives(it) }
            guardedContext("/backup/export") { handleBackupExport(it) }
            guardedContext("/backup/import") { handleBackupImport(it) }
            guardedContext("/tracker/anilist/search") { handleAniListSearch(it) }
            guardedContext("/tracker/anilist/scrobble") { handleAniListScrobble(it) }
            guardedContext("/tracker/myanimelist/search") { handleMalSearch(it) }
            guardedContext("/tracker/myanimelist/scrobble") { handleMalScrobble(it) }
            guardedContext("/tracker/kitsu/search") { handleKitsuSearch(it) }
            guardedContext("/tracker/kitsu/scrobble") { handleKitsuScrobble(it) }
            guardedContext("/tracker/shikimori/search") { handleShikimoriSearch(it) }
            guardedContext("/tracker/shikimori/scrobble") { handleShikimoriScrobble(it) }
            guardedContext("/supabase/status") { handleSupabaseStatus(it) }
            guardedContext("/supabase/signin") { handleSupabaseSignIn(it) }
            guardedContext("/supabase/register") { handleSupabaseRegister(it) }
            guardedContext("/supabase/signout") { handleSupabaseSignOut(it) }
            guardedContext("/supabase/sync") { handleSupabaseSync(it) }
            guardedContext("/supabase/restore-from-cloud") { handleSupabaseRestoreFromCloud(it) }
            guardedContext("/supabase/has-local-data") { handleSupabaseHasLocalData(it) }
            guardedContext("/ota/check") { handleOtaCheck(it) }
            guardedContext("/ota/status") { handleOtaStatus(it) }
            guardedContext("/") { handleRoot(it) }
            executor = newServerExecutor()
            start()
        }
        server = httpServer
        prewarmCache()
        return baseUrl
    }

    /** Pre-warm the browse cache for installed sources in the background right
     *  after startup, so the very first user request is already instant (no cold
     *  fetch). Cheap, bounded, best-effort; failures are ignored. Skips keys the
     *  persistent snapshot already restored. Disable with NYORA_PREWARM=0. */
    private fun prewarmCache() {
        // OPT-IN (NYORA_PREWARM=1): on the tiny hosted VM, eagerly fetching many
        // sources at boot triggers a storm of FlareSolverr (Chrome) solves for
        // CF-protected sources and saturates the box. The persistent snapshot +
        // serve-stale already make restarts instant, so pre-warm is off by default.
        if (System.getenv("NYORA_PREWARM") != "1") return
        // ONE low-priority background thread, SEQUENTIAL with a gap between fetches
        // so it never spikes CPU on the small box. Skips any key that already has a
        // cached copy (fresh OR stale) — serve-stale-while-revalidate refreshes
        // those lazily on demand, so after the first snapshot this is a near no-op.
        val t = Thread({
            try {
                val sources = try { facade.listSources().filter { it.isInstalled } } catch (_: Throwable) { emptyList() }
                for (source in sources.take(40)) {
                    for (mode in listOf(BrowseMode.POPULAR, BrowseMode.LATEST)) {
                        val key = "browse:${mode.name}:${source.id}:1:"
                        if (ResponseCache.peek(key) != null) continue // already cached — leave it to serve-stale
                        try { fetchBrowseBody(source, mode, 1, "", emptyList(), key) } catch (_: Throwable) { /* skip */ }
                        try { Thread.sleep(400) } catch (_: InterruptedException) { return@Thread } // gentle pacing
                    }
                }
            } catch (_: Throwable) { /* best-effort */ }
        }, "nyora-prewarm")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleRoot(exchange: HttpExchange) {
        // Web mode: the catch-all route serves the bundled single-page app and
        // its static assets straight from the classpath. Anything that isn't a
        // real asset falls back to index.html so client-side routing works.
        if (staticRoot != null) {
            serveStatic(exchange)
            return
        }
        respondJson(exchange, 200, buildJsonObject {
            put("name", "Nyora helper")
            put("baseUrl", baseUrl)
        })
    }

    private fun serveStatic(exchange: HttpExchange) {
        applyCors(exchange)
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            exchange.sendResponseHeaders(204, -1); exchange.close(); return
        }
        val rawPath = exchange.requestURI.path.orEmpty()
        // Normalise and guard against path traversal.
        val rel = rawPath.trimStart('/').substringBefore('?').ifBlank { "index.html" }
        if (rel.contains("..")) { respondText(exchange, 400, "Bad path"); return }
        val loader = javaClass.classLoader
        val prefix = staticRoot!!.trim('/')
        val bytes = loader.getResourceAsStream("$prefix/$rel")?.use { it.readBytes() }
            // SPA fallback for unknown non-asset routes.
            ?: loader.getResourceAsStream("$prefix/index.html")?.use { it.readBytes() }
            ?: return respondText(exchange, 404, "Not found")
        val servedRel = if (loader.getResource("$prefix/$rel") != null) rel else "index.html"
        exchange.responseHeaders.add("Content-Type", staticContentType(servedRel))
        if (servedRel == "index.html") {
            exchange.responseHeaders.add("Cache-Control", "no-cache")
        } else {
            exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
        }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun staticContentType(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        path.endsWith(".webmanifest") -> "application/manifest+json"
        path.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }

    /** Serves the embedded OpenAPI 3.1 spec (classpath `openapi.yaml`). */
    private fun handleOpenApiSpec(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val bytes = javaClass.classLoader.getResourceAsStream("openapi.yaml")?.use { it.readBytes() }
            ?: return respondText(exchange, 404, "openapi.yaml not bundled")
        applyCors(exchange)
        exchange.responseHeaders.add("Content-Type", "application/yaml; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Serves a self-contained Swagger UI page that loads the spec from /openapi.yaml. */
    private fun handleDocs(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val bytes = SWAGGER_UI_HTML.toByteArray(StandardCharsets.UTF_8)
        applyCors(exchange)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // MARK: - Device-as-egress relay (Cloudflare Turnstile)

    /** Device long-poll: block up to 25s for the next fetch task. 200 with a task
     *  (JSON), or 200 with `{}` when idle (device just polls again). */
    private fun handleDeviceRelayPoll(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val task = com.nyora.hasan72341.shared.net.DeviceRelay.poll(25_000)
        if (task == null) {
            respondJson(exchange, 200, buildJsonObject {})
            return
        }
        respondJson(exchange, 200, buildJsonObject {
            put("id", task.id)
            put("url", task.url)
            put("method", task.method)
            put("headers", buildJsonObject { task.headers.forEach { (key, value) -> put(key, value) } })
            task.body?.let { put("bodyB64", java.util.Base64.getEncoder().encodeToString(it)) }
        })
    }

    /** Device delivers a completed fetch: {id, status, contentType?, bodyB64}. */
    private fun handleDeviceRelayResult(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val raw = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val obj = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            respondError(exchange, 400, "Invalid JSON"); return
        }
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
            ?: return respondError(exchange, 400, "Missing id")
        val status = obj["status"]?.jsonPrimitive?.intOrNull ?: 200
        val contentType = obj["contentType"]?.jsonPrimitive?.contentOrNull
        val body = obj["bodyB64"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            ?: ByteArray(0)
        com.nyora.hasan72341.shared.net.DeviceRelay.complete(
            id,
            com.nyora.hasan72341.shared.net.DeviceRelay.Result(status, contentType, body)
        )
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun applyCors(exchange: HttpExchange) {
        if (!corsEnabled) return
        val headers = exchange.responseHeaders
        if (headers.containsKey("Access-Control-Allow-Origin")) return
        headers.add("Access-Control-Allow-Origin", "*")
        headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        headers.add("Access-Control-Allow-Headers", "Content-Type")
    }

    /** If this is a CORS preflight (OPTIONS), answer it 204 with CORS headers and
     *  return true so the caller stops. Non-simple requests (POST + JSON body)
     *  preflight, so POST handlers must call this before their method check. */
    private fun maybePreflight(exchange: HttpExchange): Boolean {
        if (!exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) return false
        applyCors(exchange)
        exchange.sendResponseHeaders(204, -1)
        exchange.close()
        return true
    }

    private fun handleSources(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val sources = facade.listSources().filter(::isJsParserSource)
        respondJson(exchange, 200, json.encodeToJsonElement(SourceListResponse(sources)))
    }

    private fun handleRefresh(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        try {
            val library = facade.loadLibrary()
            val existingById = library.sources.associateBy { it.id }
            val jsSources = getJsSources()
            val merged = jsSources.map { src ->
                val existing = existingById[src.id]
                if (existing != null) {
                    src.copy(
                        isInstalled = existing.isInstalled,
                        isPinned = existing.isPinned,
                        localPath = src.localPath,
                        installedAt = existing.installedAt,
                    )
                } else src
            }
            facade.saveLibrary(library.copy(sources = merged))
            val visible = facade.listSources().filter(::isJsParserSource)
            respondJson(exchange, 200, json.encodeToJsonElement(SourceListResponse(visible)))
        } catch (error: Exception) {
            respondError(exchange, 500, error.message ?: "Refresh failed")
        }
    }

    private fun getJsSources(): List<com.nyora.hasan72341.shared.model.MangaSource> =
        com.nyora.hasan72341.shared.extension.nativeParserCatalog()

    private fun handleCatalog(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        ResponseCache.get("catalog")?.let { respondJsonRaw(exchange, 200, it); return }
        val installed = facade.listSources().filter { it.isInstalled }.map { it.id }.toSet()
        val entries = getJsSources().map { source ->
            CatalogEntry(
                id = source.id,
                name = source.name,
                lang = source.lang,
                engine = source.engine.name,
                contentType = source.contentType.name,
                isBroken = false,
                isInstalled = source.id in installed,
                isNsfw = source.isNsfw,
            )
        }
        val body = json.encodeToString(CatalogResponse.serializer(), CatalogResponse(entries))
        ResponseCache.put("catalog", body, 300_000) // 5 min; invalidated on install/uninstall
        respondJsonRaw(exchange, 200, body)
    }

    private fun handleInstall(exchange: HttpExchange) {
        if (maybePreflight(exchange)) return
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        ResponseCache.invalidate("catalog") // installed flags change
        val id = exchange.query()["id"]
        if (id.isNullOrBlank()) {
            respondError(exchange, 400, "Missing 'id'"); return
        }
        if (!id.startsWith("parser:")) {
            respondError(exchange, 404, "Unknown JS parser source: $id"); return
        }
        // Already in DB? Use the existing row (covers JS sources too).
        val existing = facade.listSources().firstOrNull { it.id == id }
        if (existing != null) {
            try {
                val flipped = existing.copy(
                    isInstalled = true,
                    engine = com.nyora.hasan72341.shared.model.SourceEngine.Parser,
                    localPath = "",
                    installedAt = System.currentTimeMillis(),
                )
                val installed = facade.installSource(flipped) { it }
                respondJson(exchange, 200, json.encodeToJsonElement(SourceResponse(installed)))
            } catch (error: Exception) {
                respondError(exchange, 500, error.message ?: "Install failed")
            }
            return
        }
        // Not in DB yet — accept install for a catalog parser source.
        val jsSource = getJsSources().firstOrNull { it.id == id }
            ?: return respondError(exchange, 404, "Unknown parser source: $id")
            
        try {
            val sourceToInstall = jsSource.copy(
                isInstalled = true,
                installedAt = System.currentTimeMillis()
            )
            val installedResult = facade.installSource(sourceToInstall) { it }
            respondJson(exchange, 200, json.encodeToJsonElement(SourceResponse(installedResult)))
        } catch (error: Exception) {
            respondError(exchange, 500, error.message ?: "Install failed")
        }
    }

    private fun isJsParserSource(source: com.nyora.hasan72341.shared.model.MangaSource): Boolean =
        source.id.startsWith("parser:") &&
            source.engine == com.nyora.hasan72341.shared.model.SourceEngine.Parser

    private fun handleUninstall(exchange: HttpExchange) {
        if (maybePreflight(exchange)) return
        if (!exchange.requestMethod.equals("DELETE", ignoreCase = true) &&
            !exchange.requestMethod.equals("POST", ignoreCase = true)
        ) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        ResponseCache.invalidate("catalog") // installed flags change
        val id = exchange.query()["id"]
        if (id.isNullOrBlank()) {
            respondError(exchange, 400, "Missing 'id'"); return
        }
        val source = facade.listSources().firstOrNull { it.id == id }
            ?: return respondError(exchange, 404, "Unknown source: $id")
        if (!source.canUninstall) {
            respondError(exchange, 409, "Source is built-in and cannot be uninstalled"); return
        }
        try {
            val uninstalled = facade.installSource(source, installer::uninstall)
            respondJson(exchange, 200, json.encodeToJsonElement(SourceResponse(uninstalled)))
        } catch (error: Exception) {
            respondError(exchange, 500, error.message ?: "Uninstall failed")
        }
    }

    private fun handlePin(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val id = exchange.query()["id"]
        if (id.isNullOrBlank()) {
            respondError(exchange, 400, "Missing 'id'"); return
        }
        facade.togglePin(id)
        respondJson(exchange, 200, json.encodeToJsonElement(SourceListResponse(facade.listSources())))
    }

    private enum class BrowseMode { POPULAR, LATEST, SEARCH }

    /**
     * Receives a Cloudflare clearance solved by the app's WebView:
     *   POST /cloudflare/clearance?domain=example.com
     *   body: "cf_clearance=...; other=..."  (a Cookie header string)
     * Injected into the shared OkHttp cookie jar so subsequent parser/image
     * requests to that host pass the challenge. The WebView must solve with the
     * same User-Agent the helper sends (Chrome 115) so the cookie stays valid.
     */
    private fun handleCloudflareClearance(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val domain = exchange.query()["domain"]
        if (domain.isNullOrBlank()) { respondError(exchange, 400, "Missing 'domain'"); return }
        val cookieHeader = exchange.requestBody.readBytes().toString(Charsets.UTF_8).trim()
        if (cookieHeader.isBlank()) { respondError(exchange, 400, "Missing cookie body"); return }
        com.nyora.hasan72341.shared.net.injectClearanceCookies(domain, cookieHeader)
        respondJson(exchange, 200, buildJsonObject {
            put("ok", true)
            put("domain", domain)
        })
    }

    /**
     * Rewrite a cover URL to flow through the helper's own /image proxy with the
     * source site as Referer — exactly what the web app does (proxyImage). Many
     * cover CDNs hotlink-protect or sit behind Cloudflare, so a direct load from
     * the app shows a blank cover; proxying adds the Referer (and the shared cookie
     * jar / cf_clearance). Only applied to RESPONSE bodies — never persisted (the
     * loopback port changes per launch), so the DB keeps the raw URL.
     */
    private fun proxyCoverUrl(raw: String?, sourceBaseUrl: String): String {
        val c = raw?.trim().orEmpty()
        if (c.isEmpty() || c.startsWith("data:")) return c
        val port = server?.address?.port ?: return c
        val base = "http://127.0.0.1:$port"
        if (c.startsWith("$base/image")) return c
        val enc = java.net.URLEncoder.encode(c, "UTF-8")
        val domain = sourceBaseUrl.trim().trimEnd('/')
        val ref = if (domain.isNotEmpty()) "&h=" + java.net.URLEncoder.encode("Referer:$domain/", "UTF-8") else ""
        return "$base/image?u=$enc$ref"
    }

    // Manga ids we've already tried (and failed) to backfill a cover for this
    // session — avoids re-searching a source on every history refresh.
    private val coverResolveAttempted =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Cover URL for a history row, proxied with the source Referer.
     *
     * Synced/legacy history manga frequently have a blank `cover_url` (the
     * device that recorded them never captured it). Explore shows covers because
     * the parser's search/list results include them — so when a cover is
     * missing, fetch it the same way (search the source by title), persist it
     * via upsert (one-time cost), and proxy it. Unknown-source rows stay blank.
     */
    private fun historyCoverUrl(row: com.nyora.hasan72341.shared.repository.HistoryRow): String {
        val manga = row.manga
        if (manga.coverUrl.isNotBlank()) {
            return proxyCoverUrl(manga.coverUrl, sourceBaseUrlFor(manga.source))
        }
        val sid = openableHistorySourceId(manga.source, row.sourceId)
        if (sid.isEmpty() || !coverResolveAttempted.add(manga.id)) return ""
        val source = facade.listSources().firstOrNull { it.id == sid } ?: return ""
        return try {
            val service = openInstalled(source)
            val results = runBlocking { service.search(manga.title, 1, emptyList()).entries }
            fun slugBase(u: String) =
                u.trimEnd('/').substringAfterLast('/').substringBeforeLast('-')
            val match = results.firstOrNull { it.title.equals(manga.title, ignoreCase = true) }
                ?: results.firstOrNull { slugBase(it.url) == slugBase(manga.url) }
                ?: results.firstOrNull()
            val cover = match?.coverUrl.orEmpty()
            if (cover.isNotBlank()) {
                // Persist so the next history load is instant.
                facade.upsertManga(manga.copy(coverUrl = cover))
                proxyCoverUrl(cover, source.baseUrl)
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** baseUrl of the installed source backing a manga's source ref ("" if unknown). */
    private fun sourceBaseUrlFor(ref: com.nyora.hasan72341.shared.model.MangaSourceRef): String {
        val sid = openableHistorySourceId(ref, "")
        if (sid.isEmpty()) return ""
        return facade.listSources().firstOrNull { it.id == sid }?.baseUrl ?: ""
    }

    /**
     * Rewrite a reader page image URL to flow through the helper's /image proxy
     * with the source site as Referer — the same hotlink/Cloudflare workaround
     * we apply to covers, but for ALL page images of EVERY source (many sources
     * 403 image requests that lack a Referer, leaving the reader/thumbnails
     * blank). Any per-page headers the parser emitted are forwarded too.
     */
    private fun proxyPageUrl(page: MangaPage, sourceBaseUrl: String): MangaPage {
        val raw = page.url.trim()
        if (raw.isEmpty() || raw.startsWith("data:")) return page
        // Absolutize a relative image path (some Madara sites like MangaEclipse
        // return /wp-content/… ; already-cached pages may still hold relative
        // paths) against the source base URL so /image?u= receives a real URL.
        val c = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") && sourceBaseUrl.isNotBlank() -> sourceBaseUrl.trimEnd('/') + raw
            else -> raw
        }
        val port = server?.address?.port ?: return page
        val base = "http://127.0.0.1:$port"
        if (c.startsWith("$base/image")) return page
        val enc = java.net.URLEncoder.encode(c, "UTF-8")
        val domain = sourceBaseUrl.trim().trimEnd('/')
        val params = StringBuilder()
        val hasReferer = page.headers.keys.any { it.equals("Referer", ignoreCase = true) }
        if (domain.isNotEmpty() && !hasReferer) {
            params.append("&h=").append(java.net.URLEncoder.encode("Referer:$domain/", "UTF-8"))
        }
        page.headers.forEach { (k, v) ->
            params.append("&h=").append(java.net.URLEncoder.encode("$k:$v", "UTF-8"))
        }
        return page.copy(url = "$base/image?u=$enc$params")
    }

    private fun handleBrowse(exchange: HttpExchange, mode: BrowseMode) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val id = params["id"] ?: return respondError(exchange, 400, "Missing 'id'")
        val page = params["page"]?.toIntOrNull() ?: 1
        // Cache check FIRST — a hit must skip the source lookup below, because
        // facade.listSources() serializes under concurrency (DB/lock) and would
        // otherwise bottleneck cached hits. Popular/Latest are identical for every
        // user; Search is query/user-specific → never cached.
        val cacheKey = if (mode != BrowseMode.SEARCH) "browse:${mode.name}:$id:$page:${params["f"].orEmpty()}" else null
        // Serve-stale-while-revalidate: any cached copy (even expired) is returned
        // INSTANTLY so the user never waits on a cold upstream fetch; a stale copy
        // triggers a background refresh. Only a truly-never-seen key fetches inline.
        if (cacheKey != null) {
            ResponseCache.peek(cacheKey)?.let { hit ->
                respondJsonRaw(exchange, 200, hit.value)
                if (hit.stale) revalidateBrowse(cacheKey, id, mode, page, params["f"])
                return
            }
        }
        val source = facade.listSources().firstOrNull { it.id == id }
            ?: return respondError(exchange, 404, "Unknown source: $id")
        val filters = parseFilters(params["f"])
        try {
            val body = fetchBrowseBody(source, mode, page, params["q"].orEmpty(), filters, cacheKey)
            respondJsonRaw(exchange, 200, body)
        } catch (error: Exception) {
            // Global search fans out across hundreds of sources; a blocked/dead/
            // timing-out one should yield NO results (empty 200), not a 500 that
            // floods the client console. Single-source browse still surfaces 500.
            if (mode == BrowseMode.SEARCH) {
                respondJsonRaw(exchange, 200, json.encodeToString(BrowseResponse.serializer(), BrowseResponse(emptyList(), false)))
            } else {
                respondError(exchange, 500, error.message ?: "Browse failed")
            }
        }
    }

    /** Open a source's extension, forcing the installed flag — the native parsers
     *  are all bundled, so the isInstalled require() is just a UI guard that would
     *  otherwise 500 any not-yet-installed source (common with all-enabled). */
    private fun openInstalled(source: MangaSource) =
        facade.openExtension(if (source.isInstalled) source else source.copy(isInstalled = true))

    /** Fetch a browse page from the source, serialize it, and cache non-empty
     *  results. Shared by the inline path, background revalidation, and pre-warm. */
    private fun fetchBrowseBody(
        source: MangaSource,
        mode: BrowseMode,
        page: Int,
        query: String,
        filters: List<com.nyora.hasan72341.shared.extension.SourceFilter>,
        cacheKey: String?,
    ): String {
        val service = openInstalled(source)
        // Mark search fetches so CloudflareInterceptor skips CF-solving (fail fast on
        // blocked sources) — a broad all-source search must never trigger a Chrome
        // storm. Covers concurrent fan-out via a counter.
        val isSearch = mode == BrowseMode.SEARCH
        if (isSearch) com.nyora.hasan72341.shared.net.CloudflareInterceptor.searchFanoutActive.incrementAndGet()
        val result = try {
            runBlocking {
                when (mode) {
                    BrowseMode.POPULAR -> service.getPopular(page)
                    BrowseMode.LATEST -> service.getLatest(page)
                    BrowseMode.SEARCH -> service.search(query, page, filters)
                }
            }
        } finally {
            if (isSearch) com.nyora.hasan72341.shared.net.CloudflareInterceptor.searchFanoutActive.decrementAndGet()
        }
        val body = json.encodeToString(
            BrowseResponse.serializer(),
            BrowseResponse(
                entries = result.entries.map { it.copy(coverUrl = proxyCoverUrl(it.coverUrl, source.baseUrl)) },
                hasNextPage = result.hasNextPage,
            ),
        )
        if (cacheKey != null && result.entries.isNotEmpty()) {
            ResponseCache.put(cacheKey, body, 600_000) // 10 min fresh window
        }
        return body
    }

    /** Refresh a stale browse entry in the background so the NEXT request is fresh
     *  — the current request already got an instant (stale) response. */
    private fun revalidateBrowse(cacheKey: String, id: String, mode: BrowseMode, page: Int, filtersParam: String?) {
        if (!revalidating.add(cacheKey)) return // one refresh at a time per key
        backgroundExecutor.execute {
            try {
                val source = facade.listSources().firstOrNull { it.id == id } ?: return@execute
                fetchBrowseBody(source, mode, page, "", parseFilters(filtersParam), cacheKey)
            } catch (_: Throwable) { /* keep the stale copy on failure */ } finally {
                revalidating.remove(cacheKey)
            }
        }
    }

    private fun handleDetails(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val id = params["id"] ?: return respondError(exchange, 400, "Missing 'id'")
        val url = params["url"] ?: return respondError(exchange, 400, "Missing 'url'")
        val source = facade.listSources().firstOrNull { it.id == id }
            ?: return respondError(exchange, 404, "Unknown source: $id")
        try {
            val service = openInstalled(source)
            val details = runBlocking { service.getDetails(url) }
            val manga = details.manga.copy(
                source = com.nyora.hasan72341.shared.model.MangaSourceRef.Parser(source.id.removePrefix("parser:")),
                chapters = details.chapters,
            )
            facade.upsertManga(manga)
            respondJson(exchange, 200, json.encodeToJsonElement(
                DetailsResponse(
                    manga = manga.copy(coverUrl = proxyCoverUrl(manga.coverUrl, source.baseUrl)),
                    chapters = details.chapters,
                ),
            ))
        } catch (error: Exception) {
            respondError(exchange, 500, error.message ?: "Details failed")
        }
    }

    private fun handlePages(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val id = params["id"] ?: return respondError(exchange, 400, "Missing 'id'")
        val url = params["url"] ?: return respondError(exchange, 400, "Missing 'url'")
        val refresh = params["refresh"]?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        val source = facade.listSources().firstOrNull { it.id == id }
            ?: return respondError(exchange, 404, "Unknown source: $id")
        if (!refresh) {
            facade.cachedPages(url)?.let { cached ->
                val proxied = cached.map { proxyPageUrl(it, source.baseUrl) }
                respondJson(exchange, 200, json.encodeToJsonElement(PagesResponse(pages = proxied)))
                return
            }
        }
        try {
            val service = openInstalled(source)
            val chapter = MangaChapter(id = url, title = url, url = url)
            val pages = runBlocking { service.getPageList(chapter) }
            // Cache the RAW pages (the loopback port changes per launch, so we
            // must not persist proxied URLs). `id` here is the source id; we
            // don't know the manga id at this point, so leave it as the source
            // id — the cache uses chapter_url as the unique key.
            facade.cachePages(url, id, pages)
            val proxied = pages.map { proxyPageUrl(it, source.baseUrl) }
            respondJson(exchange, 200, json.encodeToJsonElement(PagesResponse(pages = proxied)))
        } catch (error: Exception) {
            respondError(exchange, 500, error.message ?: "Pages failed")
        }
    }

    // MARK: - library: history

    private fun handleHistory(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val limit = exchange.query()["limit"]?.toIntOrNull() ?: 100
        val rows = facade.history(limit).map { row ->
            // The manga's source ref is the authoritative identity (mirrors how
            // Android resolves a source by name). The stored `source_id` column
            // is unreliable on synced/legacy rows — it can hold a stale id or
            // even a Kotlin class string like
            // "com.nyora...MangaSourceRef.Unknown". We now run JS extensions
            // only (Java/Mihon was ditched), so resolve to a JS-openable id and
            // refuse to emit garbage that the client would forward to details().
            val resolvedSourceId = openableHistorySourceId(row.manga.source, row.sourceId)
            val sourceLabel = facade.listSources().firstOrNull { it.id == resolvedSourceId }?.name
                ?: (row.manga.source as? com.nyora.hasan72341.shared.model.MangaSourceRef.Parser)?.name
                ?: (row.manga.source as? com.nyora.hasan72341.shared.model.MangaSourceRef.Script)?.name
                ?: row.manga.source.name
            HistoryRowDto(
                mangaId = row.manga.id,
                mangaUrl = row.manga.url,
                mangaTitle = row.manga.title,
                mangaCoverUrl = historyCoverUrl(row),
                sourceId = resolvedSourceId,
                sourceName = sourceLabel,
                chapterId = row.chapterId,
                chapterTitle = row.chapterTitle,
                page = row.page,
                percent = row.percent,
                updatedAt = row.updatedAt,
            )
        }
        respondJson(exchange, 200, json.encodeToJsonElement(HistoryResponse(rows)))
    }

    private fun handleHistoryRecord(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val mangaId = params["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val sourceId = params["sourceId"].orEmpty()
        val chapterId = params["chapterId"].orEmpty()
        val chapterTitle = params["chapterTitle"].orEmpty()
        val page = params["page"]?.toIntOrNull() ?: 0
        val percent = params["percent"]?.toFloatOrNull() ?: 0f
        facade.recordHistory(mangaId, sourceId, chapterId, chapterTitle, page, percent)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleHistoryRemove(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val mangaId = params["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        facade.removeHistory(mangaId)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleHistoryClear(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        facade.clearHistory()
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleLibraryClear(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        facade.clearDatabase()
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    // MARK: - library: favourites

    private fun handleFavourites(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        // Proxy each cover through /image with the source Referer so hotlink-
        // protected thumbnails render for every source (same as browse/details).
        val favs = facade.favourites().map { m ->
            m.copy(coverUrl = proxyCoverUrl(m.coverUrl, sourceBaseUrlFor(m.source)))
        }
        respondJson(exchange, 200, json.encodeToJsonElement(FavouritesResponse(favs)))
    }

    private fun handleFavouriteToggle(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val mangaId = exchange.query()["mangaId"]
            ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val nowFavourited = facade.toggleFavourite(mangaId)
        respondJson(exchange, 200, buildJsonObject { put("favourited", nowFavourited) })
    }

    private fun handleFavouriteCheck(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val mangaId = exchange.query()["mangaId"]
            ?: return respondError(exchange, 400, "Missing 'mangaId'")
        respondJson(exchange, 200, buildJsonObject { put("favourited", facade.isFavourited(mangaId)) })
    }

    // MARK: - per-manga reader prefs

    private fun handleMangaPrefs(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val mangaId = exchange.query()["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val p = facade.mangaPrefs(mangaId)
        if (p == null) {
            respondJson(exchange, 200, buildJsonObject { put("present", false) })
        } else {
            respondJson(exchange, 200, json.encodeToJsonElement(MangaPrefsDto(
                mangaId = p.mangaId, readerMode = p.readerMode,
                brightness = p.brightness, contrast = p.contrast,
                saturation = p.saturation, hue = p.hue, palette = p.palette,
                present = true,
            )))
        }
    }

    private fun handleMangaPrefsSave(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val p = exchange.query()
        val mangaId = p["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        facade.saveMangaPrefs(com.nyora.hasan72341.shared.repository.MangaPrefsRow(
            mangaId = mangaId,
            readerMode = p["readerMode"].orEmpty(),
            brightness = p["brightness"]?.toDoubleOrNull() ?: 0.0,
            contrast = p["contrast"]?.toDoubleOrNull() ?: 1.0,
            saturation = p["saturation"]?.toDoubleOrNull() ?: 1.0,
            hue = p["hue"]?.toDoubleOrNull() ?: 0.0,
            palette = p["palette"].orEmpty(),
        ))
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleMangaPrefsClear(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true) &&
            !exchange.requestMethod.equals("DELETE", ignoreCase = true)
        ) { respondText(exchange, 405, "Method not allowed"); return }
        val mangaId = exchange.query()["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        facade.clearMangaPrefs(mangaId)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    // MARK: - downloads

    private fun handleDownloads(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val dm = downloads ?: return respondError(exchange, 503, "Downloads not enabled")
        val rows = dm.list().map(::toDto)
        respondJson(exchange, 200, json.encodeToJsonElement(DownloadsResponse(rows)))
    }

    private fun handleDownloadStart(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val dm = downloads ?: return respondError(exchange, 503, "Downloads not enabled")
        val p = exchange.query()
        val sourceId = p["sourceId"] ?: return respondError(exchange, 400, "Missing 'sourceId'")
        val mangaUrl = p["mangaUrl"].orEmpty()
        val chapterUrl = p["chapterUrl"] ?: return respondError(exchange, 400, "Missing 'chapterUrl'")
        val mangaTitle = p["mangaTitle"] ?: "manga"
        val chapterTitle = p["chapterTitle"] ?: "chapter"
        val entry = dm.start(sourceId, mangaUrl, chapterUrl, mangaTitle, chapterTitle)
        respondJson(exchange, 200, json.encodeToJsonElement(DownloadResponse(toDto(entry))))
    }

    /**
     * Batch-enqueue many chapters at once (range / multi-select from the UI).
     *   POST /downloads/enqueue?sourceId=&mangaUrl=&mangaTitle=
     *   body: [{"url":"...","title":"..."}, ...]
     * The DownloadManager throttles concurrent runs via its semaphore, so a large
     * batch queues safely.
     */
    private fun handleDownloadEnqueue(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val dm = downloads ?: return respondError(exchange, 503, "Downloads not enabled")
        val p = exchange.query()
        val sourceId = p["sourceId"] ?: return respondError(exchange, 400, "Missing 'sourceId'")
        val mangaUrl = p["mangaUrl"].orEmpty()
        val mangaTitle = p["mangaTitle"] ?: "manga"
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val arr = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return respondError(exchange, 400, "Body must be a JSON array of {url,title}")
        val entries = arr.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "chapter"
            dm.start(sourceId, mangaUrl, url, mangaTitle, title)
        }
        respondJson(exchange, 200, json.encodeToJsonElement(DownloadsResponse(entries.map { toDto(it) })))
    }

    private fun handleDownloadCancel(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val dm = downloads ?: return respondError(exchange, 503, "Downloads not enabled")
        val id = exchange.query()["id"] ?: return respondError(exchange, 400, "Missing 'id'")
        dm.cancel(id)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleDownloadSettings(exchange: HttpExchange) {
        val dm = downloads ?: return respondError(exchange, 503, "Downloads not enabled")
        when {
            exchange.requestMethod.equals("GET", ignoreCase = true) -> {
                respondJson(exchange, 200, json.encodeToJsonElement(DownloadSettingsResponse(dm.currentSettings())))
            }
            exchange.requestMethod.equals("POST", ignoreCase = true) -> {
                val p = exchange.query()
                val maxConcurrent = p["maxConcurrent"]?.toIntOrNull() ?: dm.currentSettings().maxConcurrentDownloads
                val format = p["format"]?.uppercase()?.let {
                    runCatching { DownloadFormat.valueOf(it) }.getOrNull()
                } ?: dm.currentSettings().format
                dm.configure(DownloadSettings(maxConcurrentDownloads = maxConcurrent, format = format))
                respondJson(exchange, 200, json.encodeToJsonElement(DownloadSettingsResponse(dm.currentSettings())))
            }
            else -> respondText(exchange, 405, "Method not allowed")
        }
    }

    private fun handleNetworkSettings(exchange: HttpExchange) {
        when {
            exchange.requestMethod.equals("GET", ignoreCase = true) -> {
                respondJson(exchange, 200, json.encodeToJsonElement(NetworkSettingsResponse(networkConfig.snapshot())))
            }
            exchange.requestMethod.equals("POST", ignoreCase = true) -> {
                val p = exchange.query()
                val updated = networkConfig.update(
                    HelperNetworkSettings(
                        proxyType = p["proxyType"] ?: networkConfig.snapshot().proxyType,
                        proxyAddress = p["proxyAddress"] ?: networkConfig.snapshot().proxyAddress,
                        proxyPort = p["proxyPort"]?.toIntOrNull() ?: networkConfig.snapshot().proxyPort,
                        dnsOverHttps = p["dnsOverHttps"] ?: networkConfig.snapshot().dnsOverHttps,
                        githubMirror = p["githubMirror"] ?: networkConfig.snapshot().githubMirror,
                        imagesProxy = p["imagesProxy"] ?: networkConfig.snapshot().imagesProxy,
                        sslBypass = p["sslBypass"]?.toBooleanStrictOrNull() ?: networkConfig.snapshot().sslBypass,
                        disableConnectivityCheck = p["disableConnectivityCheck"]?.toBooleanStrictOrNull()
                            ?: networkConfig.snapshot().disableConnectivityCheck,
                    )
                )
                respondJson(exchange, 200, json.encodeToJsonElement(NetworkSettingsResponse(updated)))
            }
            else -> respondText(exchange, 405, "Method not allowed")
        }
    }

    private fun toDto(e: com.nyora.hasan72341.shared.download.DownloadEntry): DownloadDto = DownloadDto(
        id = e.id,
        sourceId = e.sourceId,
        mangaTitle = e.mangaTitle,
        chapterTitle = e.chapterTitle,
        chapterUrl = e.chapterUrl,
        totalPages = e.totalPages,
        completedPages = e.completedPages,
        failedPages = e.failedPages,
        status = e.status.name,
        filePath = e.filePath,
        error = e.error,
        startedAt = e.startedAt,
        finishedAt = e.finishedAt,
    )

    // MARK: - favourite categories

    private fun handleCategories(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val categoryId = exchange.query()["categoryId"]?.toLongOrNull()
        if (categoryId != null) {
            respondJson(exchange, 200, json.encodeToJsonElement(
                FavouritesResponse(entries = facade.favouritesIn(categoryId)),
            ))
            return
        }
        val rows = facade.favouriteCategories().map { CategoryDto(it.id, it.title, it.mangaCount) }
        respondJson(exchange, 200, json.encodeToJsonElement(CategoriesResponse(rows)))
    }

    private fun handleCategoryCreate(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val title = exchange.query()["title"].orEmpty().trim()
        if (title.isEmpty()) return respondError(exchange, 400, "Missing 'title'")
        val id = facade.createCategory(title)
        respondJson(exchange, 200, buildJsonObject { put("id", id); put("title", title) })
    }

    private fun handleCategoryRename(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val id = exchange.query()["id"]?.toLongOrNull() ?: return respondError(exchange, 400, "Missing 'id'")
        val title = exchange.query()["title"].orEmpty().trim()
        if (title.isEmpty()) return respondError(exchange, 400, "Missing 'title'")
        facade.renameCategory(id, title)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleCategoryDelete(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true) &&
            !exchange.requestMethod.equals("DELETE", ignoreCase = true)
        ) { respondText(exchange, 405, "Method not allowed"); return }
        val id = exchange.query()["id"]?.toLongOrNull() ?: return respondError(exchange, 400, "Missing 'id'")
        facade.deleteCategory(id)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleCategoryAdd(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val mangaId = exchange.query()["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val categoryId = exchange.query()["categoryId"]?.toLongOrNull()
            ?: return respondError(exchange, 400, "Missing 'categoryId'")
        facade.addToCategory(mangaId, categoryId)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleCategoryRemove(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true) &&
            !exchange.requestMethod.equals("DELETE", ignoreCase = true)
        ) { respondText(exchange, 405, "Method not allowed"); return }
        val mangaId = exchange.query()["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val categoryId = exchange.query()["categoryId"]?.toLongOrNull()
            ?: return respondError(exchange, 400, "Missing 'categoryId'")
        facade.removeFromCategory(mangaId, categoryId)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleCategoriesForManga(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val mangaId = exchange.query()["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val rows = facade.categoriesForManga(mangaId).map { CategoryDto(it.id, it.title, 0) }
        respondJson(exchange, 200, json.encodeToJsonElement(CategoriesResponse(rows)))
    }

    // MARK: - global search

    private fun handleGlobalSearch(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val query = params["q"]?.trim().orEmpty()
        if (query.isBlank()) {
            return respondError(exchange, 400, "Missing 'q'")
        }
        val perSourceLimit = params["limit"]?.toIntOrNull() ?: 6
        val installed = facade.listSources().filter { it.isInstalled }

        // Fan out across ALL installed sources, fully async. With the curated
        // catalogue this is a few hundred sources, so we bound concurrency with
        // a semaphore (opening hundreds of sockets at once just thrashes) while
        // still running many in parallel. There is NO overall request timeout —
        // the handler returns once every source has finished (or hit its own
        // per-source cap), and the Swift client waits as long as it takes.
        // The per-source cap stays: without it a single hung source would block
        // `awaitAll` forever and the whole search would never return.
        // Bounded fan-out — keeps the worst-case memory spike (many in-flight Jsoup
        // documents at once) small so the hosted helper stays under its RAM budget.
        val gate = kotlinx.coroutines.sync.Semaphore(12)
        val results = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.coroutineScope {
                installed.map { src ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                kotlinx.coroutines.withTimeout(15_000L) {
                                    val service = openInstalled(src)
                                    val page = service.search(query, page = 1)
                                    GlobalSearchGroup(
                                        sourceId = src.id,
                                        sourceName = src.name,
                                        entries = page.entries.take(perSourceLimit),
                                        error = null,
                                    )
                                }
                            }.getOrElse { error ->
                                GlobalSearchGroup(
                                    sourceId = src.id,
                                    sourceName = src.name,
                                    entries = emptyList(),
                                    error = error.message?.take(120) ?: error::class.simpleName,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        // Only surface sources that actually returned hits, ordered by hit count.
        val nonEmpty = results.filter { it.entries.isNotEmpty() }.sortedByDescending { it.entries.size }
        respondJson(exchange, 200, json.encodeToJsonElement(GlobalSearchResponse(query, nonEmpty)))
    }

    // MARK: - local: cbz / folder reader

    private fun handleLocalScan(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val folder = exchange.query()["folder"] ?: return respondError(exchange, 400, "Missing 'folder'")
        val dir = java.nio.file.Path.of(folder)
        if (!java.nio.file.Files.isDirectory(dir)) {
            return respondError(exchange, 404, "Not a directory: $folder")
        }
        val entries = try {
            java.nio.file.Files.list(dir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".cbz", ignoreCase = true) ||
                             it.fileName.toString().endsWith(".cbr", ignoreCase = true) ||
                             it.fileName.toString().endsWith(".zip", ignoreCase = true) }
                    .sorted()
                    .map { p ->
                        LocalCbzEntry(
                            path = p.toAbsolutePath().toString(),
                            name = p.fileName.toString().removeSuffix(".cbz").removeSuffix(".CBZ"),
                            sizeBytes = runCatching { java.nio.file.Files.size(p) }.getOrDefault(0L),
                        )
                    }
                    .toList()
            }
        } catch (e: Exception) {
            return respondError(exchange, 500, e.message ?: "Scan failed")
        }
        respondJson(exchange, 200, json.encodeToJsonElement(LocalScanResponse(entries)))
    }

    private fun handleLocalChapter(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val cbz = exchange.query()["cbz"] ?: return respondError(exchange, 400, "Missing 'cbz'")
        val path = java.nio.file.Path.of(cbz)
        if (!java.nio.file.Files.isRegularFile(path)) {
            return respondError(exchange, 404, "Not a file: $cbz")
        }
        val entries: List<String> = try {
            java.util.zip.ZipFile(path.toFile()).use { zf ->
                val all = mutableListOf<String>()
                val enum = zf.entries()
                while (enum.hasMoreElements()) {
                    val e = enum.nextElement()
                    if (!e.isDirectory && isImageEntry(e.name)) all.add(e.name)
                }
                all.sorted()
            }
        } catch (e: Exception) {
            return respondError(exchange, 500, e.message ?: "Open failed")
        }
        // Use the helper's loopback host:port as the base for image URLs.
        val base = "http://127.0.0.1:${server!!.address.port}"
        val pageUrls = entries.map { entry ->
            val encCbz = java.net.URLEncoder.encode(cbz, java.nio.charset.StandardCharsets.UTF_8)
            val encEntry = java.net.URLEncoder.encode(entry, java.nio.charset.StandardCharsets.UTF_8)
            "$base/local/image?cbz=$encCbz&entry=$encEntry"
        }
        respondJson(exchange, 200, json.encodeToJsonElement(LocalChapterResponse(
            name = path.fileName.toString().removeSuffix(".cbz").removeSuffix(".CBZ"),
            pageCount = entries.size,
            pageUrls = pageUrls,
        )))
    }

    private fun handleLocalImage(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val cbz = params["cbz"] ?: return respondError(exchange, 400, "Missing 'cbz'")
        val entry = params["entry"] ?: return respondError(exchange, 400, "Missing 'entry'")
        val path = java.nio.file.Path.of(cbz)
        if (!java.nio.file.Files.isRegularFile(path)) {
            return respondError(exchange, 404, "Not a file: $cbz")
        }
        try {
            java.util.zip.ZipFile(path.toFile()).use { zf ->
                val zipEntry = zf.getEntry(entry)
                    ?: return respondError(exchange, 404, "Entry not found: $entry")
                val bytes = zf.getInputStream(zipEntry).readBytes()
                val contentType = guessContentType(entry, bytes)
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.responseHeaders.add("Cache-Control", "private, max-age=86400")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (e: Exception) {
            respondError(exchange, 500, e.message ?: "Read failed")
        }
    }

    private fun isImageEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".avif")
    }

    // MARK: - library: updates

    private fun handleUpdates(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val rows = facade.updates().map { UpdateDto(
            mangaId = it.mangaId,
            mangaTitle = it.mangaTitle,
            mangaCoverUrl = it.mangaCoverUrl,
            sourceId = it.sourceId,
            newChapters = it.newChapters,
            totalChapters = it.totalChapters,
            latestChapterTitle = it.latestChapterTitle,
            lastSyncedAt = it.lastSyncedAt,
        ) }
        respondJson(exchange, 200, json.encodeToJsonElement(UpdatesResponse(rows)))
    }

    private fun handleUpdatesRefresh(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        var checked = 0
        var withNew = 0
        try {
            // Scan both favourites AND reading-history manga, de-duplicated by manga id,
            // so updates are detected for any manga in history too.
            val combined = LinkedHashMap<String, Manga>()
            for (manga in facade.favourites()) {
                combined.putIfAbsent(manga.id, manga)
            }
            for (row in facade.history(limit = 500)) {
                combined.putIfAbsent(row.manga.id, row.manga)
            }
            for (manga in combined.values) {
                // Need a source to fetch from. Pick the source matching the manga.source ref name, or skip.
                val sourceName = manga.source.name
                val source = facade.listSources().firstOrNull { src ->
                    src.isInstalled && (src.name == sourceName || src.id.endsWith(":$sourceName"))
                } ?: continue
                runCatching {
                    val service = openInstalled(source)
                    val details = runBlocking { service.getDetails(manga.url.ifBlank { manga.id }) }
                    val count = details.chapters.size
                    val latestTitle = details.chapters.firstOrNull()?.title.orEmpty()
                    val before = (facade.updates().firstOrNull { it.mangaId == manga.id }?.totalChapters ?: -1)
                    facade.recordUpdateSync(
                        mangaId = manga.id,
                        sourceId = source.id,
                        currentChapterCount = count,
                        latestChapterTitle = latestTitle,
                    )
                    checked++
                    if (before in 0 until count) withNew++
                }
            }
        } catch (error: Exception) {
            respondError(exchange, 500, error.message ?: "Updates refresh failed"); return
        }
        respondJson(exchange, 200, buildJsonObject {
            put("checked", checked)
            put("withNew", withNew)
        })
    }

    private fun handleUpdatesSeen(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val mangaId = exchange.query()["mangaId"]
        if (mangaId.isNullOrBlank()) {
            facade.markAllUpdatesSeen()
        } else {
            facade.markUpdatesSeen(mangaId)
        }
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    // MARK: - library: bookmarks

    private fun handleBookmarks(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val rows = facade.bookmarks().map { BookmarkDto(
            id = it.id,
            mangaId = it.mangaId,
            mangaTitle = it.mangaTitle,
            mangaCoverUrl = it.mangaCoverUrl,
            chapterId = it.chapterId,
            chapterTitle = it.chapterTitle,
            page = it.page,
            note = it.note,
            createdAt = it.createdAt,
        ) }
        respondJson(exchange, 200, json.encodeToJsonElement(BookmarksResponse(rows)))
    }

    private fun handleBookmarkAdd(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val mangaId = params["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val chapterId = params["chapterId"].orEmpty()
        val chapterTitle = params["chapterTitle"].orEmpty()
        val page = params["page"]?.toIntOrNull() ?: 0
        val note = params["note"].orEmpty()
        facade.addBookmark(mangaId, chapterId, chapterTitle, page, note)
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleBookmarkRemove(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true) &&
            !exchange.requestMethod.equals("DELETE", ignoreCase = true)
        ) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val id = params["id"]?.toLongOrNull()
        if (id != null) {
            facade.removeBookmark(id)
        } else {
            val mangaId = params["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId' or 'id'")
            val chapterId = params["chapterId"].orEmpty()
            val page = params["page"]?.toIntOrNull() ?: 0
            facade.removeBookmarkForPage(mangaId, chapterId, page)
        }
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleBookmarkCheck(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val mangaId = params["mangaId"] ?: return respondError(exchange, 400, "Missing 'mangaId'")
        val chapterId = params["chapterId"].orEmpty()
        val page = params["page"]?.toIntOrNull() ?: 0
        respondJson(exchange, 200, buildJsonObject {
            put("bookmarked", facade.isPageBookmarked(mangaId, chapterId, page))
        })
    }

    private fun handleImage(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        // Accept ?u=<url>&h=Name:Value (repeated). Multi-value query params live
        // in the raw query string, so parse manually.
        val rawQuery = exchange.requestURI.rawQuery.orEmpty()
        val pairs = rawQuery.split('&').mapNotNull { p ->
            val idx = p.indexOf('=')
            if (idx <= 0) null else URLDecoder.decode(p.substring(0, idx), StandardCharsets.UTF_8) to
                URLDecoder.decode(p.substring(idx + 1), StandardCharsets.UTF_8)
        }
        val url = pairs.firstOrNull { it.first == "u" }?.second
            ?: return respondError(exchange, 400, "Missing 'u'")
        val headers = pairs.filter { it.first == "h" }.mapNotNull { (_, v) ->
            val colon = v.indexOf(':'); if (colon <= 0) null else v.substring(0, colon) to v.substring(colon + 1)
        }.toMap()
        // This proxy is publicly reachable (Caddy → loopback), so the target is untrusted:
        // reject non-http URLs (no local-file reads) and private/internal hosts (no SSRF).
        try {
            SsrfGuard.assertPublicHttpUrl(url)
        } catch (_: SecurityException) {
            return respondError(exchange, 400, "Invalid image URL")
        }
        try {
            val bytes = pageLoader.loadBytes(url, headers, allowLocalFiles = false, blockPrivateHosts = true)
            val contentType = guessContentType(url, bytes)
            applyCors(exchange)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.responseHeaders.add("Cache-Control", "private, max-age=86400")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (error: Exception) {
            respondError(exchange, 502, error.message ?: "Image proxy failed")
        }
    }

    private fun guessContentType(url: String, bytes: ByteArray): String {
        when {
            url.endsWith(".png", ignoreCase = true) -> return "image/png"
            url.endsWith(".webp", ignoreCase = true) -> return "image/webp"
            url.endsWith(".gif", ignoreCase = true) -> return "image/gif"
            url.endsWith(".jpg", ignoreCase = true) ||
                url.endsWith(".jpeg", ignoreCase = true) -> return "image/jpeg"
            url.endsWith(".avif", ignoreCase = true) -> return "image/avif"
        }
        // URL lacks a known extension — sniff magic bytes.
        return sniffImageType(bytes) ?: "application/octet-stream"
    }

    private fun sniffImageType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        // GIF: "GIF8"
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
        ) return "image/gif"
        // WEBP: RIFF....WEBP
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return "image/webp"
        // AVIF / HEIC: bytes 4..11 spell "ftypavif" / "ftypheic" / "ftypheix" etc.
        if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
        ) {
            val brand = String(bytes, 8, 4)
            return when (brand) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "mif1", "msf1" -> "image/heic"
                else -> null
            }
        }
        return null
    }

    private fun HttpExchange.query(): Map<String, String> {
        val raw = requestURI.rawQuery ?: return emptyMap()
        return raw.split('&').mapNotNull { p ->
            val idx = p.indexOf('=')
            if (idx <= 0) null else URLDecoder.decode(p.substring(0, idx), StandardCharsets.UTF_8) to
                URLDecoder.decode(p.substring(idx + 1), StandardCharsets.UTF_8)
        }.toMap()
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: kotlinx.serialization.json.JsonElement) {
        val bytes = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body)
            .toByteArray(StandardCharsets.UTF_8)
        applyCors(exchange)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Respond with an already-serialized JSON string (used for cached responses). */
    private fun respondJsonRaw(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        applyCors(exchange)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondText(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        applyCors(exchange)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondError(exchange: HttpExchange, status: Int, message: String) {
        respondJson(exchange, status, buildJsonObject { put("error", message) })
    }

    // ----- Supabase Sync -----

    private fun handleSupabaseStatus(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val config = com.nyora.hasan72341.shared.sync.SupabaseConfig
        respondJson(exchange, 200, buildJsonObject {
            put("isConfigured", config.isConfigured)
            put("isAuthenticated", config.isAuthenticated)
            put("userId", config.userId)
            put("email", config.email)
            put("lastSyncTimestamp", config.lastSyncTimestamp)
            put("googleDesktopClientId", config.googleDesktopClientId)
            put("googleServerClientId", config.googleServerClientId)
        })
    }

    private fun handleSupabaseSignIn(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val email = params["email"]
        val password = params["password"]
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            return respondError(exchange, 400, "Missing 'email' or 'password'")
        }
        val error = facade.supabaseSignIn(email, password)
        if (error == null) {
            respondJson(exchange, 200, buildJsonObject { put("ok", true) })
        } else {
            respondError(exchange, 401, error)
        }
    }

    private fun handleSupabaseRegister(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val params = exchange.query()
        val email = params["email"]
        val password = params["password"]
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            return respondError(exchange, 400, "Missing 'email' or 'password'")
        }
        val error = facade.supabaseRegister(email, password)
        if (error == null) {
            respondJson(exchange, 200, buildJsonObject { put("ok", true) })
        } else {
            respondError(exchange, 401, error)
        }
    }

    private fun handleSupabaseSignOut(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        facade.supabaseSignOut()
        respondJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleSupabaseSync(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        runCatching {
            facade.supabaseSyncNow()
        }.fold(
            onSuccess = { respondJson(exchange, 200, buildJsonObject { put("ok", true) }) },
            onFailure = { respondError(exchange, 500, it.message ?: "Supabase sync failed") },
        )
    }

    private fun handleSupabaseRestoreFromCloud(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        runCatching {
            facade.supabaseRestoreFromCloud()
        }.fold(
            onSuccess = { respondJson(exchange, 200, buildJsonObject { put("ok", true) }) },
            onFailure = { respondError(exchange, 500, it.message ?: "Supabase restore failed") },
        )
    }

    private fun handleSupabaseHasLocalData(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val hasData = facade.hasLocalSyncableData()
        respondJson(exchange, 200, buildJsonObject { put("hasLocalData", hasData) })
    }

    // ----- OTA parser updates -----

    // Parsers are compiled in natively (kotatsu-parsers-redo); there is no OTA bundle.
    // These endpoints are kept for client compatibility and report the static state.
    private fun handleOtaStatus(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        respondJson(exchange, 200, buildJsonObject {
            put("bundledVersion", 0)
            put("otaVersion", 0)
            put("isActive", false)
        })
    }

    private fun handleOtaCheck(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        respondJson(exchange, 200, buildJsonObject {
            put("bundledVersion", 0)
            put("otaVersion", 0)
            put("isActive", false)
        })
    }

    // ----- Tracker: AniList -----
    //
    // The Mac app sends the user's AniList personal access token in the
    // Authorization header on each call so we never persist credentials on
    // the helper side. Token storage is Keychain-backed up in Swift.

    private fun handleAniListSearch(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = exchange.requestHeaders.getFirst("Authorization")
            ?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank()) {
            return respondError(exchange, 401, "Missing AniList token")
        }
        val title = exchange.query()["title"]?.takeIf { it.isNotBlank() }
            ?: return respondError(exchange, 400, "Missing 'title'")
        val query = """
            query (${'$'}search: String) {
              Page(perPage: 5) {
                media(search: ${'$'}search, type: MANGA) {
                  id
                  title { romaji english native }
                  coverImage { large }
                  averageScore
                  chapters
                }
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject { put("search", title) })
        }
        val raw = postJson("https://graphql.anilist.co", body, bearer = token)
            ?: return respondError(exchange, 502, "AniList request failed")
        // Pass the AniList response straight through; UI parses on the Swift side.
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    private fun handleAniListScrobble(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = exchange.requestHeaders.getFirst("Authorization")
            ?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank()) {
            return respondError(exchange, 401, "Missing AniList token")
        }
        val params = exchange.query()
        val mediaId = params["mediaId"]?.toIntOrNull()
            ?: return respondError(exchange, 400, "Missing 'mediaId'")
        val progress = params["progress"]?.toIntOrNull() ?: 0
        val status = params["status"] ?: "CURRENT"
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: ${'$'}status) {
                id progress status
              }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", mutation)
            put("variables", buildJsonObject {
                put("mediaId", mediaId)
                put("progress", progress)
                put("status", status)
            })
        }
        val raw = postJson("https://graphql.anilist.co", body, bearer = token)
            ?: return respondError(exchange, 502, "AniList request failed")
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    // ----- Tracker: MyAnimeList / Kitsu / Shikimori -----
    //
    // Like AniList above, these are thin bearer-token passthroughs: the desktop
    // app holds the OAuth access token (Keychain-backed in Swift) and sends it
    // in the Authorization header on every call, so no credentials are persisted
    // helper-side. Each handler forwards a single request to the upstream service
    // API and streams the raw JSON straight back; the UI parses on the client.

    private fun bearerOrNull(exchange: HttpExchange): String? =
        exchange.requestHeaders.getFirst("Authorization")
            ?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }

    /// Perform an outbound request to a tracker API and pass the raw body back.
    /// Handles GET/POST/PUT/PATCH; PATCH (unsupported by HttpURLConnection) is
    /// tunnelled through POST + X-HTTP-Method-Override, which both Kitsu and
    /// Shikimori honour. Returns null on transport failure.
    private fun serviceRequest(
        url: String,
        method: String,
        bearer: String?,
        body: ByteArray? = null,
        contentType: String? = null,
        accept: String? = "application/json",
        userAgent: String? = null,
    ): String? {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                try {
                    requestMethod = method
                } catch (_: java.net.ProtocolException) {
                    // HttpURLConnection rejects PATCH; tunnel it via POST override.
                    requestMethod = "POST"
                    setRequestProperty("X-HTTP-Method-Override", method)
                }
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (accept != null) setRequestProperty("Accept", accept)
                if (userAgent != null) setRequestProperty("User-Agent", userAgent)
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    doOutput = true
                    if (contentType != null) setRequestProperty("Content-Type", contentType)
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

    // --- MyAnimeList (REST v2) ---

    private fun handleMalSearch(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = bearerOrNull(exchange) ?: return respondError(exchange, 401, "Missing MyAnimeList token")
        val title = exchange.query()["title"]?.takeIf { it.isNotBlank() }
            ?: return respondError(exchange, 400, "Missing 'title'")
        // MAL 400s on q longer than 64 chars.
        val url = "https://api.myanimelist.net/v2/manga?q=${urlEncode(title.take(64))}" +
            "&limit=5&nsfw=true&fields=id,title,main_picture,synopsis"
        val raw = serviceRequest(url, "GET", token)
            ?: return respondError(exchange, 502, "MyAnimeList request failed")
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    private fun handleMalScrobble(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = bearerOrNull(exchange) ?: return respondError(exchange, 401, "Missing MyAnimeList token")
        val params = exchange.query()
        val mediaId = params["mediaId"]?.toLongOrNull()
            ?: return respondError(exchange, 400, "Missing 'mediaId'")
        // Build the my_list_status form body from whatever the client supplies.
        val form = buildList {
            params["progress"]?.toIntOrNull()?.let { add("num_chapters_read=$it") }
            params["status"]?.takeIf { it.isNotBlank() }?.let { add("status=${urlEncode(it)}") }
            params["score"]?.toIntOrNull()?.let { add("score=${it.coerceIn(0, 10)}") }
            params["comment"]?.let { add("comments=${urlEncode(it)}") }
        }.joinToString("&")
        val raw = serviceRequest(
            url = "https://api.myanimelist.net/v2/manga/$mediaId/my_list_status",
            method = "PUT",
            bearer = token,
            body = form.toByteArray(StandardCharsets.UTF_8),
            contentType = "application/x-www-form-urlencoded",
        ) ?: return respondError(exchange, 502, "MyAnimeList request failed")
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    // --- Kitsu (JSON:API) ---

    private fun handleKitsuSearch(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = bearerOrNull(exchange) ?: return respondError(exchange, 401, "Missing Kitsu token")
        val title = exchange.query()["title"]?.takeIf { it.isNotBlank() }
            ?: return respondError(exchange, 400, "Missing 'title'")
        val url = "https://kitsu.app/api/edge/manga?page[limit]=5&filter[text]=${urlEncode(title)}"
        val raw = serviceRequest(url, "GET", token, accept = "application/vnd.api+json")
            ?: return respondError(exchange, 502, "Kitsu request failed")
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    private fun handleKitsuScrobble(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = bearerOrNull(exchange) ?: return respondError(exchange, 401, "Missing Kitsu token")
        val params = exchange.query()
        // rateId present -> PATCH an existing library-entry; else POST create
        // (needs mediaId + userId, which the client resolves via /users?self).
        val rateId = params["rateId"]?.takeIf { it.isNotBlank() }
        val attrs = buildJsonObject {
            params["progress"]?.toIntOrNull()?.let { put("progress", it) }
            params["status"]?.takeIf { it.isNotBlank() }?.let { put("status", it) }
            params["ratingTwenty"]?.toIntOrNull()?.let { put("ratingTwenty", it.coerceIn(2, 20)) }
            params["comment"]?.let { put("notes", it) }
        }
        val (url, method, payload) = if (rateId != null) {
            val body = buildJsonObject {
                putJsonObject("data") {
                    put("type", "libraryEntries")
                    put("id", rateId)
                    put("attributes", attrs)
                }
            }
            Triple("https://kitsu.app/api/edge/library-entries/$rateId", "PATCH", body)
        } else {
            val mediaId = params["mediaId"]?.takeIf { it.isNotBlank() }
                ?: return respondError(exchange, 400, "Missing 'mediaId' or 'rateId'")
            val userId = params["userId"]?.takeIf { it.isNotBlank() }
                ?: return respondError(exchange, 400, "Missing 'userId' for create")
            val body = buildJsonObject {
                putJsonObject("data") {
                    put("type", "libraryEntries")
                    put("attributes", attrs)
                    putJsonObject("relationships") {
                        putJsonObject("manga") {
                            putJsonObject("data") { put("type", "manga"); put("id", mediaId) }
                        }
                        putJsonObject("user") {
                            putJsonObject("data") { put("type", "users"); put("id", userId) }
                        }
                    }
                }
            }
            Triple("https://kitsu.app/api/edge/library-entries", "POST", body)
        }
        val raw = serviceRequest(
            url = url,
            method = method,
            bearer = token,
            body = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload)
                .toByteArray(StandardCharsets.UTF_8),
            contentType = "application/vnd.api+json",
            accept = "application/vnd.api+json",
        ) ?: return respondError(exchange, 502, "Kitsu request failed")
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    // --- Shikimori (REST v2) ---

    private fun handleShikimoriSearch(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = bearerOrNull(exchange) ?: return respondError(exchange, 401, "Missing Shikimori token")
        val title = exchange.query()["title"]?.takeIf { it.isNotBlank() }
            ?: return respondError(exchange, 400, "Missing 'title'")
        val url = "https://shikimori.one/api/mangas?limit=10&censored=false&search=${urlEncode(title)}"
        val raw = serviceRequest(url, "GET", token, userAgent = "Nyora")
            ?: return respondError(exchange, 502, "Shikimori request failed")
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    private fun handleShikimoriScrobble(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val token = bearerOrNull(exchange) ?: return respondError(exchange, 401, "Missing Shikimori token")
        val params = exchange.query()
        // rateId present -> PATCH the user_rate; else POST create (needs mediaId + userId).
        val rateId = params["rateId"]?.takeIf { it.isNotBlank() }
        val payload = buildJsonObject {
            putJsonObject("user_rate") {
                if (rateId == null) {
                    params["mediaId"]?.toLongOrNull()?.let { put("target_id", it) }
                    put("target_type", "Manga")
                    params["userId"]?.toLongOrNull()?.let { put("user_id", it) }
                }
                params["progress"]?.toIntOrNull()?.let { put("chapters", it) }
                params["status"]?.takeIf { it.isNotBlank() }?.let { put("status", it) }
                params["score"]?.toIntOrNull()?.let { put("score", it.coerceIn(0, 10)) }
                params["comment"]?.let { put("text", it) }
            }
        }
        if (rateId == null &&
            (params["mediaId"]?.toLongOrNull() == null || params["userId"]?.toLongOrNull() == null)
        ) {
            return respondError(exchange, 400, "Missing 'rateId' or ('mediaId' and 'userId')")
        }
        val url = if (rateId != null) {
            "https://shikimori.one/api/v2/user_rates/$rateId"
        } else {
            "https://shikimori.one/api/v2/user_rates"
        }
        val raw = serviceRequest(
            url = url,
            method = if (rateId != null) "PATCH" else "POST",
            bearer = token,
            body = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload)
                .toByteArray(StandardCharsets.UTF_8),
            contentType = "application/json",
            userAgent = "Nyora",
        ) ?: return respondError(exchange, 502, "Shikimori request failed")
        respondText(exchange, 200, raw)
        exchange.responseHeaders.set("Content-Type", "application/json")
    }

    /// POST a JSON body to `url`. Returns the response body string on 2xx,
    /// null otherwise. Used for AniList GraphQL.
    private fun postJson(url: String, body: kotlinx.serialization.json.JsonElement, bearer: String? = null): String? {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            }
            val payload = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body)
                .toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    // ----- Filters -----

    /// Returns the per-source filter descriptors so the UI can render
    /// genre / sort / status pickers driven by what the parser actually supports.
    private fun handleFilters(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val id = exchange.query()["id"] ?: return respondError(exchange, 400, "Missing 'id'")
        val source = facade.listSources().firstOrNull { it.id == id }
            ?: return respondError(exchange, 404, "Unknown source: $id")
        val descriptors = try {
            openInstalled(source).getFilterList()
        } catch (_: Exception) {
            emptyList()
        }
        val arr = descriptors.map { d ->
            buildJsonObject {
                put("name", d.name)
                put("typeName", d.typeName)
                put("values", kotlinx.serialization.json.JsonArray(
                    d.values.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ))
            }
        }
        respondJson(exchange, 200, buildJsonObject {
            put("filters", kotlinx.serialization.json.JsonArray(arr))
        })
    }

    /// Turn the wire-format filter array into the typed SourceFilter list.
    /// Unknown types are silently skipped.
    private fun parseFilters(raw: String?): List<com.nyora.hasan72341.shared.extension.SourceFilter> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try {
            json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
        } catch (_: Exception) { null } ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> com.nyora.hasan72341.shared.extension.TextSourceFilter(
                    name = name,
                    value = obj["value"]?.jsonPrimitive?.contentOrNull ?: ""
                )
                "select" -> com.nyora.hasan72341.shared.extension.SelectSourceFilter(
                    name = name,
                    selectedIndex = obj["selectedIndex"]?.jsonPrimitive?.intOrNull ?: 0
                )
                "check" -> com.nyora.hasan72341.shared.extension.CheckSourceFilter(
                    name = name,
                    checked = obj["checked"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                )
                else -> null
            }
        }
    }

    // ----- Stats -----

    private fun handleStats(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val history = facade.history(limit = 5000)
        val totalChapters = history.size
        val distinctManga = history.map { row -> row.manga.id }.distinct().size
        val sources = facade.listSources().associateBy { it.id }
        val perSource: List<kotlinx.serialization.json.JsonElement> = history
            .groupingBy { row -> row.sourceId }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)
            .map { entry ->
                val sid = entry.key
                val count = entry.value
                buildJsonObject {
                    put("sourceId", sid)
                    put("sourceName", sources[sid]?.name ?: sid)
                    put("count", count)
                }
            }
        // Reading streak: consecutive days that have at least one history entry,
        // counting back from the most recent day in the history.
        val days = history.map { it.updatedAt / 86_400_000L }.toSet().sortedDescending()
        var streak = 0
        if (days.isNotEmpty()) {
            var cursor = days.first()
            for (d in days) {
                if (d == cursor || d == cursor - 1) {
                    if (d != cursor) streak++
                    cursor = d
                }
            }
            streak += 1 // include the first day
        }
        val payload = buildJsonObject {
            put("totalChapters", totalChapters)
            put("distinctManga", distinctManga)
            put("favouritesCount", facade.favourites().size)
            put("longestStreakDays", streak)
            put("topSources", kotlinx.serialization.json.JsonArray(perSource))
        }
        respondJson(exchange, 200, payload)
    }

    // ----- Suggestions -----

    private fun handleSuggestions(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        // Pick a recent favourite as the seed and pull its source's popular
        // list, filtering out anything the user already favourited.
        val favs = facade.favourites()
        if (favs.isEmpty()) {
            respondJson(exchange, 200, buildJsonObject { put("entries", kotlinx.serialization.json.JsonArray(emptyList())) })
            return
        }
        val favIds = favs.map { it.id }.toSet()
        val seed = favs.first()
        val seedSrcId = when (val ref = seed.source) {
            is com.nyora.hasan72341.shared.model.MangaSourceRef.Parser -> "parser:${ref.name}"
            is com.nyora.hasan72341.shared.model.MangaSourceRef.Script -> ref.toOpenableSourceId()
            else -> ""
        }
        val seedSource = facade.listSources().firstOrNull { it.id == seedSrcId }
        val parserSource = seedSource ?: facade.listSources().firstOrNull {
            it.engine == com.nyora.hasan72341.shared.model.SourceEngine.Parser && it.isInstalled
        }
        if (parserSource == null) {
            respondJson(exchange, 200, buildJsonObject { put("entries", kotlinx.serialization.json.JsonArray(emptyList())) })
            return
        }
        val service = openInstalled(parserSource)
        val list: List<com.nyora.hasan72341.shared.model.Manga> = try {
            kotlinx.coroutines.runBlocking { service.getPopular(page = 1).entries }
        } catch (_: Exception) {
            emptyList()
        }
        val filtered: List<kotlinx.serialization.json.JsonElement> = list
            .filter { it.id !in favIds }
            .take(30)
            .map { mangaToJson(it) }
        respondJson(exchange, 200, buildJsonObject {
            put("entries", kotlinx.serialization.json.JsonArray(filtered))
        })
    }

    // ----- Alternative sources -----

    private fun handleAlternatives(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val query = exchange.query()["title"]?.takeIf { it.isNotBlank() }
            ?: return respondError(exchange, 400, "Missing 'title'")
        val sources = facade.listSources()
            .filter { it.isInstalled && it.engine == com.nyora.hasan72341.shared.model.SourceEngine.Parser }
        val results = mutableListOf<kotlinx.serialization.json.JsonElement>()
        for (src in sources) {
            val service = openInstalled(src)
            val matches: List<com.nyora.hasan72341.shared.model.Manga> = try {
                kotlinx.coroutines.runBlocking { service.search(query, page = 1).entries }
            } catch (_: Exception) {
                emptyList()
            }
            for (m in matches.take(3)) {
                if (m.title.lowercase().contains(query.lowercase()) ||
                    query.lowercase().contains(m.title.lowercase())) {
                    results.add(buildJsonObject {
                        put("sourceId", src.id)
                        put("sourceName", src.name)
                        put("manga", mangaToJson(m))
                    })
                }
            }
            if (results.size > 40) break // safety cap
        }
        respondJson(exchange, 200, buildJsonObject {
            put("entries", kotlinx.serialization.json.JsonArray(results))
        })
    }

    // ----- Backup -----

    private fun handleBackupExport(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val payload = buildJsonObject {
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())
            put("favourites", kotlinx.serialization.json.JsonArray(
                facade.favourites().map { mangaToJson(it) }
            ))
            put("history", kotlinx.serialization.json.JsonArray(
                facade.history(limit = 100_000).map { row ->
                    buildJsonObject {
                        put("mangaId", row.manga.id)
                        put("mangaTitle", row.manga.title)
                        put("mangaCoverUrl", row.manga.coverUrl)
                        put("sourceId", row.sourceId)
                        put("chapterId", row.chapterId)
                        put("chapterTitle", row.chapterTitle)
                        put("page", row.page)
                        put("percent", row.percent)
                        put("updatedAt", row.updatedAt)
                    }
                }
            ))
            put("categories", kotlinx.serialization.json.JsonArray(
                facade.favouriteCategories().map { cat ->
                    buildJsonObject {
                        put("id", cat.id)
                        put("title", cat.title)
                        put("sortKey", cat.sortKey)
                        put("createdAt", cat.createdAt)
                    }
                }
            ))
            // Manga <-> category membership (kept separate so category ids can be
            // remapped on import without losing which manga belong where).
            put("mangaCategories", kotlinx.serialization.json.JsonArray(
                facade.favourites().flatMap { manga ->
                    facade.categoriesForManga(manga.id).map { cat ->
                        buildJsonObject {
                            put("mangaId", manga.id)
                            put("categoryId", cat.id)
                        }
                    }
                }
            ))
            put("bookmarks", kotlinx.serialization.json.JsonArray(
                facade.bookmarks().map { bm ->
                    buildJsonObject {
                        put("mangaId", bm.mangaId)
                        put("mangaTitle", bm.mangaTitle)
                        put("mangaCoverUrl", bm.mangaCoverUrl)
                        put("chapterId", bm.chapterId)
                        put("chapterTitle", bm.chapterTitle)
                        put("page", bm.page)
                        put("note", bm.note)
                        put("createdAt", bm.createdAt)
                    }
                }
            ))
            put("mangaPrefs", kotlinx.serialization.json.JsonArray(
                facade.allMangaPrefs().map { p ->
                    buildJsonObject {
                        put("mangaId", p.mangaId)
                        put("readerMode", p.readerMode)
                        put("brightness", p.brightness)
                        put("contrast", p.contrast)
                        put("saturation", p.saturation)
                        put("hue", p.hue)
                        put("palette", p.palette)
                    }
                }
            ))
            put("sourcePrefs", kotlinx.serialization.json.JsonArray(
                facade.listSources().map { s ->
                    buildJsonObject {
                        put("sourceId", s.id)
                        put("isPinned", s.isPinned)
                    }
                }
            ))
            // Canonical cross-platform tracking section (snake_case, matching the
            // server nyora_tracking table + the iOS/Android clients). Empty until the
            // local tracking store (TS-010) is wired; the loop below then round-trips it.
            put("tracking", kotlinx.serialization.json.JsonArray(
                facade.allTracking().map { t ->
                    buildJsonObject {
                        put("tracker_id", t.trackerId)
                        put("remote_id", t.remoteId)
                        put("source_id", t.sourceId)
                        put("manga_id", t.mangaId)
                        put("title", t.title)
                        put("status", t.status)
                        put("score", t.score)
                        put("last_read_chapter", t.lastReadChapter)
                        put("last_read_volume", t.lastReadVolume)
                        put("total_chapters", t.totalChapters)
                        put("total_volumes", t.totalVolumes)
                        put("chapter_offset", t.chapterOffset)
                        put("started_at", t.startedAt)
                        put("finished_at", t.finishedAt)
                        put("comment", t.comment)
                        put("updated_at", t.updatedAt)
                        put("deleted_at", t.deletedAt)
                    }
                }
            ))
        }
        val bytes = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"nyora-backup.json\"")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleBackupImport(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val raw = exchange.requestBody.bufferedReader().readText()
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            respondError(exchange, 400, "Invalid JSON: ${e.message}"); return
        }
        fun arr(key: String) = root[key] as? kotlinx.serialization.json.JsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val favs = arr("favourites")
        val hist = arr("history")
        var importedFavs = 0
        for (el in favs) {
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: continue
            val coverUrl = o["coverUrl"]?.jsonPrimitive?.contentOrNull ?: ""
            facade.upsertManga(com.nyora.hasan72341.shared.model.Manga(
                id = id,
                title = title,
                coverUrl = coverUrl,
            ))
            // Toggle until favourited (toggleFavourite returns the new state).
            var attempts = 0
            while (!facade.isFavourited(id) && attempts < 2) {
                facade.toggleFavourite(id); attempts++
            }
            importedFavs++
        }
        var importedHist = 0
        for (el in hist) {
            val o = el.jsonObject
            val mangaId = o["mangaId"]?.jsonPrimitive?.contentOrNull ?: continue
            val sourceId = o["sourceId"]?.jsonPrimitive?.contentOrNull ?: continue
            val chapterId = o["chapterId"]?.jsonPrimitive?.contentOrNull ?: continue
            val chapterTitle = o["chapterTitle"]?.jsonPrimitive?.contentOrNull ?: ""
            val page = o["page"]?.jsonPrimitive?.intOrNull ?: 0
            val percent = o["percent"]?.jsonPrimitive?.floatOrNull ?: 0f
            facade.recordHistory(mangaId, sourceId, chapterId, chapterTitle, page, percent)
            importedHist++
        }

        // Categories: match existing by title (idempotent re-import), else create.
        // Build backup-id -> local-id map so membership can be reattached below.
        val catIdMap = HashMap<Long, Long>()
        var importedCats = 0
        for (el in arr("categories")) {
            val o = el.jsonObject
            val backupId = o["id"]?.jsonPrimitive?.longOrNull ?: continue
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: continue
            val existing = facade.favouriteCategories().firstOrNull { it.title == title }
            val localId = existing?.id ?: facade.createCategory(title)
            if (localId >= 0L) {
                catIdMap[backupId] = localId
                if (existing == null) importedCats++
            }
        }
        var importedMangaCats = 0
        for (el in arr("mangaCategories")) {
            val o = el.jsonObject
            val mangaId = o["mangaId"]?.jsonPrimitive?.contentOrNull ?: continue
            val backupCatId = o["categoryId"]?.jsonPrimitive?.longOrNull ?: continue
            val localCatId = catIdMap[backupCatId] ?: continue
            facade.addToCategory(mangaId, localCatId)
            importedMangaCats++
        }

        var importedBookmarks = 0
        for (el in arr("bookmarks")) {
            val o = el.jsonObject
            val mangaId = o["mangaId"]?.jsonPrimitive?.contentOrNull ?: continue
            val chapterId = o["chapterId"]?.jsonPrimitive?.contentOrNull ?: continue
            val chapterTitle = o["chapterTitle"]?.jsonPrimitive?.contentOrNull ?: ""
            val page = o["page"]?.jsonPrimitive?.intOrNull ?: 0
            val note = o["note"]?.jsonPrimitive?.contentOrNull ?: ""
            if (facade.isPageBookmarked(mangaId, chapterId, page)) continue
            facade.addBookmark(mangaId, chapterId, chapterTitle, page, note)
            importedBookmarks++
        }

        var importedMangaPrefs = 0
        for (el in arr("mangaPrefs")) {
            val o = el.jsonObject
            val mangaId = o["mangaId"]?.jsonPrimitive?.contentOrNull ?: continue
            facade.saveMangaPrefs(com.nyora.hasan72341.shared.repository.MangaPrefsRow(
                mangaId = mangaId,
                readerMode = o["readerMode"]?.jsonPrimitive?.contentOrNull ?: "",
                brightness = o["brightness"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                contrast = o["contrast"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                saturation = o["saturation"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                hue = o["hue"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                palette = o["palette"]?.jsonPrimitive?.contentOrNull ?: "",
            ))
            importedMangaPrefs++
        }

        // Source pin state: only toggle when it differs from the current state.
        var importedSourcePrefs = 0
        val sources = facade.listSources()
        for (el in arr("sourcePrefs")) {
            val o = el.jsonObject
            val sourceId = o["sourceId"]?.jsonPrimitive?.contentOrNull ?: continue
            val wantPinned = o["isPinned"]?.jsonPrimitive?.booleanOrNull ?: continue
            val src = sources.firstOrNull { it.id == sourceId } ?: continue
            if (src.isPinned != wantPinned) {
                facade.togglePin(sourceId)
                importedSourcePrefs++
            }
        }

        var importedTracking = 0
        for (el in arr("tracking")) {
            val o = el.jsonObject
            val trackerId = o["tracker_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val mangaId = o["manga_id"]?.jsonPrimitive?.contentOrNull ?: continue
            facade.saveTracking(com.nyora.hasan72341.shared.repository.TrackingRow(
                trackerId = trackerId,
                remoteId = o["remote_id"]?.jsonPrimitive?.contentOrNull ?: "",
                sourceId = o["source_id"]?.jsonPrimitive?.contentOrNull ?: "",
                mangaId = mangaId,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                status = o["status"]?.jsonPrimitive?.contentOrNull ?: "",
                score = o["score"]?.jsonPrimitive?.floatOrNull ?: 0f,
                lastReadChapter = o["last_read_chapter"]?.jsonPrimitive?.floatOrNull ?: 0f,
                lastReadVolume = o["last_read_volume"]?.jsonPrimitive?.intOrNull ?: 0,
                totalChapters = o["total_chapters"]?.jsonPrimitive?.intOrNull ?: 0,
                totalVolumes = o["total_volumes"]?.jsonPrimitive?.intOrNull ?: 0,
                chapterOffset = o["chapter_offset"]?.jsonPrimitive?.intOrNull ?: 0,
                startedAt = o["started_at"]?.jsonPrimitive?.contentOrNull ?: "",
                finishedAt = o["finished_at"]?.jsonPrimitive?.contentOrNull ?: "",
                comment = o["comment"]?.jsonPrimitive?.contentOrNull ?: "",
                updatedAt = o["updated_at"]?.jsonPrimitive?.contentOrNull ?: "",
                deletedAt = o["deleted_at"]?.jsonPrimitive?.contentOrNull ?: "",
            ))
            importedTracking++
        }

        respondJson(exchange, 200, buildJsonObject {
            put("ok", true)
            put("importedFavourites", importedFavs)
            put("importedHistory", importedHist)
            put("importedCategories", importedCats)
            put("importedMangaCategories", importedMangaCats)
            put("importedBookmarks", importedBookmarks)
            put("importedMangaPrefs", importedMangaPrefs)
            put("importedSourcePrefs", importedSourcePrefs)
            put("importedTracking", importedTracking)
        })
    }

    /// Compact JSON projection of a Manga used by suggestions/alternatives.
    private fun mangaToJson(m: com.nyora.hasan72341.shared.model.Manga): kotlinx.serialization.json.JsonElement {
        val ref = m.source
        val sid = when (ref) {
            is com.nyora.hasan72341.shared.model.MangaSourceRef.Parser -> "parser:${ref.name}"
            is com.nyora.hasan72341.shared.model.MangaSourceRef.Mihon -> "mihon:${ref.sourceId}"
            is com.nyora.hasan72341.shared.model.MangaSourceRef.Script -> ref.toOpenableSourceId()
            else -> ""
        }
        return buildJsonObject {
            put("id", m.id)
            put("title", m.title)
            put("coverUrl", m.coverUrl)
            put("sourceId", sid)
            put("publicUrl", m.publicUrl)
            put("description", m.description)
        }
    }
}

/** Self-contained Swagger UI page (CDN assets) that loads the spec from same-origin /openapi.yaml. */
private val SWAGGER_UI_HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Nyora Parser API — Reference</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css" />
  <style>
    body { margin: 0; background: #0f1115; }
    .nyora-topbar { display:flex; align-items:center; gap:14px; padding:14px 22px;
      background:#15181f; border-bottom:1px solid #262b36;
      font:600 16px/1.2 -apple-system,system-ui,Segoe UI,Roboto,sans-serif; color:#e8eaed; }
    .nyora-topbar .dot { width:10px; height:10px; border-radius:50%; background:#6ea8fe; }
    .nyora-topbar .spacer { flex:1; }
    .nyora-topbar a { font-size:13px; font-weight:500; color:#9bb7ff; text-decoration:none;
      padding:6px 10px; border:1px solid #2c3344; border-radius:8px; }
    .nyora-topbar a:hover { background:#1c212c; }
    .swagger-ui .topbar { display:none; }
  </style>
</head>
<body>
  <div class="nyora-topbar">
    <span class="dot"></span><span>Nyora Parser API</span>
    <span class="spacer"></span><a href="/openapi.yaml" download>openapi.yaml</a>
  </div>
  <div id="swagger-ui"></div>
  <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js" crossorigin></script>
  <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-standalone-preset.js" crossorigin></script>
  <script>
    window.ui = SwaggerUIBundle({
      url: "/openapi.yaml",
      dom_id: "#swagger-ui",
      deepLinking: true,
      docExpansion: "list",
      defaultModelsExpandDepth: 1,
      defaultModelExpandDepth: 3,
      tryItOutEnabled: true,
      filter: true,
      displayRequestDuration: true,
      presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
      layout: "StandaloneLayout"
    });
  </script>
</body>
</html>
""".trimIndent()

/**
 * HTTP server executor. On JDK 21+ this is a virtual-thread-per-task executor (Project
 * Loom): each request runs on its own cheap virtual thread, so thousands of concurrent,
 * I/O-bound parser requests cost ~tens of MB instead of one OS-thread stack each — the
 * key to serving ~1k users in a tight RAM budget. Falls back to a bounded pool pre-21.
 */
/**
 * Overload + abuse guard applied to every route. Two protections so the small VM
 * degrades gracefully under load (target: survive 5k users) instead of thrashing:
 *  - Global in-flight cap → shed excess with 503 (bounds threads/memory/CPU).
 *  - Per-client-IP token bucket → one abuser can't monopolize the box (429).
 * Loopback + long-poll (device relay) bypass the caps. Tunable via env.
 */
private object GuardFilter : Filter() {
    private val maxInFlight = System.getenv("NYORA_MAX_INFLIGHT")?.toIntOrNull() ?: 150
    private val perIpRate = System.getenv("NYORA_PER_IP_RATE")?.toDoubleOrNull() ?: 25.0   // tokens/sec
    private val perIpBurst = System.getenv("NYORA_PER_IP_BURST")?.toDoubleOrNull() ?: 60.0

    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    private class Bucket(var tokens: Double, var last: Long)
    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Bucket>()

    private fun clientIp(exchange: HttpExchange): String {
        exchange.requestHeaders.getFirst("X-Real-IP")?.let { if (it.isNotBlank()) return it.trim() }
        exchange.requestHeaders.getFirst("X-Forwarded-For")?.let {
            val first = it.substringBefore(',').trim(); if (first.isNotBlank()) return first
        }
        return exchange.remoteAddress?.address?.hostAddress ?: "?"
    }

    private fun allow(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val b = buckets.computeIfAbsent(ip) { Bucket(perIpBurst, now) }
        synchronized(b) {
            b.tokens = minOf(perIpBurst, b.tokens + (now - b.last) / 1000.0 * perIpRate)
            b.last = now
            if (buckets.size > 20_000) buckets.clear() // crude cap so the map can't grow unbounded
            if (b.tokens < 1.0) return false
            b.tokens -= 1.0
            return true
        }
    }

    private fun reject(exchange: HttpExchange, code: Int, msg: String) {
        try {
            val bytes = msg.toByteArray()
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Retry-After", "2")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: Throwable) { /* client gone */ } finally { exchange.close() }
    }

    override fun doFilter(exchange: HttpExchange, chain: Chain) {
        val ip = clientIp(exchange)
        val path = exchange.requestURI.path.orEmpty()
        val loopback = ip == "127.0.0.1" || ip == "::1" || ip == "?"
        // Long-poll + health bypass admission control (they must stay responsive).
        val bypass = loopback || path.startsWith("/device/relay/poll") || path == "/health"

        if (!bypass && !allow(ip)) { reject(exchange, 429, "Too many requests"); return }
        if (!bypass) {
            if (inFlight.incrementAndGet() > maxInFlight) {
                inFlight.decrementAndGet()
                reject(exchange, 503, "Server busy, retry shortly")
                return
            }
        }
        try {
            chain.doFilter(exchange)
        } finally {
            if (!bypass) inFlight.decrementAndGet()
        }
    }

    override fun description() = "Nyora overload/rate guard"
}

/** createContext + attach the shared overload/rate guard. */
private fun HttpServer.guardedContext(path: String, handler: HttpHandler) {
    createContext(path, handler).filters.add(GuardFilter)
}

private fun newServerExecutor(): java.util.concurrent.Executor =
    runCatching {
        java.util.concurrent.Executors::class.java
            .getMethod("newVirtualThreadPerTaskExecutor")
            .invoke(null) as java.util.concurrent.Executor
    }.getOrElse {
        java.util.concurrent.Executors.newFixedThreadPool(200)
    }

private fun com.nyora.hasan72341.shared.model.MangaSource.toAndroidJsSourceName(): String {
    val parserId = id.removePrefix("parser:")
    return if (parserId == id) name else "JS_$parserId"
}


private fun com.nyora.hasan72341.shared.model.MangaSourceRef.Script.toOpenableSourceId(): String {
    val parserId = name.removePrefix("JS_")
    return "parser:$parserId"
}

/// Resolve a history row to a source id the client can actually open.
/// The manga's source ref wins; the stored column is only trusted when it is a
/// clean, recognizable id (never a leaked Kotlin class string). Returns "" when
/// the source is genuinely unknown (e.g. a legacy non-JS row) so the client can
/// degrade gracefully instead of forwarding garbage to /details.
private fun openableHistorySourceId(
    ref: com.nyora.hasan72341.shared.model.MangaSourceRef,
    storedSourceId: String,
): String {
    val fromRef = when (ref) {
        is com.nyora.hasan72341.shared.model.MangaSourceRef.Parser -> "parser:${ref.name}"
        is com.nyora.hasan72341.shared.model.MangaSourceRef.Script -> ref.toOpenableSourceId()
        is com.nyora.hasan72341.shared.model.MangaSourceRef.Mihon -> "mihon:${ref.sourceId}"
        is com.nyora.hasan72341.shared.model.MangaSourceRef.Local -> "local:"
        com.nyora.hasan72341.shared.model.MangaSourceRef.Unknown -> ""
    }
    if (fromRef.isNotBlank()) return fromRef
    val stored = storedSourceId.trim()
    return when {
        stored.startsWith("parser:") ||
            stored.startsWith("mihon:") ||
            stored.startsWith("local:") -> stored
        else -> ""
    }
}

@kotlinx.serialization.Serializable
private data class SourceListResponse(val sources: List<MangaSource>)

@kotlinx.serialization.Serializable
private data class SourceResponse(val source: MangaSource)

@kotlinx.serialization.Serializable
private data class BrowseResponse(
    val entries: List<Manga>,
    val hasNextPage: Boolean,
)

@kotlinx.serialization.Serializable
private data class DetailsResponse(
    val manga: Manga,
    val chapters: List<MangaChapter>,
)

@kotlinx.serialization.Serializable
private data class PagesResponse(val pages: List<MangaPage>)

@kotlinx.serialization.Serializable
private data class CatalogResponse(val entries: List<CatalogEntry>)

@kotlinx.serialization.Serializable
private data class HistoryResponse(val entries: List<HistoryRowDto>)

@kotlinx.serialization.Serializable
private data class HistoryRowDto(
    val mangaId: String,
    val mangaUrl: String,
    val mangaTitle: String,
    val mangaCoverUrl: String,
    val sourceId: String,
    val sourceName: String,
    val chapterId: String,
    val chapterTitle: String,
    val page: Int,
    val percent: Float,
    val updatedAt: Long,
)

@kotlinx.serialization.Serializable
private data class FavouritesResponse(val entries: List<Manga>)

@kotlinx.serialization.Serializable
private data class MangaPrefsDto(
    val mangaId: String,
    val readerMode: String,
    val brightness: Double,
    val contrast: Double,
    val saturation: Double,
    val hue: Double,
    val palette: String,
    val present: Boolean,
)

@kotlinx.serialization.Serializable
private data class DownloadsResponse(val entries: List<DownloadDto>)

@kotlinx.serialization.Serializable
private data class DownloadResponse(val entry: DownloadDto)

@kotlinx.serialization.Serializable
private data class DownloadDto(
    val id: String,
    val sourceId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val totalPages: Int,
    val completedPages: Int,
    val failedPages: Int,
    val status: String,
    val filePath: String?,
    val error: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)

@kotlinx.serialization.Serializable
private data class CategoriesResponse(val categories: List<CategoryDto>)

@kotlinx.serialization.Serializable
private data class CategoryDto(val id: Long, val title: String, val mangaCount: Int)

@kotlinx.serialization.Serializable
private data class GlobalSearchResponse(
    val query: String,
    val groups: List<GlobalSearchGroup>,
)

@kotlinx.serialization.Serializable
private data class GlobalSearchGroup(
    val sourceId: String,
    val sourceName: String,
    val entries: List<Manga>,
    val error: String? = null,
)

@kotlinx.serialization.Serializable
private data class LocalScanResponse(val entries: List<LocalCbzEntry>)

@kotlinx.serialization.Serializable
private data class LocalCbzEntry(val path: String, val name: String, val sizeBytes: Long)

@kotlinx.serialization.Serializable
private data class LocalChapterResponse(val name: String, val pageCount: Int, val pageUrls: List<String>)

@kotlinx.serialization.Serializable
private data class UpdatesResponse(val entries: List<UpdateDto>)

@kotlinx.serialization.Serializable
private data class UpdateDto(
    val mangaId: String,
    val mangaTitle: String,
    val mangaCoverUrl: String,
    val sourceId: String,
    val newChapters: Int,
    val totalChapters: Int,
    val latestChapterTitle: String,
    val lastSyncedAt: Long,
)

@kotlinx.serialization.Serializable
private data class BookmarksResponse(val entries: List<BookmarkDto>)

@kotlinx.serialization.Serializable
private data class BookmarkDto(
    val id: Long,
    val mangaId: String,
    val mangaTitle: String,
    val mangaCoverUrl: String,
    val chapterId: String,
    val chapterTitle: String,
    val page: Int,
    val note: String,
    val createdAt: Long,
)

@kotlinx.serialization.Serializable
private data class CatalogEntry(
    val id: String,
    val name: String,
    val lang: String,
    val engine: String,
    val contentType: String,
    val isBroken: Boolean,
    val isInstalled: Boolean,
    val isNsfw: Boolean = false,
)

@kotlinx.serialization.Serializable
private data class DownloadSettingsResponse(val settings: DownloadSettings)

@kotlinx.serialization.Serializable
private data class NetworkSettingsResponse(val settings: HelperNetworkSettings)
