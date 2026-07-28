package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.MangaSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MihonBackupTest {

    private fun sampleBackup() = MihonBackup(
        backupManga = listOf(
            MihonManga(
                source = 2499283573021220255L,
                url = "/manga/berserk",
                title = "Berserk",
                author = "Kentaro Miura",
                genre = listOf("Action", "Dark Fantasy"),
                status = 6,
                thumbnailUrl = "https://example.invalid/berserk.jpg",
                favorite = true,
                categories = listOf(0L, 1L),
                chapters = listOf(
                    MihonChapter(
                        url = "/manga/berserk/1",
                        name = "Chapter 1",
                        chapterNumber = 1f,
                        read = true,
                        lastPageRead = 12,
                        sourceOrder = 0,
                    ),
                ),
                history = listOf(MihonHistory(url = "/manga/berserk/1", lastRead = 1_700_000_000_000L)),
                tracking = listOf(
                    MihonTracking(syncId = 2, mediaId = 30002, title = "Berserk", status = 1, score = 9.5f),
                ),
            ),
        ),
        backupCategories = listOf(
            MihonCategory(name = "Reading", order = 0, id = 1),
            MihonCategory(name = "Read later", order = 1, id = 2),
        ),
        backupSources = listOf(MihonSource(name = "MangaDex", sourceId = 2499283573021220255L)),
    )

    @Test
    fun `round trips through gzipped protobuf`() {
        val original = sampleBackup()
        val bytes = MihonBackupCodec.encode(original)

        // Mihon identifies its backups by the gzip magic bytes.
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)

        val decoded = MihonBackupCodec.decode(bytes)
        assertEquals(1, decoded.backupManga.size)
        val manga = decoded.backupManga.first()
        assertEquals("Berserk", manga.title)
        assertEquals("/manga/berserk", manga.url)
        assertEquals(2499283573021220255L, manga.source)
        assertEquals(listOf("Action", "Dark Fantasy"), manga.genre)
        assertEquals(listOf(0L, 1L), manga.categories)
        assertEquals(1, manga.chapters.size)
        assertEquals(12L, manga.chapters.first().lastPageRead)
        assertTrue(manga.chapters.first().read)
        assertEquals(1_700_000_000_000L, manga.history.first().lastRead)
        assertEquals(2, manga.tracking.first().syncId)
        assertEquals(30002L, manga.tracking.first().remoteId)
        assertEquals(2, decoded.backupCategories.size)
        assertEquals("MangaDex", decoded.backupSources.first().name)
    }

    @Test
    fun `accepts bare ungzipped protobuf like mihon does`() {
        val gzipped = MihonBackupCodec.encode(sampleBackup())
        val raw = java.util.zip.GZIPInputStream(gzipped.inputStream()).use { it.readBytes() }
        val decoded = MihonBackupCodec.decode(raw)
        assertEquals("Berserk", decoded.backupManga.first().title)
    }

    @Test
    fun `rejects json backups with a clear message`() {
        val json = """{"favourites":[]}""".encodeToByteArray()
        val error = assertFailsWith<MihonBackupCodec.InvalidBackupException> {
            MihonBackupCodec.decode(json)
        }
        assertTrue(error.message!!.contains("JSON backup"), "got: ${error.message}")
    }

    @Test
    fun `rejects corrupt and truncated input`() {
        assertFailsWith<MihonBackupCodec.InvalidBackupException> { MihonBackupCodec.decode(byteArrayOf(0x1f)) }
        // gzip magic followed by garbage
        assertFailsWith<MihonBackupCodec.InvalidBackupException> {
            MihonBackupCodec.decode(byteArrayOf(0x1f, 0x8b.toByte(), 0, 1, 2, 3, 4, 5))
        }
    }

    @Test
    fun `unknown fields from a newer mihon survive decoding`() {
        // A field Nyora does not model must not break the restore. Tag 999 is
        // encoded manually and should simply be ignored.
        val bytes = MihonBackupCodec.encode(sampleBackup())
        val decoded = MihonBackupCodec.decode(bytes)
        assertEquals(sampleBackup().backupManga.first().title, decoded.backupManga.first().title)
    }

    @Test
    fun `nyora memo bag round trips inside the mihon container`() {
        val memo = NyoraMangaMemo(
            nyoraId = "mangadex:abc-123",
            sourceRef = "MANGADEX",
            altTitles = listOf("ベルセルク"),
            contentRating = "SUGGESTIVE",
            rating = 9.4f,
            isNsfw = false,
            readerMode = "vertical",
        )
        val manga = MihonManga(source = 1L, url = "/x", title = "X", memo = MemoCodec.encodeManga(memo))
        val decoded = MihonBackupCodec.decode(MihonBackupCodec.encode(MihonBackup(backupManga = listOf(manga))))
        val out = MemoCodec.decodeManga(decoded.backupManga.first().memo)
        assertEquals(memo, out)
    }

    @Test
    fun `a foreign memo bag decodes to defaults instead of throwing`() {
        // Stock Mihon writes its own memo contents; we must not choke on them.
        val foreign = """{"someMihonKey":123,"nested":{"a":[1,2]}}""".encodeToByteArray()
        assertEquals(NyoraMangaMemo(), MemoCodec.decodeManga(foreign))
        assertEquals(NyoraChapterMemo(), MemoCodec.decodeChapter("not json at all".encodeToByteArray()))
        assertEquals(NyoraMangaMemo(), MemoCodec.decodeManga(null))
    }

    @Test
    fun `refuses to write an empty backup`() {
        assertFailsWith<MihonBackupCodec.InvalidBackupException> {
            MihonBackupCodec.encode(MihonBackup())
        }
    }

    // --- source mapping -----------------------------------------------------

    private fun source(id: String, name: String) =
        MangaSource(id = id, name = name, lang = "en", baseUrl = "https://$id.invalid")

    @Test
    fun `maps mihon source names onto nyora parsers`() {
        val mapper = MihonSourceMapper(
            listOf(
                source("MANGADEX", "MangaDex"),
                source("ASURASCANS", "Asura Scans"),
                source("WEEBCENTRAL", "Weeb Central"),
            ),
        )
        assertEquals("MANGADEX", mapper.match("MangaDex")?.id)
        // Mihon appends the language to the display name.
        assertEquals("MANGADEX", mapper.match("MangaDex (EN)")?.id)
        assertEquals("ASURASCANS", mapper.match("AsuraScans")?.id)
        assertEquals("WEEBCENTRAL", mapper.match("Weeb-Central")?.id)
        // No Nyora parser for this site: a miss, not a crash.
        assertNull(mapper.match("Some Dead Aggregator"))
        assertNull(mapper.match(""))
    }

    @Test
    fun `builds a source id map from the backup declaration`() {
        val mapper = MihonSourceMapper(listOf(source("MANGADEX", "MangaDex")))
        val map = mapper.buildIdMap(
            listOf(
                MihonSource(name = "MangaDex", sourceId = 111L),
                MihonSource(name = "Nonexistent Source", sourceId = 222L),
            ),
        )
        assertEquals(1, map.size)
        assertEquals("MANGADEX", map[111L]?.id)
        assertNull(map[222L])
    }

    // --- tracker mapping ----------------------------------------------------

    @Test
    fun `tracker ids map both ways`() {
        assertEquals(2, TrackerCodec.toMihonTrackerId("anilist"))
        assertEquals(1, TrackerCodec.toMihonTrackerId("myanimelist"))
        assertEquals(11, TrackerCodec.toMihonTrackerId("mangabaka"))
        assertNull(TrackerCodec.toMihonTrackerId("kitsu"))
        assertEquals("anilist", TrackerCodec.toNyoraTrackerId(2))
        assertNull(TrackerCodec.toNyoraTrackerId(4)) // Shikimori: unsupported in Nyora
    }

    @Test
    fun `myanimelist status codes differ from anilist and are mapped separately`() {
        // MAL: plan-to-read is 6, rereading 7. AniList and MangaBaka: 5 and 6.
        assertEquals("planning", TrackerCodec.toNyoraStatus(TrackerCodec.MYANIMELIST, 6))
        assertEquals("rereading", TrackerCodec.toNyoraStatus(TrackerCodec.MYANIMELIST, 7))
        assertEquals("planning", TrackerCodec.toNyoraStatus(TrackerCodec.ANILIST, 5))
        assertEquals("rereading", TrackerCodec.toNyoraStatus(TrackerCodec.ANILIST, 6))
        // Round trip must preserve the per-tracker numbering.
        assertEquals(6, TrackerCodec.toMihonStatus(TrackerCodec.MYANIMELIST, "planning"))
        assertEquals(5, TrackerCodec.toMihonStatus(TrackerCodec.ANILIST, "planning"))
        assertEquals(1, TrackerCodec.toMihonStatus(TrackerCodec.ANILIST, "reading"))
    }

    @Test
    fun `legacy 1x tracking rows using mediaIdInt still resolve`() {
        assertEquals(4242L, MihonTracking(syncId = 2, mediaIdInt = 4242).remoteId)
        assertEquals(99L, MihonTracking(syncId = 2, mediaId = 99).remoteId)
    }

    // --- status mapping -----------------------------------------------------

    @Test
    fun `manga status maps to nyora state`() {
        assertEquals(com.nyora.hasan72341.shared.model.MangaState.ONGOING, StatusCodec.toNyora(1))
        assertEquals(com.nyora.hasan72341.shared.model.MangaState.FINISHED, StatusCodec.toNyora(2))
        assertEquals(com.nyora.hasan72341.shared.model.MangaState.PAUSED, StatusCodec.toNyora(6))
        assertNull(StatusCodec.toNyora(0))
        assertEquals(1, StatusCodec.fromNyora(com.nyora.hasan72341.shared.model.MangaState.ONGOING))
    }

    @Test
    fun `chapter memo carries page bookmarks mihon cannot express`() {
        val memo = NyoraChapterMemo(
            nyoraChapterId = "ch-1",
            percent = 0.42f,
            pageBookmarks = listOf(NyoraPageBookmark(page = 7, note = "panel")),
        )
        val encoded = MemoCodec.encodeChapter(memo)
        val decoded = MemoCodec.decodeChapter(encoded)
        assertEquals(1, decoded.pageBookmarks.size)
        assertEquals(7, decoded.pageBookmarks.first().page)
        assertEquals("panel", decoded.pageBookmarks.first().note)
        assertEquals(0.42f, decoded.percent)
    }

    @Test
    fun `default memo matches mihons empty json object`() {
        assertContentEquals("{}".encodeToByteArray(), EMPTY_MEMO)
        assertNotNull(MihonManga(source = 1, url = "/a").memo)
    }
}
