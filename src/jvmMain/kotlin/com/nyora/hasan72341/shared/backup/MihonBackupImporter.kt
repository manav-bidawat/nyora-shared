package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.model.MangaTag
import com.nyora.hasan72341.shared.repository.MangaPrefsRow
import com.nyora.hasan72341.shared.repository.TrackingRow
import com.nyora.hasan72341.shared.sync.NyoraSyncConfig
import java.security.MessageDigest

data class MihonImportResult(
    val manga: Int = 0,
    val categories: Int = 0,
    val history: Int = 0,
    val bookmarks: Int = 0,
    val tracking: Int = 0,
    /** Mihon sources bound to a Nyora parser (bridge hits plus name matches). */
    val sourcesMatched: Int = 0,
    /** Of those, how many came from the offline domain bridge rather than a name guess. */
    val sourcesBridged: Int = 0,
    /** Mihon source names Nyora has no parser for; their entries still imported. */
    val unmatchedSources: List<String> = emptyList(),
    /** Entries queued for title-resolution against their matched Nyora source. */
    val pendingResolution: Int = 0,
    /**
     * Category holding every entry that could not be bound to a Nyora source, so
     * the user has one place to find and migrate them. -1 when none were needed.
     */
    val missingSourceCategoryId: Long = -1L,
    /** Entries filed under [missingSourceCategoryId]. */
    val missingSourceCount: Int = 0,
    /** True when a cloud sync run was dispatched; false when signed out (import is local-only). */
    val syncPushed: Boolean = false,
)

/**
 * Restores a Mihon `.tachibk` into Nyora.
 *
 * This is a MERGE, never a replace: nothing absent from the file is deleted, and
 * an entry that already exists keeps whichever side has more progress. That
 * matters because Nyora's cloud sync uses soft-delete tombstones — a
 * clear-then-insert import would replicate mass deletions to every other device.
 *
 * Mihon URLs do not resolve against Nyora's parsers even when both scrape the
 * same site, so imported entries are parked under [MangaSourceRef.Mihon] and
 * queued in the [ResolveQueue]. The library is fully browsable immediately
 * (title, cover, progress, categories); binding to a live Nyora source happens
 * lazily on first open, or eagerly via [LibraryResolver].
 */
class MihonBackupImporter(
    private val facade: NyoraFacade,
    private val resolveQueue: ResolveQueue,
) {

    fun import(bytes: ByteArray): MihonImportResult {
        val backup = MihonBackupCodec.decode(bytes)
        val mapper = MihonSourceMapper(facade.listSources(), MihonSourceBridge.map)
        val sourceById = mapper.buildIdMap(backup.backupSources)
        val sourceNames = backup.backupSources.associate { it.sourceId to it.name }
        val categoryIdByOrder = restoreCategories(backup.backupCategories)

        // saveTracking is a blind upsert, so the current rows are needed to avoid
        // regressing progress when an older backup is imported.
        val existingTracking = facade.allTracking().associateBy { it.mangaId to it.trackerId }

        val totals = Totals()
        val unresolved = mutableListOf<ResolveEntry>()

        for (entry in backup.backupManga) {
            val memo = MemoCodec.decodeManga(entry.memo)
            val originName = sourceNames[entry.source].orEmpty()
            val identity = MihonIdentity.resolve(entry.source, memo, syntheticMangaId(entry))

            facade.upsertManga(
                toNyoraManga(
                    backupManga = entry,
                    memo = if (identity.isNyoraOrigin) {
                        memo
                    } else {
                        memo.copy(originSourceName = originName, unresolved = true)
                    },
                    mangaId = identity.mangaId,
                    sourceRef = identity.sourceRef,
                ),
            )
            if (entry.favorite) ensureFavourited(identity.mangaId)

            restoreMangaCategories(identity.mangaId, entry.categories, categoryIdByOrder)
            restorePrefs(identity.mangaId, memo)
            totals.manga++
            totals.history += restoreHistory(entry, identity.mangaId, identity.rowSourceId)
            totals.bookmarks += restoreBookmarks(entry, identity.mangaId)
            totals.tracking += restoreTracking(entry, identity.mangaId, identity.rowSourceId, existingTracking)

            if (!identity.isNyoraOrigin) {
                unresolved += ResolveEntry(
                    mangaId = identity.mangaId,
                    title = entry.title,
                    targetSourceId = sourceById[entry.source]?.id.orEmpty(),
                    originSourceName = originName,
                )
            }
        }

        resolveQueue.enqueue(unresolved)
        val missingCategoryId = fileUnderMissingSource(unresolved)
        val syncing = startSync()

        return MihonImportResult(
            manga = totals.manga,
            categories = categoryIdByOrder.size,
            history = totals.history,
            bookmarks = totals.bookmarks,
            tracking = totals.tracking,
            sourcesMatched = sourceById.size,
            sourcesBridged = backup.backupSources.count { mapper.isBridged(it.sourceId) },
            unmatchedSources = backup.backupSources
                .filter { it.sourceId !in sourceById && it.name.isNotEmpty() }
                .map { it.name }
                .distinct(),
            pendingResolution = unresolved.size,
            missingSourceCategoryId = missingCategoryId,
            missingSourceCount = if (missingCategoryId >= 0L) unresolved.size else 0,
            syncPushed = syncing,
        )
    }

    private class Totals {
        var manga = 0
        var history = 0
        var bookmarks = 0
        var tracking = 0
    }

    /**
     * Collects entries with no matching Nyora source into a single category so
     * they can be found and migrated by hand. Returns the category id, or -1 when
     * there was nothing to file.
     */
    private fun fileUnderMissingSource(entries: List<ResolveEntry>): Long {
        if (entries.isEmpty()) return -1L
        val categoryId = findOrCreateMissingSourceCategory()
        if (categoryId < 0L) return -1L
        for (entry in entries) {
            if (facade.categoriesForManga(entry.mangaId).none { it.id == categoryId }) {
                facade.addToCategory(entry.mangaId, categoryId)
            }
        }
        return categoryId
    }

    /**
     * Converges other devices on the merged library. [NyoraFacade.nyoraSyncNow]
     * blocks for the duration of a full push and pull, so it runs off-thread;
     * it also no-ops while signed out, hence the explicit check.
     */
    private fun startSync(): Boolean {
        if (!NyoraSyncConfig.isAuthenticated) return false
        Thread({ runCatching { facade.nyoraSyncNow() } }, "mihon-import-sync").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * The "Missing Source" bucket, matched by title so repeated imports reuse it
     * rather than piling up duplicate tabs.
     */
    private fun findOrCreateMissingSourceCategory(): Long =
        facade.favouriteCategories().firstOrNull { it.title == MISSING_SOURCE_CATEGORY }?.id
            ?: facade.createCategory(MISSING_SOURCE_CATEGORY)

    /** Mihon addresses a manga's categories by ORDER; returns order -> local id. */
    private fun restoreCategories(backupCategories: List<MihonCategory>): Map<Long, Long> {
        if (backupCategories.isEmpty()) return emptyMap()
        val existing = facade.favouriteCategories()
        val byTitle = existing.associateBy { it.title }
        val result = HashMap<Long, Long>()
        for (category in backupCategories.sortedBy { it.order }) {
            // Match by name so a re-import is idempotent instead of duplicating tabs.
            val localId = byTitle[category.name]?.id ?: facade.createCategory(category.name)
            if (localId >= 0L) result[category.order] = localId
        }
        return result
    }

    private fun restoreMangaCategories(
        mangaId: String,
        orders: List<Long>,
        categoryIdByOrder: Map<Long, Long>,
    ) {
        val current = facade.categoriesForManga(mangaId).map { it.id }.toSet()
        for (order in orders) {
            val localId = categoryIdByOrder[order] ?: continue
            if (localId !in current) facade.addToCategory(mangaId, localId)
        }
    }

    /**
     * Mihon keeps history per chapter; Nyora keeps one row per manga, so we take
     * the most recently read chapter. Progress is preserved, per-chapter read
     * timestamps are not.
     */
    private fun restoreHistory(backupManga: MihonManga, mangaId: String, sourceId: String): Int {
        val latest = backupManga.history.maxByOrNull { it.lastRead } ?: return 0
        val chapter = backupManga.chapters.firstOrNull { it.url == latest.url }
        val chapterMemo = MemoCodec.decodeChapter(chapter?.memo)
        val chapterId = chapterMemo.nyoraChapterId.ifEmpty { chapter?.url ?: latest.url }
        val percent = when {
            chapterMemo.percent > 0f -> chapterMemo.percent
            chapter?.read == true -> 1f
            else -> 0f
        }
        facade.recordHistory(
            mangaId,
            sourceId,
            chapterId,
            chapter?.name.orEmpty(),
            chapter?.lastPageRead?.toInt() ?: 0,
            percent,
        )
        return 1
    }

    private fun restoreBookmarks(backupManga: MihonManga, mangaId: String): Int {
        var count = 0
        for (chapter in backupManga.chapters) {
            val memo = MemoCodec.decodeChapter(chapter.memo)
            val chapterId = memo.nyoraChapterId.ifEmpty { chapter.url }
            // Nyora bookmarks a page; Mihon only flags a whole chapter. A
            // chapter-level flag becomes a page-0 bookmark.
            val pages = memo.pageBookmarks.ifEmpty {
                if (chapter.bookmark) listOf(NyoraPageBookmark(page = 0)) else emptyList()
            }
            for (bookmark in pages) {
                if (facade.isPageBookmarked(mangaId, chapterId, bookmark.page)) continue
                facade.addBookmark(mangaId, chapterId, chapter.name, bookmark.page, bookmark.note)
                count++
            }
        }
        return count
    }

    private fun restoreTracking(
        backupManga: MihonManga,
        mangaId: String,
        sourceId: String,
        existing: Map<Pair<String, String>, TrackingRow>,
    ): Int {
        var count = 0
        for (track in backupManga.tracking) {
            val trackerId = TrackerCodec.toNyoraTrackerId(track.syncId) ?: continue
            // Never regress: an older backup must not walk local progress backwards.
            val current = existing[mangaId to trackerId]
            if (current != null && current.lastReadChapter > track.lastChapterRead) continue
            facade.saveTracking(
                TrackingRow(
                    trackerId = trackerId,
                    remoteId = track.remoteId.toString(),
                    sourceId = sourceId,
                    mangaId = mangaId,
                    title = track.title,
                    status = TrackerCodec.toNyoraStatus(track.syncId, track.status),
                    score = track.score,
                    lastReadChapter = track.lastChapterRead,
                    lastReadVolume = current?.lastReadVolume ?: 0,
                    totalChapters = track.totalChapters,
                    totalVolumes = current?.totalVolumes ?: 0,
                    chapterOffset = current?.chapterOffset ?: 0,
                    startedAt = isoOrEmpty(track.startedReadingDate),
                    finishedAt = isoOrEmpty(track.finishedReadingDate),
                    comment = current?.comment.orEmpty(),
                    updatedAt = nowIso(),
                    deletedAt = "",
                ),
            )
            count++
        }
        return count
    }

    private fun restorePrefs(mangaId: String, memo: NyoraMangaMemo) {
        if (memo.readerMode.isEmpty() && memo.palette.isEmpty() && memo.brightness == 0.0) return
        facade.saveMangaPrefs(
            MangaPrefsRow(
                mangaId = mangaId,
                readerMode = memo.readerMode,
                brightness = memo.brightness,
                contrast = memo.contrast,
                saturation = memo.saturation,
                hue = memo.hue,
                palette = memo.palette,
            ),
        )
    }

    private fun toNyoraManga(
        backupManga: MihonManga,
        memo: NyoraMangaMemo,
        mangaId: String,
        sourceRef: MangaSourceRef,
    ): Manga = Manga(
        id = mangaId,
        title = backupManga.title,
        altTitles = memo.altTitles,
        url = backupManga.url,
        publicUrl = memo.publicUrl,
        rating = memo.rating,
        isNsfw = memo.isNsfw,
        contentRating = ContentRatingCodec.decode(memo.contentRating),
        coverUrl = backupManga.thumbnailUrl.orEmpty(),
        largeCoverUrl = memo.largeCoverUrl.ifEmpty { null },
        state = MangaStateCodec.decode(memo.state) ?: StatusCodec.toNyora(backupManga.status),
        authors = listOfNotNull(backupManga.author, backupManga.artist).filter { it.isNotBlank() },
        source = sourceRef,
        description = backupManga.description.orEmpty(),
        tags = backupManga.genre.map { MangaTag(key = it.lowercase(), title = it) },
        chapters = backupManga.chapters.map { chapter ->
            val chapterMemo = MemoCodec.decodeChapter(chapter.memo)
            MangaChapter(
                id = chapterMemo.nyoraChapterId.ifEmpty { chapter.url },
                title = chapter.name,
                number = chapter.chapterNumber,
                volume = chapterMemo.volume,
                url = chapter.url,
                scanlator = chapter.scanlator,
                uploadDate = chapter.dateUpload,
                branch = chapterMemo.branch.ifEmpty { null },
                index = chapter.sourceOrder.toInt(),
            )
        },
    )

    /** Favourite is OR-merged: an import can add a favourite but never remove one. */
    private fun ensureFavourited(mangaId: String) {
        var attempts = 0
        while (!facade.isFavourited(mangaId) && attempts < 2) {
            facade.toggleFavourite(mangaId)
            attempts++
        }
    }

    /**
     * Stable Nyora id for a Mihon entry, so re-importing the same backup updates
     * the same rows instead of duplicating the library.
     */
    private fun syntheticMangaId(backupManga: MihonManga): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${backupManga.source}:${backupManga.url}".encodeToByteArray())
        return "mihon_" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun isoOrEmpty(epochMillis: Long): String =
        if (epochMillis <= 0L) "" else java.time.Instant.ofEpochMilli(epochMillis).toString()

    companion object {
        /** Title of the category collecting entries with no matching Nyora source. */
        const val MISSING_SOURCE_CATEGORY = "Missing Source"
    }

    private fun nowIso(): String =
        java.time.Instant.now().toString().replace(Regex("""\.\d+Z$"""), "Z")
}
