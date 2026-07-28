package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.MangaSourceRef

/**
 * Source identifiers live in two namespaces that must not be mixed.
 *
 * [MangaSourceRef.name] holds the form `MangaSourceRefCodec` parses (`MIHON_<id>`);
 * `history.source_id` and `tracking.source_id` hold a colon-namespaced id
 * (`parser:X`, `script:X`, `mihon:X`). `NyoraSync.fromWireSourceId` re-adds a
 * `parser:` prefix to any id without a colon, so a ref name stored as a row id
 * returns from the cloud as `parser:MIHON_<id>`.
 */
object MihonSourceIds {

    fun sourceId(mihonSourceId: Long): String = "mihon:$mihonSourceId"

    fun sourceRef(mihonSourceId: Long): MangaSourceRef.Mihon =
        MangaSourceRef.Mihon("MIHON_$mihonSourceId", mihonSourceId)

    /** Row identifier for [ref], namespaced so it survives the sync wire format. */
    fun sourceIdFor(ref: MangaSourceRef): String = when (ref) {
        is MangaSourceRef.Mihon -> sourceId(ref.sourceId)
        is MangaSourceRef.Parser -> ref.name.withPrefix("parser:")
        is MangaSourceRef.Script -> ref.name.withPrefix("script:")
        else -> ref.name
    }

    private fun String.withPrefix(prefix: String) = if (contains(':')) this else prefix + this
}
