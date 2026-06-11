package com.nyora.hasan72341.shared.proxy

import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.data.ExtensionInstaller
import com.nyora.hasan72341.shared.data.SourceCatalogClient
import com.nyora.hasan72341.shared.download.DownloadFormat
import com.nyora.hasan72341.shared.download.DownloadSettings
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.net.HelperNetworkSettings
import com.nyora.hasan72341.shared.model.Library
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.reader.PageImageLoader
import com.sun.net.httpserver.HttpExchange
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.floatOrNull
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
    // Emit permissive CORS headers on responses. Harmless for the same-origin
    // web UI; useful if the API is ever consumed cross-origin.
    private val corsEnabled: Boolean = staticRoot != null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private var server: HttpServer? = null

    val baseUrl: String
        get() = server?.address?.let { "http://${it.address.hostAddress}:${it.port}" }.orEmpty()

    val isRunning: Boolean
        get() = server != null

    fun start(): String {
        if (server != null) return baseUrl
        val httpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
            createContext("/health") { respondJson(it, 200, buildJsonObject { put("status", "ok") }) }
            createContext("/sources/refresh") { handleRefresh(it) }
            createContext("/sources/catalog") { handleCatalog(it) }
            createContext("/sources/install") { handleInstall(it) }
            createContext("/sources/uninstall") { handleUninstall(it) }
            createContext("/sources/pin") { handlePin(it) }
            createContext("/sources/filters") { handleFilters(it) }
            createContext("/sources/popular") { handleBrowse(it, BrowseMode.POPULAR) }
            createContext("/sources/latest") { handleBrowse(it, BrowseMode.LATEST) }
            createContext("/sources/search") { handleBrowse(it, BrowseMode.SEARCH) }
            createContext("/cloudflare/clearance") { handleCloudflareClearance(it) }
            createContext("/sources") { handleSources(it) }
            createContext("/manga/details") { handleDetails(it) }
            createContext("/manga/pages") { handlePages(it) }
            createContext("/image") { handleImage(it) }
            createContext("/library/history/record") { handleHistoryRecord(it) }
            createContext("/library/history/remove") { handleHistoryRemove(it) }
            createContext("/library/history/clear") { handleHistoryClear(it) }
            createContext("/library/history") { handleHistory(it) }
            createContext("/library/clear") { handleLibraryClear(it) }
            createContext("/library/favourites/toggle") { handleFavouriteToggle(it) }
            createContext("/library/favourites/check") { handleFavouriteCheck(it) }
            createContext("/library/favourites") { handleFavourites(it) }
            createContext("/library/bookmarks/add") { handleBookmarkAdd(it) }
            createContext("/library/bookmarks/remove") { handleBookmarkRemove(it) }
            createContext("/library/bookmarks/check") { handleBookmarkCheck(it) }
            createContext("/library/bookmarks") { handleBookmarks(it) }
            createContext("/library/updates/refresh") { handleUpdatesRefresh(it) }
            createContext("/library/updates/seen") { handleUpdatesSeen(it) }
            createContext("/library/updates") { handleUpdates(it) }
            createContext("/local/scan") { handleLocalScan(it) }
            createContext("/local/chapter") { handleLocalChapter(it) }
            createContext("/local/image") { handleLocalImage(it) }
            createContext("/search/global") { handleGlobalSearch(it) }
            createContext("/library/categories/create") { handleCategoryCreate(it) }
            createContext("/library/categories/rename") { handleCategoryRename(it) }
            createContext("/library/categories/delete") { handleCategoryDelete(it) }
            createContext("/library/categories/add") { handleCategoryAdd(it) }
            createContext("/library/categories/remove") { handleCategoryRemove(it) }
            createContext("/library/categories/manga") { handleCategoriesForManga(it) }
            createContext("/library/categories") { handleCategories(it) }
            createContext("/manga/prefs/save") { handleMangaPrefsSave(it) }
            createContext("/manga/prefs/clear") { handleMangaPrefsClear(it) }
            createContext("/manga/prefs") { handleMangaPrefs(it) }
            createContext("/downloads/start") { handleDownloadStart(it) }
            createContext("/downloads/enqueue") { handleDownloadEnqueue(it) }
            createContext("/downloads/cancel") { handleDownloadCancel(it) }
            createContext("/downloads/settings") { handleDownloadSettings(it) }
            createContext("/settings/network") { handleNetworkSettings(it) }
            createContext("/downloads") { handleDownloads(it) }
            createContext("/stats") { handleStats(it) }
            createContext("/suggestions") { handleSuggestions(it) }
            createContext("/manga/alternatives") { handleAlternatives(it) }
            createContext("/backup/export") { handleBackupExport(it) }
            createContext("/backup/import") { handleBackupImport(it) }
            createContext("/tracker/anilist/search") { handleAniListSearch(it) }
            createContext("/tracker/anilist/scrobble") { handleAniListScrobble(it) }
            createContext("/supabase/status") { handleSupabaseStatus(it) }
            createContext("/supabase/signin") { handleSupabaseSignIn(it) }
            createContext("/supabase/signout") { handleSupabaseSignOut(it) }
            createContext("/supabase/sync") { handleSupabaseSync(it) }
            createContext("/supabase/restore-from-cloud") { handleSupabaseRestoreFromCloud(it) }
            createContext("/supabase/has-local-data") { handleSupabaseHasLocalData(it) }
            createContext("/ota/check") { handleOtaCheck(it) }
            createContext("/ota/status") { handleOtaStatus(it) }
            createContext("/") { handleRoot(it) }
            executor = Executors.newCachedThreadPool()
            start()
        }
        server = httpServer
        return baseUrl
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

    private fun applyCors(exchange: HttpExchange) {
        if (!corsEnabled) return
        val headers = exchange.responseHeaders
        if (headers.containsKey("Access-Control-Allow-Origin")) return
        headers.add("Access-Control-Allow-Origin", "*")
        headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        headers.add("Access-Control-Allow-Headers", "Content-Type")
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

    private fun getJsSources(): List<com.nyora.hasan72341.shared.model.MangaSource> {
        val otaBase = com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository
            .defaultDatabasePath()
            .parent
        val jsonText = com.nyora.hasan72341.shared.extension.ParserOtaUpdater.sources(otaBase)
            ?: javaClass.classLoader.getResourceAsStream("parsers_sources.json")?.bufferedReader()?.readText()
            ?: return emptyList()
        val array = kotlinx.serialization.json.Json.parseToJsonElement(jsonText).let {
            if (it is kotlinx.serialization.json.JsonArray) it else kotlinx.serialization.json.JsonArray(emptyList())
        }
        val sources = mutableListOf<com.nyora.hasan72341.shared.model.MangaSource>()
        for (element in array) {
            if (element !is kotlinx.serialization.json.JsonObject) continue
            val idStr = element["id"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: continue
            val titleStr = element["title"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: idStr
            val domainStr = element["domain"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
            val localeStr = element["locale"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: "all"
            val isNsfw = element["isNsfw"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toBoolean() ?: false
            
            sources.add(
                com.nyora.hasan72341.shared.model.MangaSource(
                    id = "parser:$idStr",
                    name = com.nyora.hasan72341.shared.SourcePatches.TITLE_OVERRIDES[idStr] ?: titleStr,
                    lang = localeStr,
                    baseUrl = "https://$domainStr",
                    isInstalled = false,
                    isNsfw = isNsfw,
                    engine = com.nyora.hasan72341.shared.model.SourceEngine.JavaScript,
                    contentType = com.nyora.hasan72341.shared.model.SourceContentType.Manga,
                    notes = "Built-in JS parser.",
                    localPath = "classpath:parsers.bundle.js",
                    canUninstall = false,
                    installedAt = 0L,
                )
            )
        }
        return sources
    }

    private fun handleCatalog(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val installed = facade.listSources().filter { it.isInstalled }.map { it.id }.toSet()
        val entries = getJsSources().map { source ->
            CatalogEntry(
                id = source.id,
                name = source.name,
                lang = source.lang,
                engine = com.nyora.hasan72341.shared.model.SourceEngine.JavaScript.name,
                contentType = source.contentType.name,
                isBroken = false,
                isInstalled = source.id in installed,
            )
        }
        respondJson(exchange, 200, json.encodeToJsonElement(CatalogResponse(entries)))
    }

    private fun handleInstall(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
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
                    engine = com.nyora.hasan72341.shared.model.SourceEngine.JavaScript,
                    localPath = "classpath:parsers.bundle.js",
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
            source.engine == com.nyora.hasan72341.shared.model.SourceEngine.JavaScript

    private fun handleUninstall(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("DELETE", ignoreCase = true) &&
            !exchange.requestMethod.equals("POST", ignoreCase = true)
        ) {
            respondText(exchange, 405, "Method not allowed"); return
        }
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
            val service = facade.openExtension(source)
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
        val c = page.url.trim()
        if (c.isEmpty() || c.startsWith("data:")) return page
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
        val source = facade.listSources().firstOrNull { it.id == id }
            ?: return respondError(exchange, 404, "Unknown source: $id")
        // Filters are passed as a URL-encoded JSON array in the 'f' parameter:
        //   [{"name":"Genre","type":"select","selectedIndex":2}, …]
        val filters = parseFilters(params["f"])
        try {
            val service = facade.openExtension(source)
            val result = runBlocking {
                when (mode) {
                    BrowseMode.POPULAR -> service.getPopular(page)
                    BrowseMode.LATEST -> service.getLatest(page)
                    BrowseMode.SEARCH -> service.search(params["q"].orEmpty(), page, filters)
                }
            }
            respondJson(exchange, 200, json.encodeToJsonElement(
                BrowseResponse(
                    entries = result.entries.map { it.copy(coverUrl = proxyCoverUrl(it.coverUrl, source.baseUrl)) },
                    hasNextPage = result.hasNextPage,
                ),
            ))
        } catch (error: Exception) {
            respondError(exchange, 500, error.message ?: "Browse failed")
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
            val service = facade.openExtension(source)
            val details = runBlocking { service.getDetails(url) }
            val manga = details.manga.copy(
                source = com.nyora.hasan72341.shared.model.MangaSourceRef.Script(source.toAndroidJsSourceName()),
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
            val service = facade.openExtension(source)
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
        val gate = kotlinx.coroutines.sync.Semaphore(48)
        val results = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.coroutineScope {
                installed.map { src ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                kotlinx.coroutines.withTimeout(15_000L) {
                                    val service = facade.openExtension(src)
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
                    val service = facade.openExtension(source)
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
        try {
            val bytes = pageLoader.loadBytes(url, headers)
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
        val idToken = params["idToken"]
        if (idToken.isNullOrBlank()) {
            return respondError(exchange, 400, "Missing 'idToken'")
        }
        val error = facade.supabaseSignInWithGoogle(idToken)
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

    private fun otaBaseDir() =
        com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository.defaultDatabasePath().parent

    private fun handleOtaStatus(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        respondJson(exchange, 200, buildJsonObject {
            val base = otaBaseDir()
            val otaVer = com.nyora.hasan72341.shared.extension.ParserOtaUpdater.otaVersion(base)
            val isActive = com.nyora.hasan72341.shared.extension.ParserOtaUpdater.isActive(base)
            put("bundledVersion", com.nyora.hasan72341.shared.extension.ParserOtaUpdater.BUNDLED_VERSION)
            put("otaVersion", otaVer)
            put("isActive", isActive)
        })
    }

    private fun handleOtaCheck(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respondText(exchange, 405, "Method not allowed"); return
        }
        val base = otaBaseDir()
        runCatching {
            com.nyora.hasan72341.shared.extension.ParserOtaUpdater.updateOnce(base, networkConfig)
        }.onFailure { println("[OTA] manual check failed: ${it.message}") }
        respondJson(exchange, 200, buildJsonObject {
            val otaVer = com.nyora.hasan72341.shared.extension.ParserOtaUpdater.otaVersion(base)
            val isActive = com.nyora.hasan72341.shared.extension.ParserOtaUpdater.isActive(base)
            put("bundledVersion", com.nyora.hasan72341.shared.extension.ParserOtaUpdater.BUNDLED_VERSION)
            put("otaVersion", otaVer)
            put("isActive", isActive)
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
            facade.openExtension(source).getFilterList()
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
        val service = facade.openExtension(parserSource)
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
            val service = facade.openExtension(src)
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
            put("version", 1)
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
                    }
                }
            ))
            put("bookmarks", kotlinx.serialization.json.JsonArray(
                facade.bookmarks().map { bm ->
                    buildJsonObject {
                        put("mangaId", bm.mangaId)
                        put("mangaTitle", bm.mangaTitle)
                        put("chapterId", bm.chapterId)
                        put("chapterTitle", bm.chapterTitle)
                        put("page", bm.page)
                        put("note", bm.note)
                        put("createdAt", bm.createdAt)
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
        val favs = root["favourites"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: kotlinx.serialization.json.JsonArray(emptyList())
        val hist = root["history"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: kotlinx.serialization.json.JsonArray(emptyList())
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
        respondJson(exchange, 200, buildJsonObject {
            put("ok", true)
            put("importedFavourites", importedFavs)
            put("importedHistory", importedHist)
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
)

@kotlinx.serialization.Serializable
private data class DownloadSettingsResponse(val settings: DownloadSettings)

@kotlinx.serialization.Serializable
private data class NetworkSettingsResponse(val settings: HelperNetworkSettings)
