package com.nyora.hasan72341.shared.extension

import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.model.SourceEngine
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import java.util.concurrent.ConcurrentHashMap

class JvmExtensionRuntime(
    private val networkConfig: HelperNetworkConfig = HelperNetworkConfig(),
) : MangaExtensionRuntime {

    private val delegate = CommonMangaExtensionRuntime(
        jsFactory = { source -> JavaScriptExtensionService(source, networkConfig = networkConfig) },
        mihonFactory = { source ->
            UnsupportedExtensionService(
                source = source,
                reason = "Mihon APK sources require a Dalvik-compatible runtime. Not supported on desktop JVM.",
            )
        },
        parserFactory = { source ->
            // Route all legacy "Parser" engine requests to the new JS engine
            JavaScriptExtensionService(source, networkConfig = networkConfig)
        },
    )

    // Cache JS-backed services per source. Each one lazily builds a GraalVM
    // context that evaluates the ~400KB parser bundle, so rebuilding it on every
    // REST request (browse/details/pages are separate calls) would be very slow.
    // Caching also lets redirect-resolved domains (window.__domainOverrides)
    // persist across calls — a source that has moved is resolved once per session.
    private val jsCache = ConcurrentHashMap<String, MangaExtensionService>()

    override fun create(source: MangaSource): MangaExtensionService =
        when (source.engine) {
            SourceEngine.JavaScript, SourceEngine.Parser ->
                jsCache.getOrPut(source.id) { delegate.create(source) }
            else -> delegate.create(source)
        }
}
