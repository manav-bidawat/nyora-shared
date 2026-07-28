package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.repository.BookmarkRow
import com.nyora.hasan72341.shared.repository.HistoryRow
import com.nyora.hasan72341.shared.repository.MangaPrefsRow
import com.nyora.hasan72341.shared.repository.TrackingRow
import java.security.MessageDigest

/**
 * Writes Nyora's library as a Mihon-compatible `.tachibk`.
 *
 * Mihon keys a manga by `(sourceId: Long, url: String)`, but Nyora's sources are
 * its own parsers with string ids. Entries that originally came from Mihon keep
 * their real [MangaSourceRef.Mihon] id so they land back on exactly the same row;
 * Nyora-native entries get a deterministic synthetic id derived from the source
 * name. Mihon flags a synthetic id as "source not installed" — a warning, not an
 * error — and restores the library and all reading progress regardless.
 *
 * The portable key in both directions is the source *name*, which we always write
 * into [MihonSource.name].
 */
class MihonBackupExporter(
    private val facade: NyoraFacade,
    /**
     * Supplies the original Mihon source name for entries that are still
     * unresolved. Nyora's [Manga] has no memo column, so the name recorded at
     * import time lives in the resolve queue — the only place it survives.
     */
    private val resolveQueue: ResolveQueue? = null,
) {

    fun export(): ByteArray {
        val originNames = resolveQueue?.load()
            ?.filter { it.originSourceName.isNotEmpty() }
            ?.associate { it.mangaId to it.originSourceName }
            .orEmpty()
        val historyRows = facade.history(limit = HISTORY_LIMIT)
        val history = historyRows.groupBy { it.manga.id }
        val favourites = facade.favourites()
        val favouriteIds = favourites.map { it.id }.toSet()
        // Mihon also backs up entries that were read but never added to the
        // library (`getReadMangaNotInLibrary`). Without this their progress is
        // silently dropped on export.
        val readNotInLibrary = historyRows
            .map { it.manga }
            .distinctBy { it.id }
            .filter { it.id !in favouriteIds }
        val allManga = favourites + readNotInLibrary
        val bookmarks = facade.bookmarks().groupBy { it.mangaId }
        val tracking = facade.allTracking().groupBy { it.mangaId }
        val prefs = facade.allMangaPrefs().associateBy { it.mangaId }

        val categories = facade.favouriteCategories().sortedBy { it.sortKey }
        // Mihon addresses a manga's categories by ORDER, not by id.
        val categoryOrderById = categories.withIndex().associate { (index, row) -> row.id to index.toLong() }

        val backupCategories = categories.mapIndexed { index, row ->
            MihonCategory(name = row.title, order = index.toLong(), id = row.id)
        }

        val sourceIds = LinkedHashMap<Long, String>()
        val sources = facade.listSources()
        val sourceNamesById = sources.associate { it.id to it.name }
        val sourceLangById = sources.associate { it.id to it.lang }

        val backupManga = allManga.map { manga ->
            val (sourceId, mihonName) = translateSource(manga, sourceLangById)
            sourceIds.putIfAbsent(
                sourceId,
                mihonName ?: sourceDisplayName(manga, sourceNamesById, originNames),
            )
            val rows = MangaRows(
                history = history[manga.id].orEmpty(),
                bookmarks = bookmarks[manga.id].orEmpty(),
                tracking = tracking[manga.id].orEmpty(),
                prefs = prefs[manga.id],
                categoryOrders = facade.categoriesForManga(manga.id).mapNotNull { categoryOrderById[it.id] },
                favourite = manga.id in favouriteIds,
            )
            toMihonManga(manga, sourceId, rows)
        }

        val backup = MihonBackup(
            backupManga = backupManga,
            backupCategories = backupCategories,
            backupSources = sourceIds.map { (id, name) -> MihonSource(name = name, sourceId = id) },
        )

        val bytes = MihonBackupCodec.encode(backup)
        // Prove the file is readable before it ever reaches the user.
        MihonBackupCodec.verify(bytes)
        return bytes
    }

    /** Everything belonging to one manga, gathered before the schema mapping. */
    private class MangaRows(
        val history: List<HistoryRow>,
        val bookmarks: List<BookmarkRow>,
        val tracking: List<TrackingRow>,
        val prefs: MangaPrefsRow?,
        val categoryOrders: List<Long>,
        val favourite: Boolean,
    )

    private fun toMihonManga(manga: Manga, sourceId: Long, rows: MangaRows): MihonManga {
        val chapters = manga.chapters.map { chapter -> toMihonChapter(chapter, rows) }
        val lastRead = rows.history.maxByOrNull { it.updatedAt }

        return MihonManga(
            source = sourceId,
            url = manga.url,
            title = manga.title,
            author = manga.authors.firstOrNull(),
            artist = manga.authors.getOrNull(1),
            description = manga.description,
            genre = manga.tags.map { it.title },
            status = StatusCodec.fromNyora(manga.state),
            thumbnailUrl = manga.coverUrl,
            favorite = rows.favourite,
            initialized = manga.chapters.isNotEmpty(),
            chapters = chapters,
            categories = rows.categoryOrders,
            history = lastRead?.let { row ->
                val url = chapters.firstOrNull { MemoCodec.decodeChapter(it.memo).nyoraChapterId == row.chapterId }
                listOf(MihonHistory(url = url?.url ?: row.chapterId, lastRead = row.updatedAt))
            }.orEmpty(),
            tracking = rows.tracking.mapNotNull(::toMihonTracking),
            memo = MemoCodec.encodeManga(toMemo(manga, rows.prefs)),
        )
    }

    private fun toMihonChapter(chapter: MangaChapter, rows: MangaRows): MihonChapter {
        val progress = rows.history.firstOrNull { it.chapterId == chapter.id }
        return MihonChapter(
            url = chapter.url.ifEmpty { chapter.id },
            name = chapter.title,
            scanlator = chapter.scanlator,
            read = progress != null && progress.percent >= READ_THRESHOLD,
            bookmark = rows.bookmarks.any { it.chapterId == chapter.id },
            lastPageRead = progress?.page?.toLong() ?: 0L,
            dateUpload = chapter.uploadDate,
            chapterNumber = chapter.number,
            sourceOrder = chapter.index.toLong(),
            memo = MemoCodec.encodeChapter(
                NyoraChapterMemo(
                    nyoraChapterId = chapter.id,
                    percent = progress?.percent ?: 0f,
                    branch = chapter.branch.orEmpty(),
                    volume = chapter.volume,
                    pageBookmarks = rows.bookmarks
                        .filter { it.chapterId == chapter.id }
                        .map { NyoraPageBookmark(page = it.page, note = it.note, createdAt = it.createdAt) },
                ),
            ),
        )
    }

    private fun toMihonTracking(row: TrackingRow): MihonTracking? {
        val syncId = TrackerCodec.toMihonTrackerId(row.trackerId) ?: return null
        return MihonTracking(
            syncId = syncId,
            mediaId = row.remoteId.toLongOrNull() ?: 0L,
            title = row.title,
            lastChapterRead = row.lastReadChapter,
            totalChapters = row.totalChapters,
            score = row.score,
            status = TrackerCodec.toMihonStatus(syncId, row.status),
        )
    }

    /** Nyora-only fields, carried in the extension bag Mihon copies through. */
    private fun toMemo(manga: Manga, prefs: MangaPrefsRow?) = NyoraMangaMemo(
        nyoraId = manga.id,
        sourceRef = manga.source.name,
        altTitles = manga.altTitles,
        publicUrl = manga.publicUrl,
        largeCoverUrl = manga.largeCoverUrl.orEmpty(),
        contentRating = ContentRatingCodec.encode(manga.contentRating),
        state = MangaStateCodec.encode(manga.state),
        rating = manga.rating,
        isNsfw = manga.isNsfw,
        readerMode = prefs?.readerMode.orEmpty(),
        brightness = prefs?.brightness ?: 0.0,
        contrast = prefs?.contrast ?: 1.0,
        saturation = prefs?.saturation ?: 1.0,
        hue = prefs?.hue ?: 0.0,
        palette = prefs?.palette.orEmpty(),
    )

    /**
     * The name written into [MihonSource.name] — the portable key another app
     * matches on, so it must be the human-readable site name, never an id.
     *
     * For an unresolved Mihon import the ref name is `MIHON_<numeric id>`, which
     * is useless as a name; the original was stashed in the memo at import time.
     */
    private fun sourceDisplayName(
        manga: Manga,
        sourceNamesById: Map<String, String>,
        originNames: Map<String, String>,
    ): String {
        val ref = manga.source
        if (ref is MangaSourceRef.Mihon) {
            // Fall back to the ref name only if provenance was lost; a numeric
            // id is a poor key but still better than an empty name.
            return originNames[manga.id] ?: ref.name
        }
        return sourceNamesById[MihonSourceIds.sourceIdFor(ref)]
            ?: sourceNamesById[ref.name]
            ?: ref.name
    }

    /**
     * Picks the Mihon source id to write, and the name to declare for it.
     *
     * Three cases, in order:
     *  1. The entry came from Mihon and is still unresolved — keep its original
     *     id so a Nyora -> Mihon round trip lands on exactly the same row.
     *  2. The entry is on a Nyora source that has a Mihon equivalent — translate
     *     to that extension's real id, preferring a language match, so restoring
     *     into Mihon binds to a source the user can actually install.
     *  3. No equivalent exists — fall back to a deterministic synthetic id.
     *     Mihon reports it as "source not installed", a warning rather than an
     *     error, and still restores the library and all reading progress.
     */
    private fun translateSource(manga: Manga, sourceLangById: Map<String, String>): Pair<Long, String?> {
        val ref = manga.source
        if (ref is MangaSourceRef.Mihon) return ref.sourceId to null

        val nyoraSourceId = MihonSourceIds.sourceIdFor(ref)
        val lang = sourceLangById[nyoraSourceId] ?: sourceLangById[ref.name].orEmpty()
        MihonSourceBridge.mihonSourceFor(nyoraSourceId, lang)
            ?.takeIf { it.sourceId != 0L }
            ?.let { return it.sourceId to it.name }

        return syntheticSourceId(ref.name) to null
    }

    private fun syntheticSourceId(name: String): Long {
        val digest = MessageDigest.getInstance("MD5").digest(name.lowercase().encodeToByteArray())
        var id = 0L
        for (i in 0 until 8) {
            id = (id shl 8) or (digest[i].toLong() and 0xff)
        }
        // Mihon source ids are positive; clearing the sign bit keeps us in range.
        return id and Long.MAX_VALUE
    }

    private companion object {
        const val HISTORY_LIMIT = 100_000
        /** Nyora stores fractional progress; Mihon only has a read/unread flag. */
        const val READ_THRESHOLD = 0.95f
    }
}
