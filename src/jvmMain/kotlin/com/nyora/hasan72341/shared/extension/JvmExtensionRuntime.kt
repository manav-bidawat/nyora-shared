package com.nyora.hasan72341.shared.extension

import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.model.SourceEngine
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import java.util.concurrent.ConcurrentHashMap

class JvmExtensionRuntime(
    private val networkConfig: HelperNetworkConfig = HelperNetworkConfig(),
) : MangaExtensionRuntime {

    private val nativeFactory: (MangaSource) -> MangaExtensionService =
        { source ->
            // MangaFire relaunched on a new JSON API (/api/titles); the bundled
            // kotatsu-parsers-redo MangaFire (old /filter HTML + vrf + scrambling)
            // now returns 0 results, so serve it from a native app-layer service.
            // Every MANGAFIRE_* language source (parser:MANGAFIRE_EN/ES/…) routes here.
            when {
                source.id.startsWith("parser:MANGAFIRE") -> MangaFireExtensionService(source, networkConfig)
                // Toonily.me → toondex.io: rebuilt on the MangaBuddy JSON API (api.toondex.io);
                // the kotatsu Madtheme parser no longer matches. Serve natively.
                source.id == "parser:TOONILY_ME" -> ToonDexExtensionService(source, networkConfig)
                else -> KotatsuParserExtensionService(source, networkConfig = networkConfig)
            }
        }

    private val delegate = CommonMangaExtensionRuntime(
        // Native kotatsu-parsers-redo engine. Both the canonical "Parser" engine and any
        // legacy "JavaScript"-tagged rows (from before the native migration) route here —
        // seedBuiltInSources re-stamps them to Parser on launch.
        jsFactory = nativeFactory,
        mihonFactory = { source ->
            UnsupportedExtensionService(
                source = source,
                reason = "Mihon APK sources require a Dalvik-compatible runtime. Not supported on desktop JVM.",
            )
        },
        parserFactory = nativeFactory,
    )

    // Cache parser-backed services per source. Each one instantiates a native parser
    // (and its OkHttp/loader context), so rebuilding it on every REST request
    // (browse/details/pages are separate calls) would be wasteful. Caching also lets
    // any per-parser state (resolved/overridden domain) persist across calls.
    private val serviceCache = ConcurrentHashMap<String, MangaExtensionService>()

    override fun create(source: MangaSource): MangaExtensionService =
        when (source.engine) {
            SourceEngine.JavaScript, SourceEngine.Parser ->
                serviceCache.getOrPut(source.id) { delegate.create(source) }
            else -> delegate.create(source)
        }
}
