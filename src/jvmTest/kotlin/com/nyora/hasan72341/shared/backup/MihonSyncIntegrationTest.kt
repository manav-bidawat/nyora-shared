package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.model.MangaSourceRefCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the seams where the backup layer meets nyora-sync.
 *
 * The sync wire format normalises source ids: `toWireSourceId()` strips a
 * `parser:` prefix on push, and `fromWireSourceId()` re-adds one to any id that
 * contains no colon on pull. Anything we store in a `source_id` column therefore
 * has to survive that round trip unchanged.
 */
class MihonSyncIntegrationTest {

    // Mirrors NyoraSync's private helpers so the contract is asserted, not assumed.
    private fun toWire(id: String) = id.removePrefix("parser:")
    private fun fromWire(id: String) = if (id.isBlank() || id.contains(':')) id else "parser:$id"
    private fun roundTrip(id: String) = fromWire(toWire(id))

    @Test
    fun `mihon source ids survive the sync round trip`() {
        val id = MihonSourceIds.sourceId(123L)
        assertEquals("mihon:123", id)
        assertEquals(id, roundTrip(id))
    }

    @Test
    fun `a ref name stored as a row id is rewritten by the pull path`() {
        assertEquals("parser:MIHON_123", roundTrip("MIHON_123"))
    }

    @Test
    fun `parser and script ids survive the round trip`() {
        assertEquals("parser:MANGADEX", roundTrip("parser:MANGADEX"))
        assertEquals("script:FOO", roundTrip("script:FOO"))
    }

    @Test
    fun `source ids derived from refs are always colon namespaced`() {
        assertEquals("mihon:99", MihonSourceIds.sourceIdFor(MihonSourceIds.sourceRef(99L)))
        assertEquals("parser:MANGADEX", MihonSourceIds.sourceIdFor(MangaSourceRef.Parser("MANGADEX")))
        // Already-prefixed refs must not be double-prefixed.
        assertEquals("parser:MANGADEX", MihonSourceIds.sourceIdFor(MangaSourceRef.Parser("parser:MANGADEX")))
        assertEquals("script:JS_X", MihonSourceIds.sourceIdFor(MangaSourceRef.Script("JS_X")))

        for (ref in listOf(
            MihonSourceIds.sourceRef(1L),
            MangaSourceRef.Parser("MANGADEX"),
            MangaSourceRef.Script("JS_X"),
        )) {
            val id = MihonSourceIds.sourceIdFor(ref)
            assertEquals(id, roundTrip(id), "source id $id did not survive sync normalisation")
        }
    }

    @Test
    fun `the source ref name still decodes to a mihon ref`() {
        // The ref name keeps the MIHON_ form the codec expects; only the row id
        // uses the colon form.
        val ref = MihonSourceIds.sourceRef(456L)
        assertEquals("MIHON_456", ref.name)

        val decoded = MangaSourceRefCodec.decodeName(ref.name)
        assertTrue(decoded is MangaSourceRef.Mihon)
        assertEquals(456L, decoded.sourceId)
        assertEquals("mihon:456", MihonSourceIds.sourceIdFor(decoded))
    }

    @Test
    fun `nyora origin backups round trip their source ref through the memo`() {
        // A Nyora-written backup carries the real ref, so re-importing it must not
        // downgrade the entry to an unresolved Mihon placeholder.
        val memo = NyoraMangaMemo(nyoraId = "abc", sourceRef = "MANGADEX")
        val decoded = MangaSourceRefCodec.decodeName(memo.sourceRef)
        assertTrue(decoded is MangaSourceRef.Parser)
        assertEquals("parser:MANGADEX", MihonSourceIds.sourceIdFor(decoded))
    }
}
