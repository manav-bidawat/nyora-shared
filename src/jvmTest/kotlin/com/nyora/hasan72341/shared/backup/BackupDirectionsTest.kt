package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.MangaSourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two directions a backup can travel, and the guarantee each one carries:
 *
 *  - Nyora -> Nyora  : restores onto Nyora sources ONLY, never a placeholder.
 *  - Nyora -> Mihon  : Nyora sources are translated to their Mihon equivalents.
 */
class BackupDirectionsTest {

    private fun syntheticId(m: MihonManga) = "mihon_synthetic_${m.source}"

    // ── Nyora -> Nyora ──────────────────────────────────────────────────────

    @Test
    fun `a nyora backup restores onto nyora sources only`() {
        // Exactly what the exporter writes for a native entry: the envelope now
        // carries the TRANSLATED Mihon source id, while the memo holds the truth.
        val memo = NyoraMangaMemo(nyoraId = "mangadex:abc-123", sourceRef = "MANGADEX")
        val identity = MihonIdentity.resolve(
            mihonSourceId = 2499283573021220255L, // a real Mihon MangaDex id
            memo = memo,
            syntheticId = "should-not-be-used",
        )

        assertTrue(identity.isNyoraOrigin)
        assertFalse(identity.isNyoraOrigin.not())
        assertEquals("mangadex:abc-123", identity.mangaId)
        assertTrue(identity.sourceRef is MangaSourceRef.Parser)
        assertEquals("MANGADEX", identity.sourceRef.name)
        assertEquals("parser:MANGADEX", identity.rowSourceId)
    }

    @Test
    fun `nyora origin entries are never queued for resolution`() {
        val refs = listOf("MANGADEX", "JS_SOMETHING", "LOCAL")
        for (ref in refs) {
            val identity = MihonIdentity.resolve(
                mihonSourceId = 999L,
                memo = NyoraMangaMemo(nyoraId = "id-$ref", sourceRef = ref),
                syntheticId = "synthetic",
            )
            assertFalse(identity.isNyoraOrigin.not(), "$ref should not need resolution")
            assertFalse(
                identity.sourceRef is MangaSourceRef.Mihon,
                "$ref must not degrade to a Mihon placeholder",
            )
        }
    }

    @Test
    fun `every nyora source ref survives a full export import cycle`() {
        // Simulates the exporter writing the ref into the memo and the importer
        // reading it back, for each ref kind Nyora can produce.
        val cases = mapOf(
            "MANGADEX" to "parser:MANGADEX",
            "JS_FOO" to "script:JS_FOO",
            "LOCAL" to "LOCAL",
        )
        for ((refName, expectedRowId) in cases) {
            val exported = NyoraMangaMemo(nyoraId = "x", sourceRef = refName)
            val encoded = MemoCodec.encodeManga(exported)
            val backup = MihonBackup(
                backupManga = listOf(MihonManga(source = 1L, url = "/u", title = "T", memo = encoded)),
            )
            val decoded = MihonBackupCodec.decode(MihonBackupCodec.encode(backup))
            val memo = MemoCodec.decodeManga(decoded.backupManga.first().memo)
            val identity = MihonIdentity.resolve(1L, memo, "synthetic")
            assertEquals(refName, identity.sourceRef.name, "ref $refName changed across the cycle")
            assertEquals(expectedRowId, identity.rowSourceId)
        }
    }

    @Test
    fun `a genuine mihon backup does become a placeholder`() {
        // No Nyora memo -> unresolved, queued, and namespaced sync-safely.
        val identity = MihonIdentity.resolve(555L, NyoraMangaMemo(), "mihon_abc")
        assertFalse(identity.isNyoraOrigin)
        assertTrue(identity.isNyoraOrigin.not())
        assertEquals("mihon_abc", identity.mangaId)
        assertTrue(identity.sourceRef is MangaSourceRef.Mihon)
        assertEquals(555L, (identity.sourceRef as MangaSourceRef.Mihon).sourceId)
        assertEquals("mihon:555", identity.rowSourceId)
    }

    @Test
    fun `a memo naming a mihon ref is still treated as unresolved`() {
        // Round-tripping an unresolved entry through Nyora must not fake resolution.
        val memo = NyoraMangaMemo(nyoraId = "mihon_abc", sourceRef = "MIHON_555")
        val identity = MihonIdentity.resolve(555L, memo, "fallback")
        assertTrue(identity.isNyoraOrigin.not())
        assertEquals("mihon_abc", identity.mangaId, "the existing id must be kept, not regenerated")
        assertEquals("mihon:555", identity.rowSourceId)
    }

    @Test
    fun `only unresolved entries belong in the missing source bucket`() {
        // Only entries that failed to bind belong in the bucket; natively
        // resolved ones must stay out of it.
        val nyoraOrigin = MihonIdentity.resolve(
            mihonSourceId = 2499283573021220255L,
            memo = NyoraMangaMemo(nyoraId = "mangadex:abc", sourceRef = "MANGADEX"),
            syntheticId = "synthetic",
        )
        val fromMihon = MihonIdentity.resolve(555L, NyoraMangaMemo(), "mihon_abc")

        assertFalse(nyoraOrigin.isNyoraOrigin.not(), "a native entry must not be filed as missing")
        assertTrue(fromMihon.isNyoraOrigin.not(), "an unbound Mihon entry must be filed as missing")
        assertEquals("Missing Source", MihonBackupImporter.MISSING_SOURCE_CATEGORY)
    }

    // ── Nyora -> Mihon ──────────────────────────────────────────────────────

    @Test
    fun `nyora sources translate to real mihon extension ids`() {
        // Every bridged Nyora source must yield a usable Mihon candidate, so a
        // Mihon user restoring a Nyora backup gets a source they can install.
        var checked = 0
        for ((nyoraId, candidates) in MihonSourceBridge.reverse) {
            val picked = MihonSourceBridge.mihonSourceFor(nyoraId)
            assertNotNull(picked, "no Mihon equivalent picked for $nyoraId")
            assertTrue(picked.sourceId > 0L, "candidate for $nyoraId has a bogus id")
            assertTrue(picked.name.isNotEmpty(), "candidate for $nyoraId has no name")
            assertTrue(candidates.contains(picked))
            // The translation must be consistent with the forward direction.
            assertEquals(nyoraId, MihonSourceBridge.nyoraSourceId(picked.sourceId))
            checked++
        }
        assertTrue(checked > 300, "only $checked Nyora sources have a Mihon equivalent")
    }

    @Test
    fun `translation prefers a language match`() {
        val entry = MihonSourceBridge.reverse.entries.firstOrNull { (_, c) ->
            c.map { it.lang }.distinct().size > 1
        }
        if (entry == null) return // no multi-language source in the snapshot
        val (nyoraId, candidates) = entry
        for (lang in candidates.map { it.lang }.filter { it.isNotEmpty() }.distinct()) {
            val picked = MihonSourceBridge.mihonSourceFor(nyoraId, lang)
            assertEquals(lang, picked?.lang, "asked for $lang on $nyoraId, got ${picked?.lang}")
        }
    }

    @Test
    fun `translation is deterministic`() {
        for ((nyoraId, _) in MihonSourceBridge.reverse.entries.take(50)) {
            assertEquals(
                MihonSourceBridge.mihonSourceFor(nyoraId, "en")?.id,
                MihonSourceBridge.mihonSourceFor(nyoraId, "en")?.id,
            )
        }
    }

    @Test
    fun `an unbridged nyora source has no mihon equivalent`() {
        assertNull(MihonSourceBridge.mihonSourceFor("parser:DOES_NOT_EXIST"))
        assertNull(MihonSourceBridge.mihonSourceFor(""))
    }

    @Test
    fun `bridge exposes both directions consistently`() {
        assertTrue(MihonSourceBridge.map.size > 500)
        assertTrue(MihonSourceBridge.reverse.size > 300)
        // Every reverse target must be a key of the forward map.
        for ((nyoraId, candidates) in MihonSourceBridge.reverse) {
            for (c in candidates) {
                assertEquals(nyoraId, MihonSourceBridge.map[c.sourceId])
            }
        }
    }
}
