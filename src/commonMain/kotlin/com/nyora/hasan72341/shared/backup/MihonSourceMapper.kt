package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.MangaSource

/**
 * Binds the sources declared in a Mihon backup to Nyora's own parsers.
 *
 * A Mihon `sourceId` is a hash of the extension's name, language and version, so
 * it carries no information about the site. Two things recover it:
 *
 *  1. [bridge] — a prebuilt id map generated offline by joining the keiyoushi
 *     extension index against Nyora's catalogue on registrable domain. Preferred,
 *     because it binds on the website rather than a display string.
 *  2. The source name Mihon writes into the backup, matched leniently.
 *
 * A miss is not fatal: unmapped entries still import and keep their title, cover
 * and reading progress.
 */
class MihonSourceMapper(
    nyoraSources: List<MangaSource>,
    private val bridge: Map<Long, String> = emptyMap(),
) {

    private val byId = nyoraSources.associateBy { it.id }

    private val byName = nyoraSources
        .associateBy { normalise(it.name) }
        .filterKeys { it.length >= MIN_NAME_LENGTH }

    /** Longest first, so "asurascans" wins over "asura" on a containment match. */
    private val namesByLength = byName.entries.sortedByDescending { it.key.length }

    fun buildIdMap(backupSources: List<MihonSource>): Map<Long, MangaSource> =
        backupSources.mapNotNull { source ->
            val match = bridged(source.sourceId) ?: match(source.name)
            match?.let { source.sourceId to it }
        }.toMap()

    /** True when [mihonSourceId] resolved through the bridge rather than by name. */
    fun isBridged(mihonSourceId: Long): Boolean = bridged(mihonSourceId) != null

    private fun bridged(mihonSourceId: Long): MangaSource? = bridge[mihonSourceId]?.let(byId::get)

    fun match(mihonName: String): MangaSource? {
        val key = normalise(mihonName)
        // Names that normalise to nothing (CJK-only titles) would all collide.
        if (key.length < MIN_NAME_LENGTH) return null
        return byName[key]
            ?: namesByLength.firstOrNull { (name, _) -> key.contains(name) || name.contains(key) }?.value
    }

    private fun normalise(raw: String): String {
        val stripped = NON_ALNUM.replace(LANG_SUFFIX.replace(raw.lowercase().trim(), ""), "")
        return NOISE_SUFFIXES.fold(stripped) { name, noise ->
            if (name.length > noise.length + MIN_NAME_LENGTH) name.removeSuffix(noise) else name
        }
    }

    private companion object {
        /** Shorter stems ("to", "manga") make containment matching untrustworthy. */
        const val MIN_NAME_LENGTH = 4

        /** Mihon appends the language: "MangaDex (EN)", "Manganato (all)". */
        val LANG_SUFFIX = Regex("""\s*\((?:[a-z]{2,3}|all|multi)\)\s*$""")
        val NON_ALNUM = Regex("""[^a-z0-9]""")
        val NOISE_SUFFIXES = listOf("scans", "scanlation", "manga", "online", "net", "com", "org")
    }
}
