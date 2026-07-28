package com.nyora.hasan72341.shared.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bidirectional bridge between Mihon extension sources and Nyora sources.
 *
 * Mihon derives a source id as `md5("${name.lowercase()}/$lang/$versionId")`
 * truncated to eight big-endian bytes, so the id is stable across installs but
 * tells us nothing about the site. The bridge resolves that offline: it is
 * generated from the keiyoushi extension index (which publishes each source's id
 * AND baseUrl) joined against Nyora's own parser catalogue, matched on
 * registrable domain first and source name second.
 *
 * Both directions matter:
 *  - [nyoraSourceId] — restoring a Mihon backup INTO Nyora.
 *  - [mihonSourceFor] — exporting a Nyora backup so Mihon lands on the
 *    equivalent extension instead of an unknown source.
 *
 * Regenerate with `tools/build-source-bridge.py` when either catalogue moves;
 * the mapping is a point-in-time snapshot, not a live lookup.
 */
object MihonSourceBridge {

    private const val RESOURCE = "/mihon-source-bridge.json"

    @Serializable
    data class MihonCandidate(
        val id: String = "",
        val lang: String = "",
        val name: String = "",
    ) {
        val sourceId: Long get() = id.toLongOrNull() ?: 0L
    }

    @Serializable
    private data class BridgeFile(
        val version: Int = 0,
        val toNyora: Map<String, String> = emptyMap(),
        val toMihon: Map<String, List<MihonCandidate>> = emptyMap(),
    )

    private val data: BridgeFile by lazy { load() }

    /** Mihon source id -> Nyora source id (already carries the `parser:` prefix). */
    val map: Map<Long, String> by lazy {
        data.toNyora.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()
    }

    /** Nyora source id -> the Mihon extensions that scrape the same site. */
    val reverse: Map<String, List<MihonCandidate>> by lazy { data.toMihon }

    fun nyoraSourceId(mihonSourceId: Long): String? = map[mihonSourceId]


    /**
     * Best Mihon equivalent for a Nyora source, preferring an extension in the
     * same language so a French Nyora source does not export as the English one.
     * Candidates are pre-sorted (en, then all/multi, then by id), so the first
     * entry is a sensible default when no language matches.
     */
    fun mihonSourceFor(nyoraSourceId: String, lang: String = ""): MihonCandidate? {
        val candidates = reverse[nyoraSourceId] ?: return null
        if (candidates.isEmpty()) return null
        val wanted = lang.lowercase()
        if (wanted.isNotEmpty()) {
            candidates.firstOrNull { it.lang.equals(wanted, ignoreCase = true) }?.let { return it }
            if (wanted != "all" && wanted != "multi") {
                candidates.firstOrNull { it.lang.equals("all", ignoreCase = true) }?.let { return it }
            }
        }
        return candidates.first()
    }

    private fun load(): BridgeFile {
        val text = MihonSourceBridge::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: return BridgeFile()
        return runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(BridgeFile.serializer(), text)
        }.getOrDefault(BridgeFile())
    }
}
