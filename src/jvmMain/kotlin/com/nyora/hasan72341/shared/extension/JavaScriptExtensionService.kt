package com.nyora.hasan72341.shared.extension

import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import com.nyora.hasan72341.shared.extension.MangaSearchPage
import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.net.RemoteUrlKind
import com.nyora.hasan72341.shared.net.buildOkHttpClient
import com.nyora.hasan72341.shared.net.defaultHelperUserAgent
import com.nyora.hasan72341.shared.net.fetchText
import com.nyora.hasan72341.shared.net.rewriteRemoteUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.util.Base64
import kotlin.io.path.Path
import kotlin.io.path.readText

class JavaScriptExtensionService(
    private val source: MangaSource,
    private val networkConfig: HelperNetworkConfig = HelperNetworkConfig(),
) : MangaExtensionService {
    
    // We share a single GraalJS context for all parsers to avoid evaluating the large bundle multiple times.
    // However, for simplicity in this migration, we'll keep it per-instance but evaluate the bundle.
    private val context: Context by lazy { createContext() }

    // GraalVM polyglot Contexts are THREAD-CONFINED: a Context built without explicit
    // multi-thread access (as ours is, see createContext) records the first thread that
    // enters it and throws IllegalStateException ("Multi threaded access requested by
    // thread ... but is not allowed") if a DIFFERENT thread later enters — even when the
    // accesses never overlap in time. The previous `synchronized(evalLock)` only prevented
    // concurrent access; it did NOT pin a single owning thread, so successive REST requests
    // arriving on different Dispatchers.IO / cached-thread-pool threads crashed on Windows.
    //
    // Fix: confine ALL Context access to one dedicated daemon thread per source. The
    // single-thread executor also serialises calls (FIFO task queue), so the old lock is
    // redundant. This is per-source, so different sources keep running in parallel exactly
    // as before (each already had its own Context).
    //
    // Lifecycle note: this executor is a daemon and lives for the lifetime of the cached
    // service (services are cached per source for the session — see JvmExtensionRuntime).
    // No explicit shutdown is performed. If sources are ever evicted from that cache, add
    // a close()/jsThread.shutdown() hook to avoid leaking one thread per evicted source.
    private val jsThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "nyora-js-$jsEngineId").apply { isDaemon = true }
    }

    private val jsEngineId: String
        get() = source.id.removePrefix("parser:")
    private val sourceRefName: String
        get() = "JS_$jsEngineId"

    override val supportsLatest: Boolean = true

    override fun getHeaders(): Map<String, String> {
        return emptyMap()
    }

    // NOTE: the `map` lambda runs ON the source's owning JS thread (see invokeParserAsync)
    // because it reads members off the returned GraalVM Value, and Value access re-enters
    // the thread-confined Context. The lambda returns only plain Kotlin data classes, which
    // are safe to hand back to the calling pool thread.
    override suspend fun getPopular(page: Int): MangaSearchPage =
        invokeParserAsync("list", mapOf("page" to page, "order" to "POPULARITY")) { mapMangaPage(it) }

    override suspend fun getLatest(page: Int): MangaSearchPage =
        invokeParserAsync("list", mapOf("page" to page, "order" to "UPDATED")) { mapMangaPage(it) }

    override suspend fun search(
        query: String,
        page: Int,
        filters: List<SourceFilter>,
    ): MangaSearchPage {
        // TODO map filters if needed
        return invokeParserAsync("list", mapOf("page" to page, "order" to "RELEVANCE", "filter" to mapOf("query" to query))) { mapMangaPage(it) }
    }

    override suspend fun getDetails(url: String): MangaDetails {
        return invokeParserAsync("details", mapOf("url" to url, "id" to url)) { result ->
            val manga = mapManga(result) ?: error("Extension returned no manga for details")
            val chapters = result.getMember("chapters")?.let { mapChapters(it) }.orEmpty()
            MangaDetails(manga = manga, chapters = chapters)
        }
    }

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        return invokeParserAsync("pages", mapOf("url" to chapter.url, "id" to chapter.url)) { mapPages(it) }
    }

    private fun createContext(): Context {
        // Prefer an OTA-downloaded bundle (verified at download time); fall back to
        // the copy bundled into the JAR at build time.
        val otaBase = com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository.defaultDatabasePath().parent
        val bundleText = ParserOtaUpdater.bundle(otaBase)
            ?: javaClass.classLoader.getResourceAsStream("parsers.bundle.js")?.bufferedReader()?.readText()
            ?: error("Missing parsers.bundle.js")
        // WHATWG URL / URLSearchParams / atob / btoa / TextEncoder / TextDecoder —
        // absent from a bare GraalVM "js" Context, needed by base.js + several
        // parser families. Evaluated before the bundle. (Browser/iOS have them natively.)
        val polyfills = javaClass.classLoader.getResourceAsStream("parser-polyfills.js")?.bufferedReader()?.readText()
            ?: error("Missing parser-polyfills.js")

        val prelude = """
            var window = {};
            console = {
                log: (...args) => host.log(args.map(String).join(' ')),
                warn: (...args) => host.log(args.map(String).join(' ')),
                error: (...args) => host.log(args.map(String).join(' '))
            };

            // Wraps a Jsoup Node — text nodes get nodeType 3 + text accessors so
            // parsers that walk childNodes (scan.js, fuzzydoodle.js) work; anything
            // else is treated as an element.
            function __wrapNode(node) {
                if (node == null) return null;
                var name = '';
                try { name = String(node.nodeName()); } catch(e) {}
                if (name === '#text' || name === '#comment' || name === '#data') {
                    return {
                        _j: node,
                        nodeType: name === '#text' ? 3 : (name === '#comment' ? 8 : 4),
                        nodeName: name,
                        get textContent() { try { return String(node.text ? node.text() : ''); } catch(e) { return ''; } },
                        get nodeValue() { try { return String(node.text ? node.text() : ''); } catch(e) { return ''; } },
                        get wholeText() { try { return String(node.getWholeText ? node.getWholeText() : (node.text ? node.text() : '')); } catch(e) { return ''; } }
                    };
                }
                return __wrapJsoup(node);
            }

            // Wraps a Jsoup Element/Document in a DOM-like interface so web parsers
            // can call querySelectorAll / getAttribute / textContent / etc unchanged.
            function __wrapJsoup(jel) {
                if (jel == null) return null;
                return {
                    _j: jel,
                    nodeType: 1,
                    get tagName() {
                        try { return String(jel.tagName()).toUpperCase(); } catch(e) { return ''; }
                    },
                    get nodeName() {
                        try { return String(jel.tagName()).toUpperCase(); } catch(e) { return ''; }
                    },
                    get id() {
                        try { return String(jel.id ? jel.id() : (jel.attr('id') || '')); } catch(e) { return ''; }
                    },
                    get className() {
                        try { return String(jel.className ? jel.className() : (jel.attr('class') || '')); } catch(e) { return ''; }
                    },
                    get classList() {
                        var cn = '';
                        try { cn = String(jel.className ? jel.className() : (jel.attr('class') || '')); } catch(e) {}
                        var list = cn.split(/\s+/).filter(function(x) { return x; });
                        return {
                            length: list.length,
                            contains: function(c) { return list.indexOf(String(c)) >= 0; },
                            item: function(i) { return list[i]; }
                        };
                    },
                    get value() {
                        try { return String(jel.val ? jel.val() : (jel.attr('value') || '')); } catch(e) { return ''; }
                    },
                    get innerText() {
                        try { return String(jel.text()); } catch(e) { return ''; }
                    },
                    get textContent() {
                        try { return String(jel.text()); } catch(e) { return ''; }
                    },
                    get innerHTML() {
                        try { return String(jel.html()); } catch(e) { return ''; }
                    },
                    get outerHTML() {
                        try { return String(jel.outerHtml()); } catch(e) { return ''; }
                    },
                    get parentElement() {
                        try { var p = jel.parent(); return p ? __wrapJsoup(p) : null; } catch(e) { return null; }
                    },
                    get nextElementSibling() {
                        try { var s = jel.nextElementSibling(); return s ? __wrapJsoup(s) : null; } catch(e) { return null; }
                    },
                    get children() {
                        try {
                            var kids = jel.children(); var arr = [];
                            for (var i = 0; i < kids.size(); i++) arr.push(__wrapJsoup(kids.get(i)));
                            return arr;
                        } catch(e) { return []; }
                    },
                    get style() { return { display: '' }; },
                    hasAttribute: function(n) {
                        try { return !!jel.hasAttr(String(n)); } catch(e) { return false; }
                    },
                    getAttribute: function(n) {
                        try {
                            if (!jel.hasAttr(String(n))) return null;
                            return String(jel.attr(String(n)));
                        } catch(e) { return null; }
                    },
                    querySelector: function(css) {
                        try {
                            var els = jel.select(String(css));
                            return (!els || els.isEmpty()) ? null : __wrapJsoup(els.get(0));
                        } catch(e) { return null; }
                    },
                    querySelectorAll: function(css) {
                        try {
                            var els = jel.select(String(css)); var arr = [];
                            for (var i = 0; i < els.size(); i++) arr.push(__wrapJsoup(els.get(i)));
                            return arr;
                        } catch(e) { return []; }
                    },
                    closest: function(css) {
                        try { var p = jel.closest(String(css)); return p ? __wrapJsoup(p) : null; } catch(e) { return null; }
                    },
                    getElementById: function(id) {
                        try {
                            var e = (typeof jel.getElementById === 'function') ? jel.getElementById(String(id)) : null;
                            if (!e) { var r = jel.select('#' + String(id)); e = (r && !r.isEmpty()) ? r.get(0) : null; }
                            return e ? __wrapJsoup(e) : null;
                        } catch(e) { return null; }
                    },
                    getElementsByClassName: function(c) {
                        try { return this.querySelectorAll('.' + String(c)); } catch(e) { return []; }
                    },
                    getElementsByTagName: function(t) {
                        try { return this.querySelectorAll(String(t)); } catch(e) { return []; }
                    },
                    matches: function(sel) {
                        try { return jel.is ? !!jel.is(String(sel)) : false; } catch(e) { return false; }
                    },
                    remove: function() {
                        try { if (jel.remove) jel.remove(); } catch(e) { /* ignore */ }
                    },
                    cloneNode: function() {
                        try { return jel.clone ? __wrapJsoup(jel.clone()) : this; } catch(e) { return this; }
                    },
                    get childNodes() {
                        try {
                            var nodes = jel.childNodes(); var arr = [];
                            for (var i = 0; i < nodes.size(); i++) arr.push(__wrapNode(nodes.get(i)));
                            return arr;
                        } catch(e) { return []; }
                    },
                    get firstElementChild() {
                        try { var f = jel.firstElementChild(); return f ? __wrapJsoup(f) : null; } catch(e) { return null; }
                    },
                    get lastElementChild() {
                        try { var l = jel.lastElementChild(); return l ? __wrapJsoup(l) : null; } catch(e) { return null; }
                    },
                    get previousElementSibling() {
                        try { var s = jel.previousElementSibling(); return s ? __wrapJsoup(s) : null; } catch(e) { return null; }
                    },
                    get documentElement() {
                        try { var h = jel.select('html'); if (h && !h.isEmpty()) return __wrapJsoup(h.get(0)); } catch(e) {}
                        return this;
                    }
                };
            }

            // Remembers domains that sources have redirected to, so every later
            // parser call (this context is cached per source) uses the live host
            // without re-resolving the redirect each time.
            if (typeof window.__domainOverrides === 'undefined') window.__domainOverrides = {};

            // Mirror parser-runtime.js handleProxyRedirect: if the request followed
            // a cross-host redirect, retarget the parser's domain so later relative
            // URLs resolve against the live host, and persist it for next time.
            function __retarget(parser, requestUrl) {
                if (!parser) return;
                try {
                    var fin = host.finalUrl();
                    if (!fin) return;
                    var fd = new URL(fin).hostname;
                    var od = new URL(requestUrl).hostname;
                    if (fd && od && fd !== od) {
                        parser.domain = fd;
                        if (parser.source && parser.source.id) window.__domainOverrides[parser.source.id] = fd;
                    }
                } catch (e) { /* ignore */ }
            }
            window.__context = {
                httpGet: function(url, parser) {
                    var domain = (parser && parser.domain) ? parser.domain : '';
                    var resp = host.http('GET', url, domain, null, null);
                    __retarget(parser, url);
                    return resp;
                },
                httpPost: function(url, body, extraHeaders, parser) {
                    var domain = (parser && parser.domain) ? parser.domain : '';
                    var resp = host.http('POST', url, domain, body || '', extraHeaders ? JSON.stringify(extraHeaders) : null);
                    __retarget(parser, url);
                    return resp;
                },
                parseHTML: function(html) {
                    return __wrapJsoup(host.parseHTML(html));
                },
                decodeContent: function(s) { return s; }
            };
        """.trimIndent()

        return Context.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .allowAllAccess(true)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { true }
            .build()
            .also { ctx ->
                ctx.getBindings("js").putMember("host", HostBridge())
                ctx.eval("js", polyfills + "\n" + prelude + "\n" + bundleText)
            }
    }

    // `map` converts the parser's GraalVM Value result into plain Kotlin model objects.
    // It MUST run on jsThread alongside the eval, because traversing the Value (getMember /
    // arraySize / asString …) re-enters the thread-confined Context; doing it on the calling
    // pool thread would re-trigger the very multi-thread access crash this change fixes.
    private fun <R> invokeParserAsync(method: String, args: Map<String, Any>, map: (Value) -> R): R {
        // Recursive conversion so nested args (e.g. the SEARCH filter map
        // {"query": ...}) survive as real JSON objects/arrays instead of being
        // flattened to a Kotlin map's toString() — which silently broke search.
        val jsonArgs = toJsonElement(args).toString()

        val jsCode = """
            (async function() {
                var args = JSON.parse(${jsonQuote(jsonArgs)});
                var p = NyoraParsers.getParser("${jsEngineId}", window.__context);
                if (!p) throw new Error("Parser not found: ${jsEngineId}");
                if (window.__domainOverrides["${jsEngineId}"]) p.domain = window.__domainOverrides["${jsEngineId}"];

                // Proactive redirection check on first page browse
                try {
                    if ("$method" === "list" && args.page === 1 && (!args.filter || !args.filter.query)) {
                        var testUrl = 'https://' + p.domain + '/';
                        var resp = host.http('GET', testUrl, p.domain, null, null);
                        var fin = host.finalUrl();
                        if (fin && fin.startsWith('http')) {
                            var fd = new URL(fin).hostname;
                            if (fd && fd !== p.domain) {
                                console.log('[Redir] Domain changed: ' + p.domain + ' -> ' + fd);
                                p.domain = fd;
                                window.__domainOverrides["${jsEngineId}"] = fd;
                            }
                        }
                    }
                } catch (e) { console.error('[Redir] check failed', e); }

                if ("$method" === "list") {
                    return await p.getListPage(args.page, args.order, args.filter || {});
                } else if ("$method" === "details") {
                    return await p.getDetails({ id: args.url, url: args.url, source: { id: "${jsEngineId}", name: "${jsEngineId}" } });
                } else if ("$method" === "pages") {
                    return await p.getPages({ id: args.url, url: args.url, branch: args.branch, source: { id: "${jsEngineId}" } });
                }
                throw new Error("Unknown method");
            })();
        """.trimIndent()
        
        // Run the WHOLE unit of Context work (lazy Context creation on first use, the
        // eval, and awaitPromise's pump loop) on the source's single owning thread, then
        // block the calling pool thread on the Future. This keeps the public suspend API
        // unchanged: the suspend methods still call invokeParserAsync synchronously from
        // inside their runBlocking context; only the eval dispatch moves off the calling
        // thread. The calling thread holds NO lock while it waits — it just blocks on
        // Future.get() — so there is no risk of holding a lock across a suspend point.
        //
        // Re-entrancy safety: this Callable must never call back into invokeParserAsync
        // (that would submit a new task to the same single thread and self-deadlock on
        // Future.get). The JS host bridge (HostBridge.http / parseHTML / finalUrl) only
        // performs OkHttp/Jsoup work and does not re-invoke the parser, so this is safe.
        return try {
            jsThread.submit(java.util.concurrent.Callable {
                map(awaitPromise(context.eval("js", jsCode)))
            }).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            // Unwrap so the parser's own error (incl. the "Cloudflare challenge: " marker
            // that clients key on, and the cleaned promise-rejection messages) reaches the
            // caller unchanged instead of being wrapped in an ExecutionException.
            throw e.cause ?: e
        }
    }

    private fun awaitPromise(value: Value): Value {
        if (!value.hasMember("then")) return value
        var resolved: Value? = null
        var rejected: String? = null
        value.invokeMember(
            "then",
            ProxyExecutable { args ->
                resolved = args.firstOrNull()
                null
            },
            ProxyExecutable { args ->
                // Strip the leading "java.lang.SomeException: " that GraalVM prepends
                // when a host exception crosses into JS, so the UI shows a clean message.
                rejected = args.firstOrNull()?.toString()
                    ?.replace(Regex("^(?:[\\w.\$]*\\.)?\\w*(?:Exception|Error):\\s*"), "")
                    ?.trim()
                    ?: "JavaScript promise rejected"
                null
            },
        )
        // host.http is synchronous, so parser promises settle within a few
        // microtask checkpoints. Each context.eval drains the JS job queue;
        // pump until settled (NOT a fixed sleep — the old `return@repeat` only
        // skipped one iteration and always slept ~2s). Cap as a hang guard.
        var spins = 0
        while (resolved == null && rejected == null && spins < 5000) {
            context.eval("js", "undefined")
            spins++
        }
        rejected?.let { error(it) }
        return resolved ?: error("JavaScript promise did not resolve")
    }

    private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is kotlinx.serialization.json.JsonElement -> value
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> kotlinx.serialization.json.JsonObject(
            value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
        )
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }

    private fun jsonQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Null-safe member reads: a member that exists but holds JS `undefined`
    // throws on .asString()/.asBoolean(); these return null/fallback instead.
    private fun Value.strOrNull(key: String): String? {
        val m = getMember(key) ?: return null
        return try {
            when {
                m.isNull -> null
                m.isString -> m.asString()
                m.isNumber -> if (m.fitsInLong()) m.asLong().toString() else m.asDouble().toString()
                m.isBoolean -> m.asBoolean().toString()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun Value.numOrNull(key: String): Double? {
        val m = getMember(key) ?: return null
        return try {
            when {
                m.isNull -> null
                m.isNumber -> m.asDouble()
                m.isString -> m.asString().toDoubleOrNull()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun Value.boolOrNull(key: String): Boolean? {
        val m = getMember(key) ?: return null
        return try { if (m.isBoolean) m.asBoolean() else null } catch (e: Exception) { null }
    }

    private fun mapMangaPage(value: Value): MangaSearchPage {
        // getListPage returns a plain array; older bundles wrapped it in {list:[...]}
        val list = when {
            value.hasArrayElements() -> value
            else -> value.getMember("entries") ?: value.getMember("list") ?: value
        }
        val entries = if (list.hasArrayElements()) {
            (0 until list.arraySize).mapNotNull { index -> mapManga(list.getArrayElement(index)) }
        } else emptyList()
        val hasNextPage = value.boolOrNull("hasNextPage") ?: entries.isNotEmpty()
        return MangaSearchPage(entries = entries, hasNextPage = hasNextPage)
    }

    private fun mapManga(value: Value): Manga? {
        if (value.isNull) return null
        val url = value.strOrNull("url") ?: value.strOrNull("link") ?: ""
        val cover = listOfNotNull(
            value.strOrNull("coverUrl"), value.strOrNull("largeCoverUrl"),
            value.strOrNull("cover"), value.strOrNull("imageUrl"),
        ).firstOrNull { it.isNotBlank() } ?: ""
        val authors = value.getMember("authors")?.let { a ->
            if (a.hasArrayElements()) (0 until a.arraySize).mapNotNull { i -> a.getArrayElement(i)?.let { if (it.isString) it.asString() else null } } else null
        }?.filter { it.isNotBlank() }
            ?: listOfNotNull(value.strOrNull("author")?.takeIf { it.isNotBlank() })
        return Manga(
            // Trust the bundle-stamped canonical id (parsers.bundle.js sets manga.id via
            // nyoraId) so it matches iOS/Android/Web; fall back to the local hash only if the
            // bundle didn't provide one.
            id = value.strOrNull("id")?.takeIf { it.isNotBlank() }
                ?: com.nyora.hasan72341.shared.util.generateNyoraId(sourceRefName, url).toString(),
            title = value.strOrNull("title") ?: value.strOrNull("name") ?: "",
            url = url,
            authors = authors,
            source = MangaSourceRef.Script(sourceRefName),
            coverUrl = cover,
            description = value.strOrNull("description") ?: "",
            tags = mapTags(value.getMember("tags")),
        )
    }

    /** Map a JS tags array (strings or {title,key}/{name} objects) to MangaTag. */
    private fun mapTags(value: Value?): List<com.nyora.hasan72341.shared.model.MangaTag> {
        if (value == null || value.isNull || !value.hasArrayElements()) return emptyList()
        return (0 until value.arraySize).mapNotNull { i ->
            val t = value.getArrayElement(i)
            when {
                t == null || t.isNull -> null
                t.isString -> t.asString().takeIf { it.isNotBlank() }?.let { com.nyora.hasan72341.shared.model.MangaTag(key = it, title = it) }
                t.hasMembers() -> {
                    val title = t.strOrNull("title") ?: t.strOrNull("name") ?: t.strOrNull("key")
                    title?.takeIf { it.isNotBlank() }?.let {
                        com.nyora.hasan72341.shared.model.MangaTag(key = t.strOrNull("key") ?: it, title = it)
                    }
                }
                else -> null
            }
        }
    }

    private fun mapChapters(value: Value): List<MangaChapter> {
        if (!value.hasArrayElements()) return emptyList()
        return (0 until value.arraySize).mapNotNull { index ->
            val chapter = value.getArrayElement(index)
            if (chapter.isNull) null else {
                val url = chapter.strOrNull("url") ?: ""
                MangaChapter(
                    id = chapter.strOrNull("id")?.takeIf { it.isNotBlank() }
                        ?: com.nyora.hasan72341.shared.util.generateNyoraId(sourceRefName, url).toString(),
                    title = chapter.strOrNull("title") ?: chapter.strOrNull("name") ?: "",
                    number = (chapter.numOrNull("number") ?: 0.0).toFloat(),
                    url = url,
                    index = index.toInt(),
                )
            }
        }
    }

    private fun mapPages(value: Value): List<MangaPage> {
        if (!value.hasArrayElements()) return emptyList()
        return (0 until value.arraySize).mapNotNull { index ->
            val page = value.getArrayElement(index)
            if (page.isNull) return@mapNotNull null
            when {
                page.isString -> MangaPage(page.asString())
                page.hasMembers() -> MangaPage(
                    url = page.strOrNull("url") ?: "",
                    headers = page.getMember("headers")?.let { headers ->
                        if (headers.hasMembers()) {
                            headers.memberKeys.associateWith { key -> headers.strOrNull(key) ?: "" }
                        } else emptyMap()
                    }.orEmpty(),
                )
                else -> null
            }
        }
    }

    inner class HostBridge {
        // Final URL of the most recent request (after redirects). The JS context
        // reads this to retarget parser.domain on a cross-host redirect — the same
        // behaviour as the web runtime's handleProxyRedirect (X-Final-URL).
        @Volatile
        var lastFinalUrl: String = ""

        fun log(msg: String) {
            println("[JS] ${source.name}: $msg")
        }

        /**
         * HTTP fetch that mirrors the web app's cloudflare CORS proxy request shape
         * (browser User-Agent + Accept/Referer headers), so manga sites return the
         * SAME HTML/JSON they serve the web app — many vary their markup by UA. The
         * only difference from web is that this goes DIRECT via OkHttp (the JVM has
         * no CORS), with no cloudflare proxy in between.
         */
        fun http(method: String, url: String, domain: String, body: String?, headersJson: String?): String {
            val origin = runCatching {
                val u = java.net.URI(url)
                if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else null
            }.getOrNull() ?: if (domain.isNotBlank()) "https://$domain" else ""

            val builder = okhttp3.Request.Builder()
                .url(url)
                .method(method, body?.takeIf { method.equals("POST", ignoreCase = true) }?.toRequestBody())
                // Match nyora-web/cloudflare/public/parsers/worker.js exactly.
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-cache")
            if (origin.isNotEmpty()) builder.header("Referer", "$origin/")
            if (method.equals("POST", ignoreCase = true)) {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                if (origin.isNotEmpty()) builder.header("Origin", origin)
                builder.header("X-Requested-With", "XMLHttpRequest")
            }
            // Forward parser-supplied headers (POST mainly), overriding defaults.
            if (!headersJson.isNullOrBlank() && headersJson != "{}") {
                runCatching {
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(headersJson)
                        as? kotlinx.serialization.json.JsonObject
                    if (obj != null) for ((k, v) in obj) {
                        val pv = (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
                        builder.header(k, pv)
                    }
                }
            }

            val client = buildOkHttpClient(networkConfig.snapshot())
            client.newCall(builder.build()).execute().use { response ->
                lastFinalUrl = response.request.url.toString()
                if (!response.isSuccessful) {
                    // Cloudflare JS-challenge ("Just a moment") can't be cleared with
                    // headers alone — it needs a real browser. Signal it distinctly so
                    // the app can solve it in a WebView and POST the cf_clearance back.
                    val cfMitigated = response.header("cf-mitigated") != null
                    val cfServer = response.code in intArrayOf(403, 503, 429) &&
                        (response.header("server")?.contains("cloudflare", ignoreCase = true) == true)
                    if (cfMitigated || cfServer) {
                        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: domain
                        error("$CLOUDFLARE_PREFIX$host")
                    }
                    error("HTTP ${response.code}")
                }
                return response.body?.string().orEmpty()
            }
        }

        fun finalUrl(): String = lastFinalUrl

        fun parseHTML(html: String): Any {
            return org.jsoup.Jsoup.parse(html)
        }
    }

    private companion object {
        // Same Chrome UA string the web proxy worker sends. Single source of truth in
        // HelperNetworkSettings so the fetch path, image/cover path, and the WebView
        // Cloudflare solver all present an identical UA (cf_clearance is UA-bound).
        val BROWSER_UA = com.nyora.hasan72341.shared.net.NYORA_BROWSER_UA
        // Marker the app keys on to trigger a WebView Cloudflare solve. Followed by the host.
        const val CLOUDFLARE_PREFIX = "Cloudflare challenge: "
    }
}
