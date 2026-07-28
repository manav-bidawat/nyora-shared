package com.nyora.hasan72341.shared.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the JVM and Swift implementations agree on the wire.
 *
 * Two independent encoders exist (kotlinx protobuf here, a hand-written writer on
 * iOS). Tag drift between them would only surface as a corrupt restore on a real
 * device, so the files are exchanged through a shared directory and each side
 * decodes the other's output.
 *
 * Skips cleanly when the fixture is absent, so the suite stays runnable alone.
 */
class CrossPlatformInteropTest {

    private val dir = System.getenv("NYORA_INTEROP_DIR")

    @Test
    fun `decodes a tachibk written by the swift implementation`() {
        val file = dir?.let { File(it, "swift.tachibk") } ?: return
        if (!file.exists()) return

        val backup = MihonBackupCodec.decode(file.readBytes())
        assertEquals(1, backup.backupManga.size)
        val manga = backup.backupManga.first()

        assertEquals("Berserk", manga.title)
        assertEquals(2499283573021220255L, manga.source)
        assertEquals("/manga/berserk", manga.url)
        assertEquals("Kentaro Miura", manga.author)
        assertEquals("Miura", manga.artist)
        assertEquals(listOf("Action", "Dark Fantasy"), manga.genre)
        assertEquals(6, manga.status)
        assertEquals(listOf(0L, 1L), manga.categories)
        // Non-default false against a schema default of true — the case a naive
        // encoder silently drops.
        assertEquals(false, manga.favorite)
        assertTrue(manga.initialized)

        assertEquals(1, manga.chapters.size)
        val chapter = manga.chapters.first()
        assertEquals("/c/1", chapter.url)
        assertEquals("Chapter 1", chapter.name)
        assertEquals("Team", chapter.scanlator)
        assertTrue(chapter.read)
        assertTrue(chapter.bookmark)
        assertEquals(12L, chapter.lastPageRead)
        assertEquals(1.5f, chapter.chapterNumber)

        assertEquals(1700000000000L, manga.history.first().lastRead)

        val track = manga.tracking.first()
        assertEquals(2, track.syncId)
        assertEquals(30002L, track.remoteId)
        assertEquals(9.5f, track.score)
        assertEquals(364f, track.lastChapterRead)
        assertEquals(400, track.totalChapters)

        assertEquals(listOf("Reading", "Read later"), backup.backupCategories.map { it.name })
        assertEquals("MangaDex", backup.backupSources.first().name)

        // The Nyora memo must survive the other implementation's encoder.
        val memo = MemoCodec.decodeManga(manga.memo)
        assertEquals("mangadex:abc-123", memo.nyoraId)
        assertEquals("MANGADEX", memo.sourceRef)
        assertEquals(listOf("ベルセルク"), memo.altTitles)
        assertEquals(9.4f, memo.rating)
    }

    @Test
    fun `writes a tachibk for the swift implementation to read`() {
        val out = dir?.let { File(it, "jvm.tachibk") } ?: return
        val memo = NyoraMangaMemo(
            nyoraId = "mangadex:jvm-1",
            sourceRef = "MANGADEX",
            altTitles = listOf("ベルセルク"),
            rating = 8.25f,
        )
        val backup = MihonBackup(
            backupManga = listOf(
                MihonManga(
                    source = 2499283573021220255L,
                    url = "/manga/berserk",
                    title = "Berserk",
                    artist = "Miura",
                    author = "Kentaro Miura",
                    description = "A dark fantasy",
                    genre = listOf("Action", "Dark Fantasy"),
                    status = 6,
                    thumbnailUrl = "https://example.invalid/b.jpg",
                    chapters = listOf(
                        MihonChapter(
                            url = "/c/1", name = "Chapter 1", scanlator = "Team",
                            read = true, bookmark = true, lastPageRead = 12,
                            chapterNumber = 1.5f, sourceOrder = 0,
                        ),
                    ),
                    categories = listOf(0L, 1L),
                    tracking = listOf(
                        MihonTracking(
                            syncId = 2, title = "Berserk", lastChapterRead = 364f,
                            totalChapters = 400, score = 9.5f, status = 1, mediaId = 30002,
                        ),
                    ),
                    favorite = false,
                    history = listOf(MihonHistory(url = "/c/1", lastRead = 1700000000000L)),
                    initialized = true,
                    memo = MemoCodec.encodeManga(memo),
                ),
            ),
            backupCategories = listOf(
                MihonCategory(name = "Reading", order = 0, id = 1),
                MihonCategory(name = "Read later", order = 1, id = 2),
            ),
            backupSources = listOf(MihonSource(name = "MangaDex", sourceId = 2499283573021220255L)),
        )
        out.writeBytes(MihonBackupCodec.encode(backup))
        assertTrue(out.length() > 0)
    }

    /**
     * The iOS EXPORT path, verified by the other implementation.
     *
     * Produced by running `MihonBackupConverter.convert` over a library snapshot,
     * so this asserts the real mapping — not a hand-built message.
     */
    @Test
    fun `ios export is genuinely mihon format`() {
        val file = dir?.let { File(it, "swift-export.tachibk") } ?: return
        if (!file.exists()) return

        val backup = MihonBackupCodec.decode(file.readBytes())
        assertEquals(3, backup.backupManga.size)

        // Categories are declared with contiguous ORDER values starting at 0.
        assertEquals(listOf("Reading", "Read later", "Favourites"), backup.backupCategories.map { it.name })
        assertEquals(listOf(0L, 1L, 2L), backup.backupCategories.map { it.order })

        val berserk = backup.backupManga.first { it.title == "Berserk" }

        // A local source was translated to a REAL Mihon extension id — the whole
        // point of the export direction. A synthetic id would not be in the bridge.
        assertEquals("parser:MANGADEX", MihonSourceBridge.nyoraSourceId(berserk.source))
        assertEquals("MangaDex", backup.backupSources.first { it.sourceId == berserk.source }.name)

        // Per-manga categories are ORDER references, not names or ids.
        assertEquals(listOf(1L, 0L), berserk.categories)

        assertTrue(berserk.favorite)
        assertEquals("Kentaro Miura", berserk.author)
        assertEquals(listOf("Action", "Dark Fantasy"), berserk.genre)
        assertEquals(6, berserk.status)
        assertTrue(berserk.initialized)

        // Chapters are ordered by sourceOrder and keep fractional numbering.
        assertEquals(listOf(0L, 1L), berserk.chapters.map { it.sourceOrder })
        assertEquals(1.5f, berserk.chapters[0].chapterNumber)
        assertEquals("Chapter 1", berserk.chapters[0].name)
        assertTrue(berserk.chapters[0].read)
        assertEquals(12L, berserk.chapters[0].lastPageRead)
        assertEquals("Team", berserk.chapters[0].scanlator)
        // Second chapter was never read.
        assertEquals(false, berserk.chapters[1].read)
        // dateUpload is epoch MILLIS, not seconds.
        assertEquals(1500000000000L, berserk.chapters[0].dateUpload)

        assertEquals(1700000000000L, berserk.history.first().lastRead)
        assertEquals(2, berserk.tracking.first().syncId)           // AniList
        assertEquals(30002L, berserk.tracking.first().remoteId)

        // Read but not in the library: favorite=false against a schema DEFAULT of
        // true — only survives if the writer emits non-default values.
        val vagabond = backup.backupManga.first { it.title == "Vagabond" }
        assertEquals(false, vagabond.favorite)

        // An unresolved Mihon import keeps its ORIGINAL source id, so a round trip
        // lands on exactly the same row.
        val imported = backup.backupManga.first { it.title == "Imported Title" }
        assertEquals(987654321L, imported.source)

        // Nyora provenance rides in the memo and survives the other encoder.
        assertEquals("abc-123", MemoCodec.decodeManga(berserk.memo).nyoraId)
        assertEquals("mangadex", MemoCodec.decodeManga(berserk.memo).sourceRef)
        assertEquals("ch-1", MemoCodec.decodeChapter(berserk.chapters[0].memo).nyoraChapterId)
    }
}
