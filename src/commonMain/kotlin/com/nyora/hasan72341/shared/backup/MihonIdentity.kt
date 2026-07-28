package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.model.MangaSourceRefCodec

data class RestoredIdentity(
    val mangaId: String,
    val sourceRef: MangaSourceRef,
    /** Row identifier, namespaced for the sync wire format. */
    val rowSourceId: String,
    /** Entries Nyora itself exported; everything else needs source resolution. */
    val isNyoraOrigin: Boolean,
)

object MihonIdentity {

    /**
     * Determines whether a restored entry maps onto a Nyora source or becomes a
     * placeholder awaiting resolution.
     *
     * Nyora stamps its own id and source ref into the `memo` bag on export, and
     * that memo takes precedence over the envelope's Mihon source id: the exporter
     * translates Nyora sources to their Mihon equivalents, so the envelope may name
     * a Mihon extension for an entry that is natively a Nyora source. Trusting the
     * envelope would turn every entry of a Nyora backup into a placeholder.
     *
     * @param syntheticId identifier for entries with no Nyora memo, derived by the
     *   caller from the Mihon `(source, url)` pair.
     */
    fun resolve(mihonSourceId: Long, memo: NyoraMangaMemo, syntheticId: String): RestoredIdentity {
        val ref = memo.sourceRef
            .takeIf { it.isNotEmpty() && memo.nyoraId.isNotEmpty() }
            ?.let(MangaSourceRefCodec::decodeName)
            ?.takeUnless { it is MangaSourceRef.Mihon || it is MangaSourceRef.Unknown }

        return if (ref != null) {
            RestoredIdentity(memo.nyoraId, ref, MihonSourceIds.sourceIdFor(ref), isNyoraOrigin = true)
        } else {
            val placeholder = MihonSourceIds.sourceRef(mihonSourceId)
            RestoredIdentity(
                mangaId = memo.nyoraId.ifEmpty { syntheticId },
                sourceRef = placeholder,
                rowSourceId = MihonSourceIds.sourceIdFor(placeholder),
                isNyoraOrigin = false,
            )
        }
    }
}
