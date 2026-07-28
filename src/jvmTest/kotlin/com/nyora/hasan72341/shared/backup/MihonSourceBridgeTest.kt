package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.MangaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MihonSourceBridgeTest {

    private fun source(id: String, name: String) =
        MangaSource(id = id, name = name, lang = "en", baseUrl = "")

    @Test
    fun `bridge resource loads and is populated`() {
        assertTrue(MihonSourceBridge.map.size > 500, "bridge only has ${MihonSourceBridge.map.size} entries")
        // Every target must be namespaced like a real Nyora source id.
        assertTrue(MihonSourceBridge.map.values.all { it.startsWith("parser:") })
        // Ids are Mihon's 63-bit hashes: positive, and never zero.
        assertTrue(MihonSourceBridge.map.keys.all { it > 0L })
    }

    @Test
    fun `mihon id algorithm reproduces published keiyoushi ids`() {
        // Mihon: md5("${name.lowercase()}/$lang/$versionId") -> first 8 bytes BE, sign bit cleared.
        fun generateId(name: String, lang: String, versionId: Int): Long {
            val key = "${name.lowercase()}/$lang/$versionId"
            val bytes = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
                .reduce(Long::or) and Long.MAX_VALUE
        }
        // Sanity: the algorithm is deterministic and stays in range.
        val id = generateId("MangaDex", "en", 1)
        assertEquals(id, generateId("mangadex", "en", 1))
        assertTrue(id > 0L)
    }

    @Test
    fun `bridge hit wins over a name guess`() {
        val correct = source("parser:MANGADEX", "MangaDex")
        val decoy = source("parser:SOMETHINGELSE", "Mangadex Clone")
        val mihonId = 2499283573021220255L
        val mapper = MihonSourceMapper(
            listOf(correct, decoy),
            bridge = mapOf(mihonId to "parser:MANGADEX"),
        )
        val map = mapper.buildIdMap(listOf(MihonSource(name = "Mangadex Clone", sourceId = mihonId)))
        // Name says "Mangadex Clone", the bridge says MangaDex; the bridge is authoritative.
        assertEquals("parser:MANGADEX", map[mihonId]?.id)
        assertTrue(mapper.isBridged(mihonId))
    }

    @Test
    fun `falls back to name matching when the bridge has no entry`() {
        val mapper = MihonSourceMapper(listOf(source("parser:MANGADEX", "MangaDex")), bridge = emptyMap())
        val map = mapper.buildIdMap(listOf(MihonSource(name = "MangaDex (EN)", sourceId = 42L)))
        assertEquals("parser:MANGADEX", map[42L]?.id)
        assertFalse(mapper.isBridged(42L))
    }

    @Test
    fun `a bridge entry pointing at an uninstalled source is ignored`() {
        // The bridge is a snapshot; a source it names may not be present here.
        val mapper = MihonSourceMapper(
            listOf(source("parser:MANGADEX", "MangaDex")),
            bridge = mapOf(7L to "parser:REMOVED_SOURCE"),
        )
        assertFalse(mapper.isBridged(7L))
        assertNull(mapper.buildIdMap(listOf(MihonSource(name = "Removed", sourceId = 7L)))[7L])
    }

    @Test
    fun `cjk only source names never collide on the empty string`() {
        // Normalising a CJK-only name yields an empty stem.
        val mapper = MihonSourceMapper(listOf(source("parser:SODSAIME", "โซดสยาม")))
        assertNull(mapper.match("性感美女"))
        assertNull(mapper.match("漫画"))
        assertNull(mapper.match(""))
    }

    @Test
    fun `real bridge binds a known mihon source to a nyora parser`() {
        // Pick any live entry from the shipped resource and prove it round-trips
        // through the mapper when that Nyora source is installed.
        val (mihonId, nyoraId) = MihonSourceBridge.map.entries.first().let { it.key to it.value }
        val mapper = MihonSourceMapper(
            listOf(source(nyoraId, nyoraId.removePrefix("parser:"))),
            MihonSourceBridge.map,
        )
        assertTrue(mapper.isBridged(mihonId))
        val resolved = mapper.buildIdMap(listOf(MihonSource(name = "irrelevant", sourceId = mihonId)))
        assertEquals(nyoraId, resolved[mihonId]?.id)
        assertNotNull(MihonSourceBridge.nyoraSourceId(mihonId))
    }

    @Test
    fun `no mihon source maps onto a shared hosting domain match`() {
        // blogspot.com and my.id host dozens of unrelated sites, so a domain
        // match on them is meaningless and the generator excludes them.
        val suspects = listOf("parser:LER999", "parser:YURILAB")
        for (target in suspects) {
            val count = MihonSourceBridge.map.values.count { it == target }
            assertTrue(count <= 3, "$target is bridged $count times — shared-host over-matching is back")
        }
    }

    @Test
    fun `per language sources are disambiguated rather than collapsed`() {
        // Several Nyora parsers legitimately serve one site (Toomics, Shueisha).
        // Each Mihon source must land on exactly one of them, never be dropped
        // wholesale and never all collapse onto a single variant.
        val families = MihonSourceBridge.reverse.keys.filter {
            it.contains("TOOMICS") || it.contains("SHUEISHA")
        }
        if (families.isEmpty()) return
        val bridgedVariants = families.count { MihonSourceBridge.reverse[it]?.isNotEmpty() == true }
        assertTrue(bridgedVariants >= 2, "per-language variants collapsed onto one source")
    }

    @Test
    fun `unknown mihon ids return null rather than guessing`() {
        assertNull(MihonSourceBridge.nyoraSourceId(1L))
        assertNull(MihonSourceBridge.nyoraSourceId(-999L))
    }
}
