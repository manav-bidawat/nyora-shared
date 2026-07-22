package com.nyora.hasan72341.shared.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.nyora.hasan72341.shared.db.NyoraDatabase
import com.nyora.hasan72341.shared.model.HistoryEntry
import com.nyora.hasan72341.shared.model.Library
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaRepo
import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.model.MangaSourceRefCodec
import com.nyora.hasan72341.shared.model.MangaState
import com.nyora.hasan72341.shared.model.MangaTag
import com.nyora.hasan72341.shared.model.SourceContentType
import com.nyora.hasan72341.shared.model.SourceEngine
import com.nyora.hasan72341.shared.model.defaultRepos
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.io.path.absolutePathString

/**
 * SQLite-backed implementation. Lives in jvmMain because it uses SQLDelight's
 * JDBC driver. The macOS-native side would swap in `NativeSqliteDriver`.
 *
 * Schema scope (Phase 2 v1):
 *   - manga             (flat row; tags/authors/chapters serialized as JSON)
 *   - manga_source      (full row)
 *
 * Out of scope this pass — handled later or kept in memory:
 *   - repos             (Library.repos, returned via defaultRepos for now)
 *   - history           (Phase 4 reader + history)
 *   - favourites / categories (Phase 5)
 */
/** A favourite category for sync push — includes soft-deleted rows (deletedAt != null). */
data class CategorySyncRow(val id: Long, val title: String, val sortKey: Long, val deletedAt: Long?)

class SqlDelightLibraryRepository(
    dbPath: Path = defaultDatabasePath(),
) : LibraryRepository {
    private val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${dbPath.absolutePathString()}",
    ).also { driver ->
        ensureSchema(driver)
    }
    internal val database = NyoraDatabase(driver)

    init {
        // Collapse duplicate-title favourite categories that piled up via sync (each device
        // historically created its own "Read later"). Idempotent — a no-op once deduped.
        dedupeCategories()
    }

    // Nyora Sync sync — set by the app after NyoraSyncConfig is loaded and the user has signed in.
    // Fire-and-forget: called on a daemon thread after each mutating operation.
    var nyoraSync: com.nyora.hasan72341.shared.sync.NyoraSync? = null

    private fun triggerPush() {
        val sync = nyoraSync ?: return
        Thread(sync::pushAll).also { it.isDaemon = true }.start()
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun ensureSchema(driver: JdbcSqliteDriver) {
        // CREATE TABLE IF NOT EXISTS in the .sq files takes care of fresh installs.
        NyoraDatabase.Schema.create(driver)
        // Runtime ALTERs for columns added after a user's DB was first created.
        // SQLite has no `ADD COLUMN IF NOT EXISTS`, so we use a sentinel and
        // swallow the "duplicate column" error if it's already there.

        // Phase 1 (original)
        runAlterIfMissing(driver, "manga_history", "source_id", "ALTER TABLE manga_history ADD COLUMN source_id TEXT NOT NULL DEFAULT ''")
        backfillHistorySourceIds(driver)

        // Phase 2 — sync parity fixes (deleted_at + missing fields)
        runAlterIfMissing(driver, "manga",           "updated_at", "ALTER TABLE manga ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")

        runAlterIfMissing(driver, "manga_favourite", "sort_key",    "ALTER TABLE manga_favourite ADD COLUMN sort_key INTEGER NOT NULL DEFAULT 0")
        runAlterIfMissing(driver, "manga_favourite", "deleted_at",  "ALTER TABLE manga_favourite ADD COLUMN deleted_at INTEGER")

        runAlterIfMissing(driver, "manga_history",  "scroll",         "ALTER TABLE manga_history ADD COLUMN scroll REAL NOT NULL DEFAULT 0.0")
        runAlterIfMissing(driver, "manga_history",  "chapters_count", "ALTER TABLE manga_history ADD COLUMN chapters_count INTEGER NOT NULL DEFAULT 0")
        runAlterIfMissing(driver, "manga_history",  "deleted_at",     "ALTER TABLE manga_history ADD COLUMN deleted_at INTEGER")

        runAlterIfMissing(driver, "bookmark",       "scroll",     "ALTER TABLE bookmark ADD COLUMN scroll REAL NOT NULL DEFAULT 0.0")
        runAlterIfMissing(driver, "bookmark",       "image_url",  "ALTER TABLE bookmark ADD COLUMN image_url TEXT NOT NULL DEFAULT ''")
        runAlterIfMissing(driver, "bookmark",       "percent",    "ALTER TABLE bookmark ADD COLUMN percent REAL NOT NULL DEFAULT 0.0")
        runAlterIfMissing(driver, "bookmark",       "updated_at", "ALTER TABLE bookmark ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        runAlterIfMissing(driver, "bookmark",       "deleted_at", "ALTER TABLE bookmark ADD COLUMN deleted_at INTEGER")

        runAlterIfMissing(driver, "favourite_category", "deleted_at", "ALTER TABLE favourite_category ADD COLUMN deleted_at INTEGER")
    }

    /// For history rows that pre-date `source_id`, infer it by matching the
    /// linked manga's `source_ref` JSON against installed source names.
    /// Best-effort — leaves source_id empty for rows we can't resolve.
    private fun backfillHistorySourceIds(driver: JdbcSqliteDriver) {
        runCatching {
            driver.execute(
                identifier = null,
                sql = """
                    UPDATE manga_history
                    SET source_id = COALESCE((
                        SELECT s.id FROM manga_source s, manga m
                        WHERE m.id = manga_history.manga_id
                          AND s.is_installed = 1
                          AND m.source_ref LIKE '%"name":"' || s.name || '"%'
                        LIMIT 1
                    ), source_id)
                    WHERE source_id = ''
                """.trimIndent(),
                parameters = 0,
            )
        }.onFailure { System.err.println("Backfill failed (non-fatal): ${it.message}") }
    }

    private fun runAlterIfMissing(driver: JdbcSqliteDriver, table: String, column: String, alterSql: String) {
        val cols = mutableSetOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildList {
                        while (cursor.next().value) {
                            cursor.getString(1)?.let { cols.add(it) }
                            add(Unit)
                        }
                    },
                )
            },
            parameters = 0,
        ).value
        if (column !in cols) {
            runCatching { driver.execute(null, alterSql, 0) }
                .onFailure { System.err.println("ALTER failed for $table.$column: ${it.message}") }
        }
    }

    /**
     * One-time migration: rewrite legacy manga ids to the cross-platform `nyoraId` scheme — a
     * hash of `sourceName + url`, byte-identical to nyora-web (seed 1125899906842597, rolling
     * `31*h + charCode`, 64-bit signed). Older builds keyed details-opened manga by
     * `url.hashCode()`, which never matched the web or new browse ids. We recompute each PARSER
     * manga's id and remap it across the `manga` row AND every FK table (favourites, history,
     * bookmarks, prefs, categories, updates) so a returning user's library survives and merges
     * with the web on the next sync. Idempotent (recompute == same id → skipped); a browse-row +
     * details-row collision for the same manga collapses via `UPDATE OR IGNORE` + delete.
     */
    fun migrateMangaIdsToNyoraId() {
        val mapping = LinkedHashMap<String, String>() // oldId -> newId
        runCatching {
            val rows = driver.executeQuery(
                identifier = null,
                sql = "SELECT id, url, source_ref FROM manga",
                mapper = { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(
                        buildList {
                            while (cursor.next().value) {
                                val id = cursor.getString(0) ?: continue
                                add(Triple(id, cursor.getString(1) ?: "", cursor.getString(2) ?: "{}"))
                            }
                        },
                    )
                },
                parameters = 0,
            ).value
            for ((id, url, ref) in rows) {
                val decoded = MangaSourceRefCodec.decode(ref)
                if (decoded is MangaSourceRef.Parser) {
                    val newId = nyoraMangaId(decoded.name, url).toString()
                    if (newId != id) mapping[id] = newId
                }
            }
        }.onFailure { System.err.println("[migrate manga_id] scan failed: ${it.message}"); return }

        if (mapping.isEmpty()) return
        // FK cascades would delete the child rows out from under us; disable while remapping.
        runCatching { driver.execute(null, "PRAGMA foreign_keys = OFF", 0) }
        val fkTables = listOf(
            "manga_favourite", "manga_history", "bookmark",
            "manga_prefs", "manga_favourite_category", "manga_update",
        )
        for ((oldId, newId) in mapping) {
            for (t in fkTables) {
                runCatching {
                    driver.execute(null, "UPDATE OR IGNORE $t SET manga_id = ? WHERE manga_id = ?", 2) {
                        bindString(0, newId); bindString(1, oldId)
                    }
                }
                // Remove any leftover old-id row the UPDATE skipped on a collision.
                runCatching { driver.execute(null, "DELETE FROM $t WHERE manga_id = ?", 1) { bindString(0, oldId) } }
            }
            runCatching {
                driver.execute(null, "UPDATE OR IGNORE manga SET id = ? WHERE id = ?", 2) {
                    bindString(0, newId); bindString(1, oldId)
                }
            }
            runCatching { driver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindString(0, oldId) } }
        }
        runCatching { driver.execute(null, "PRAGMA foreign_keys = ON", 0) }
        System.err.println("[migrate manga_id] remapped ${mapping.size} manga id(s) to nyoraId")
    }

    /** Cross-platform stable manga id — see [KotatsuParserExtensionService] / nyora-web `nyoraId`. */
    private fun nyoraMangaId(sourceName: String, url: String): Long {
        var h = 1125899906842597L
        val s = sourceName + url
        for (c in s) h = 31 * h + c.code
        return h
    }

    /**
     * Collapse duplicate same-title favourite categories (legacy per-device "Read later" seeds
     * replicated across devices by sync). Keep the lowest id per title, repoint manga->category
     * links onto it, and soft-delete the rest so the deletion propagates on the next push.
     */
    fun dedupeCategories() {
        runCatching {
            driver.execute(
                null,
                "UPDATE OR IGNORE manga_favourite_category SET category_id = " +
                    "(SELECT MIN(c.id) FROM favourite_category c WHERE c.deleted_at IS NULL AND c.title = " +
                    "(SELECT t.title FROM favourite_category t WHERE t.id = manga_favourite_category.category_id)) " +
                    "WHERE category_id IN (SELECT c.id FROM favourite_category c WHERE c.deleted_at IS NULL AND c.id <> " +
                    "(SELECT MIN(c2.id) FROM favourite_category c2 WHERE c2.deleted_at IS NULL AND c2.title = c.title))",
                0,
            )
            driver.execute(
                null,
                "UPDATE favourite_category SET deleted_at = ${System.currentTimeMillis()} " +
                    "WHERE deleted_at IS NULL AND id <> " +
                    "(SELECT MIN(c2.id) FROM favourite_category c2 WHERE c2.deleted_at IS NULL AND c2.title = favourite_category.title)",
                0,
            )
        }.onFailure { System.err.println("dedupeCategories failed: ${it.message}") }
    }

    /** All categories incl. soft-deleted, for sync push (so deletions propagate). */
    fun categoriesForSync(): List<CategorySyncRow> =
        database.favouriteCategoryQueries.selectAllCategoriesIncludingDeleted().executeAsList()
            .map { CategorySyncRow(it.id, it.title, it.sort_key, it.deleted_at) }

    override fun load(): Library = database.transactionWithResult {
        val mangas = database.mangaQueries.selectAll().executeAsList().map { row -> row.toManga() }
        val sources = database.mangaSourceQueries.selectAll().executeAsList().map { row -> row.toMangaSource() }
        Library(
            mangas = mangas,
            sources = sources,
            repos = defaultRepos,
            categories = emptyList(),
            history = emptyList<HistoryEntry>(),
        )
    }

    override fun save(library: Library) {
        database.transaction {
            for (manga in library.mangas) {
                writeManga(manga)
            }
            for (source in library.sources) {
                writeSource(source)
            }
        }
    }

    override fun upsertManga(manga: Manga) {
        database.transaction { writeManga(manga) }
    }

    override fun upsertSource(source: MangaSource) {
        database.transaction { writeSource(source) }
    }

    override fun deleteSource(sourceId: String) {
        database.mangaSourceQueries.deleteById(sourceId)
    }

    fun togglePin(sourceId: String) {
        database.mangaSourceQueries.togglePin(sourceId)
    }

    fun count(): Pair<Long, Long> {
        return database.mangaQueries.countAll().executeAsOne() to
            database.mangaSourceQueries.countAll().executeAsOne()
    }

    // MARK: - history

    override fun history(limit: Int): List<HistoryRow> {
        return database.mangaHistoryQueries.selectRecent(limit.toLong()).executeAsList().map { row ->
            HistoryRow(
                manga = mangaRowToDomain(
                    id = row.id, title = row.title, alt_titles = row.alt_titles,
                    url = row.url, public_url = row.public_url, rating = row.rating,
                    is_nsfw = row.is_nsfw, content_rating = row.content_rating,
                    cover_url = row.cover_url, large_cover_url = row.large_cover_url,
                    state = row.state, authors = row.authors, source_ref = row.source_ref,
                    description = row.description, tags = row.tags, chapters = row.chapters,
                    unread = row.unread, progress = row.progress,
                ),
                sourceId = row.source_id,
                chapterId = row.chapter_id,
                chapterTitle = row.chapter_title,
                page = row.page.toInt(),
                percent = row.percent.toFloat(),
                updatedAt = row.updated_at,
            )
        }
    }

    override fun recordHistory(
        mangaId: String,
        sourceId: String,
        chapterId: String,
        chapterTitle: String,
        page: Int,
        percent: Float,
    ) {
        database.mangaHistoryQueries.upsert(
            manga_id = mangaId,
            source_id = sourceId,
            chapter_id = chapterId,
            chapter_title = chapterTitle,
            page = page.toLong(),
            scroll = 0.0,
            percent = percent.toDouble(),
            chapters_count = 0,
            updated_at = System.currentTimeMillis(),
        )
        triggerPush()
    }

    fun upsertHistoryFromSync(
        mangaId: String,
        sourceId: String,
        chapterId: String,
        chapterTitle: String,
        page: Int,
        scroll: Double,
        percent: Float,
        chaptersCount: Int,
        updatedAt: String?,
    ) {
        database.mangaHistoryQueries.upsert(
            manga_id = mangaId,
            source_id = sourceId,
            chapter_id = chapterId,
            chapter_title = chapterTitle,
            page = page.toLong(),
            scroll = scroll,
            percent = percent.toDouble(),
            chapters_count = chaptersCount.toLong(),
            updated_at = parseNyoraSyncTimestampMillis(updatedAt),
        )
    }

    override fun removeHistory(mangaId: String) {
        database.mangaHistoryQueries.deleteByMangaId(mangaId)
    }

    override fun clearHistory() {
        database.mangaHistoryQueries.deleteAll()
    }

    // MARK: - favourites

    override fun favourites(): List<Manga> {
        return database.mangaFavouriteQueries.selectAll().executeAsList().map { row ->
            mangaRowToDomain(
                id = row.id, title = row.title, alt_titles = row.alt_titles,
                url = row.url, public_url = row.public_url, rating = row.rating,
                is_nsfw = row.is_nsfw, content_rating = row.content_rating,
                cover_url = row.cover_url, large_cover_url = row.large_cover_url,
                state = row.state, authors = row.authors, source_ref = row.source_ref,
                description = row.description, tags = row.tags, chapters = row.chapters,
                unread = row.unread, progress = row.progress,
            )
        }
    }

    override fun isFavourited(mangaId: String): Boolean {
        return database.mangaFavouriteQueries.isFavourited(mangaId).executeAsOne()
    }

    override fun toggleFavourite(mangaId: String): Boolean {
        val now = System.currentTimeMillis()
        val wasFav = isFavourited(mangaId)
        if (wasFav) {
            database.mangaFavouriteQueries.deleteByMangaId(mangaId)
        } else {
            database.mangaFavouriteQueries.insert(mangaId, now, 0, now)
        }
        triggerPush()
        return !wasFav
    }

    // MARK: - bookmarks

    override fun bookmarks(): List<BookmarkRow> {
        return database.bookmarkQueries.selectAll().executeAsList().map { row ->
            BookmarkRow(
                id = row.id,
                mangaId = row.manga_id,
                mangaTitle = row.manga_title ?: row.manga_id,
                mangaCoverUrl = row.manga_cover_url.orEmpty(),
                chapterId = row.chapter_id,
                chapterTitle = row.chapter_title,
                page = row.page.toInt(),
                note = row.note,
                createdAt = row.created_at,
            )
        }
    }

    override fun bookmarksForChapter(mangaId: String, chapterId: String): List<BookmarkRow> {
        return database.bookmarkQueries.selectForChapter(mangaId, chapterId).executeAsList().map { row ->
            BookmarkRow(
                id = row.id,
                mangaId = row.manga_id,
                mangaTitle = row.manga_title ?: row.manga_id,
                mangaCoverUrl = row.manga_cover_url.orEmpty(),
                chapterId = row.chapter_id,
                chapterTitle = row.chapter_title,
                page = row.page.toInt(),
                note = row.note,
                createdAt = row.created_at,
            )
        }
    }

    override fun isPageBookmarked(mangaId: String, chapterId: String, page: Int): Boolean {
        return database.bookmarkQueries.existsForPage(mangaId, chapterId, page.toLong()).executeAsOne()
    }

    override fun addBookmark(
        mangaId: String,
        chapterId: String,
        chapterTitle: String,
        page: Int,
        note: String,
    ) {
        val now = System.currentTimeMillis()
        database.bookmarkQueries.insert(
            manga_id = mangaId,
            chapter_id = chapterId,
            chapter_title = chapterTitle,
            page = page.toLong(),
            scroll = 0.0,
            note = note,
            image_url = "",
            percent = 0.0,
            created_at = now,
            updated_at = now,
        )
        triggerPush()
    }

    override fun removeBookmark(id: Long) {
        database.bookmarkQueries.deleteById(id)
        triggerPush()
    }

    override fun removeBookmarkForPage(mangaId: String, chapterId: String, page: Int) {
        database.bookmarkQueries.deleteByPage(mangaId, chapterId, page.toLong())
        triggerPush()
    }


    // MARK: - chapter page cache

    override fun cachedPages(chapterUrl: String): List<com.nyora.hasan72341.shared.model.MangaPage>? {
        val row = database.chapterPagesQueries.selectForChapter(chapterUrl).executeAsOneOrNull() ?: return null
        val ageMs = System.currentTimeMillis() - row.fetched_at
        if (ageMs > CACHE_MAX_AGE_MS) return null
        return runCatching {
            json.decodeFromString(
                ListSerializer(com.nyora.hasan72341.shared.model.MangaPage.serializer()),
                row.pages_json,
            )
        }.getOrNull()
    }

    override fun cachePages(chapterUrl: String, mangaId: String, pages: List<com.nyora.hasan72341.shared.model.MangaPage>) {
        val payload = json.encodeToString(
            ListSerializer(com.nyora.hasan72341.shared.model.MangaPage.serializer()),
            pages,
        )
        database.chapterPagesQueries.upsert(
            chapter_url = chapterUrl,
            manga_id = mangaId,
            pages_json = payload,
            fetched_at = System.currentTimeMillis(),
        )
    }

    override fun clearChapterPageCache() {
        database.chapterPagesQueries.deleteAll()
    }

    // MARK: - per-manga reader prefs

    override fun mangaPrefs(mangaId: String): MangaPrefsRow? {
        val row = database.mangaPrefsQueries.selectByMangaId(mangaId).executeAsOneOrNull() ?: return null
        return MangaPrefsRow(
            mangaId = row.manga_id,
            readerMode = row.reader_mode,
            brightness = row.brightness,
            contrast = row.contrast,
            saturation = row.saturation,
            hue = row.hue,
            palette = row.palette,
        )
    }

    override fun allMangaPrefs(): List<MangaPrefsRow> {
        return database.mangaPrefsQueries.selectAll().executeAsList().map { row ->
            MangaPrefsRow(
                mangaId = row.manga_id,
                readerMode = row.reader_mode,
                brightness = row.brightness,
                contrast = row.contrast,
                saturation = row.saturation,
                hue = row.hue,
                palette = row.palette,
            )
        }
    }

    override fun saveMangaPrefs(prefs: MangaPrefsRow) {
        database.mangaPrefsQueries.upsert(
            manga_id = prefs.mangaId,
            reader_mode = prefs.readerMode,
            brightness = prefs.brightness,
            contrast = prefs.contrast,
            saturation = prefs.saturation,
            hue = prefs.hue,
            palette = prefs.palette,
            updated_at = System.currentTimeMillis(),
        )
    }

    override fun clearMangaPrefs(mangaId: String) {
        database.mangaPrefsQueries.deleteByMangaId(mangaId)
    }

    // MARK: - favourite categories

    override fun favouriteCategories(): List<FavouriteCategoryRow> {
        return database.favouriteCategoryQueries.selectAllCategories().executeAsList().map { row ->
            FavouriteCategoryRow(
                id = row.id,
                title = row.title,
                sortKey = row.sort_key.toInt(),
                createdAt = row.created_at,
                mangaCount = row.manga_count.toInt(),
            )
        }
    }

    override fun favouritesIn(categoryId: Long): List<Manga> {
        return database.favouriteCategoryQueries.selectFavouritesByCategory(categoryId).executeAsList().map { row ->
            mangaRowToDomain(
                id = row.id, title = row.title, alt_titles = row.alt_titles,
                url = row.url, public_url = row.public_url, rating = row.rating,
                is_nsfw = row.is_nsfw, content_rating = row.content_rating,
                cover_url = row.cover_url, large_cover_url = row.large_cover_url,
                state = row.state, authors = row.authors, source_ref = row.source_ref,
                description = row.description, tags = row.tags, chapters = row.chapters,
                unread = row.unread, progress = row.progress,
            )
        }
    }

    override fun categoriesForManga(mangaId: String): List<FavouriteCategoryRow> {
        return database.favouriteCategoryQueries.selectCategoriesForManga(mangaId).executeAsList().map { row ->
            FavouriteCategoryRow(
                id = row.id,
                title = row.title,
                sortKey = row.sort_key.toInt(),
                createdAt = row.created_at,
                mangaCount = 0,
            )
        }
    }

    override fun createCategory(title: String): Long {
        val now = System.currentTimeMillis()
        val nextSort = (database.favouriteCategoryQueries.selectAllCategories().executeAsList().maxOfOrNull { it.sort_key } ?: 0L) + 1L
        database.favouriteCategoryQueries.insertCategory(title.trim(), nextSort, now)
        triggerPush()
        return database.favouriteCategoryQueries.selectAllCategories().executeAsList()
            .firstOrNull { it.title == title.trim() && it.created_at == now }?.id ?: -1L
    }

    override fun renameCategory(id: Long, title: String) {
        database.favouriteCategoryQueries.renameCategory(title.trim(), id)
        triggerPush()
    }

    override fun deleteCategory(id: Long) {
        database.favouriteCategoryQueries.deleteCategory(id)
        triggerPush()
    }

    override fun addToCategory(mangaId: String, categoryId: Long) {
        database.favouriteCategoryQueries.addMangaToCategory(mangaId, categoryId)
        triggerPush()
    }

    override fun removeFromCategory(mangaId: String, categoryId: Long) {
        database.favouriteCategoryQueries.removeMangaFromCategory(mangaId, categoryId)
        triggerPush()
    }

    // MARK: - updates

    override fun updates(): List<UpdateRow> {
        return database.mangaUpdateQueries.selectAllWithNew().executeAsList().map { row ->
            UpdateRow(
                mangaId = row.manga_id,
                mangaTitle = row.manga_title,
                mangaCoverUrl = row.manga_cover_url,
                sourceId = row.source_id,
                newChapters = row.new_chapters_count.toInt(),
                totalChapters = row.last_chapter_count.toInt(),
                latestChapterTitle = row.latest_chapter_title,
                lastSyncedAt = row.last_synced_at,
            )
        }
    }

    override fun recordUpdateSync(
        mangaId: String,
        sourceId: String,
        currentChapterCount: Int,
        latestChapterTitle: String,
    ) {
        val existing = database.mangaUpdateQueries.selectByMangaId(mangaId).executeAsOneOrNull()
        val previousCount = existing?.last_chapter_count?.toInt() ?: -1
        val diff = if (previousCount < 0) 0 else (currentChapterCount - previousCount).coerceAtLeast(0)
        val accumulated = (existing?.new_chapters_count?.toInt() ?: 0) + diff
        database.mangaUpdateQueries.upsert(
            manga_id = mangaId,
            source_id = sourceId,
            last_chapter_count = currentChapterCount.toLong(),
            new_chapters_count = accumulated.toLong(),
            latest_chapter_title = latestChapterTitle,
            last_synced_at = System.currentTimeMillis(),
            updated_at = System.currentTimeMillis(),
        )
        triggerPush()
    }

    override fun markUpdatesSeen(mangaId: String) {
        database.mangaUpdateQueries.markSeen(mangaId)
        triggerPush()
    }

    override fun markAllUpdatesSeen() {
        database.mangaUpdateQueries.markAllSeen()
        triggerPush()
    }

    // MARK: - tracking (per-service link + live state, canonical schema)

    override fun allTracking(): List<TrackingRow> {
        return database.trackingQueries.selectAll().executeAsList().map { it.toTrackingRow() }
    }

    /** Live (non-tombstoned) tracking links for a given manga. */
    fun trackingForManga(mangaId: String): List<TrackingRow> {
        return database.trackingQueries.selectForManga(mangaId).executeAsList().map { it.toTrackingRow() }
    }

    override fun saveTracking(row: TrackingRow) {
        database.trackingQueries.upsert(
            tracker_id = row.trackerId,
            manga_id = row.mangaId,
            remote_id = row.remoteId,
            source_id = row.sourceId,
            title = row.title,
            status = row.status,
            score = row.score.toDouble(),
            last_read_chapter = row.lastReadChapter.toDouble(),
            last_read_volume = row.lastReadVolume.toLong(),
            total_chapters = row.totalChapters.toLong(),
            total_volumes = row.totalVolumes.toLong(),
            chapter_offset = row.chapterOffset.toLong(),
            started_at = row.startedAt,
            finished_at = row.finishedAt,
            comment = row.comment,
            updated_at = row.updatedAt.ifBlank { nowIso() },
            deleted_at = row.deletedAt,
        )
        triggerPush()
    }

    /** Soft-delete (tombstone) a tracking link so the deletion propagates on the next push. */
    fun removeTracking(trackerId: String, mangaId: String) {
        val now = nowIso()
        database.trackingQueries.softDelete(deleted_at = now, updated_at = now, tracker_id = trackerId, manga_id = mangaId)
        triggerPush()
    }

    private fun com.nyora.hasan72341.shared.db.Tracking.toTrackingRow(): TrackingRow = TrackingRow(
        trackerId = tracker_id,
        remoteId = remote_id,
        sourceId = source_id,
        mangaId = manga_id,
        title = title,
        status = status,
        score = score.toFloat(),
        lastReadChapter = last_read_chapter.toFloat(),
        lastReadVolume = last_read_volume.toInt(),
        totalChapters = total_chapters.toInt(),
        totalVolumes = total_volumes.toInt(),
        chapterOffset = chapter_offset.toInt(),
        startedAt = started_at,
        finishedAt = finished_at,
        comment = comment,
        updatedAt = updated_at,
        deletedAt = deleted_at,
    )

    private fun nowIso(): String = OffsetDateTime.now(java.time.ZoneOffset.UTC).toInstant().toString()

    override fun hasLocalSyncableData(): Boolean {
        if (database.mangaFavouriteQueries.countAll().executeAsOne() > 0) return true
        if (database.mangaHistoryQueries.countAll().executeAsOne() > 0) return true
        if (database.bookmarkQueries.countAll().executeAsOne() > 0) return true
        if (database.favouriteCategoryQueries.selectAllCategories().executeAsList().isNotEmpty()) return true
        if (database.mangaPrefsQueries.selectAll().executeAsList().isNotEmpty()) return true
        if (database.trackingQueries.countLive().executeAsOne() > 0) return true
        val sources = database.mangaSourceQueries.selectAll().executeAsList()
        if (sources.any { it.is_pinned != 0L }) return true
        return false
    }

    override fun clearDatabase() {
        database.transaction {
            database.favouriteCategoryQueries.deleteAllMangaCategories()
            database.mangaHistoryQueries.deleteAll()
            database.mangaFavouriteQueries.deleteAll()
            database.bookmarkQueries.deleteAll()
            database.favouriteCategoryQueries.deleteAllCategories()
            database.mangaPrefsQueries.deleteAll()
            database.mangaUpdateQueries.deleteAll()
            database.trackingQueries.deleteAll()
            database.mangaQueries.deleteAll()
        }
    }

    override fun nyoraSyncSignIn(email: String, password: String): String? {
        val sync = nyoraSync ?: return "Sync unavailable"
        return sync.signIn(email, password).fold(
            onSuccess = { null },
            onFailure = { it.message ?: "Sign-in failed" },
        )
    }

    override fun nyoraSyncRegister(email: String, password: String): String? {
        val sync = nyoraSync ?: return "Sync unavailable"
        return sync.register(email, password).fold(
            onSuccess = { null },
            onFailure = { it.message ?: "Registration failed" },
        )
    }

    override fun nyoraSyncNow() {
        nyoraSync?.syncNow()
    }

    override fun nyoraSyncRestoreFromCloud() {
        nyoraSync?.restoreFromCloud()
    }

    override fun nyoraSyncSignOut() {
        nyoraSync?.signOut()
    }

    fun allFavouritesIncludingDeleted(): List<com.nyora.hasan72341.shared.db.Manga_favourite> {
        return database.mangaFavouriteQueries.selectAllIncludingDeleted().executeAsList()
    }

    fun allHistoryIncludingDeleted(): List<com.nyora.hasan72341.shared.db.Manga_history> {
        return database.mangaHistoryQueries.selectAllIncludingDeleted().executeAsList()
    }

    fun allBookmarksIncludingDeleted(): List<com.nyora.hasan72341.shared.db.Bookmark> {
        return database.bookmarkQueries.selectAllIncludingDeleted().executeAsList()
    }

    fun allUpdatesIncludingDeleted(): List<com.nyora.hasan72341.shared.db.Manga_update> {
        return database.mangaUpdateQueries.selectAllIncludingDeleted().executeAsList()
    }

    private fun mangaRowToDomain(
        id: String, title: String, alt_titles: String,
        url: String, public_url: String, rating: Double,
        is_nsfw: Long, content_rating: String?,
        cover_url: String, large_cover_url: String?,
        state: String?, authors: String, source_ref: String,
        description: String, tags: String, chapters: String,
        unread: Long, progress: Double,
    ): Manga {
        val tagSer = ListSerializer(MangaTag.serializer())
        val chapterSer = ListSerializer(MangaChapter.serializer())
        val stringList = ListSerializer(serializer<String>())
        return Manga(
            id = id,
            title = title,
            altTitles = decodeList(alt_titles, stringList),
            url = url,
            publicUrl = public_url,
            rating = rating.toFloat(),
            isNsfw = is_nsfw != 0L,
            contentRating = content_rating?.let { runCatching { com.nyora.hasan72341.shared.model.ContentRating.valueOf(it) }.getOrNull() },
            coverUrl = cover_url,
            largeCoverUrl = large_cover_url,
            state = state?.let { runCatching { com.nyora.hasan72341.shared.model.MangaState.valueOf(it) }.getOrNull() },
            authors = decodeList(authors, stringList),
            source = MangaSourceRefCodec.decode(source_ref),
            description = description,
            tags = decodeList(tags, tagSer),
            chapters = decodeList(chapters, chapterSer),
            unread = unread.toInt(),
            progress = progress.toFloat(),
        )
    }

    // MARK: - writers

    private fun writeManga(manga: Manga) {
        val tagSer = ListSerializer(MangaTag.serializer())
        val chapterSer = ListSerializer(MangaChapter.serializer())
        val stringList = ListSerializer(serializer<String>())
        database.mangaQueries.upsert(
            id = manga.id,
            title = manga.title,
            alt_titles = json.encodeToString(stringList, manga.altTitles),
            url = manga.url,
            public_url = manga.publicUrl,
            rating = manga.rating.toDouble(),
            is_nsfw = manga.isNsfw.toLong(),
            content_rating = manga.contentRating?.name,
            cover_url = manga.coverUrl,
            large_cover_url = manga.largeCoverUrl,
            state = manga.state?.name,
            authors = json.encodeToString(stringList, manga.authors),
            source_ref = MangaSourceRefCodec.encode(manga.source),
            description = manga.description,
            tags = json.encodeToString(tagSer, manga.tags),
            chapters = json.encodeToString(chapterSer, manga.chapters),
            unread = manga.unread.toLong(),
            progress = manga.progress.toDouble(),
            updated_at = System.currentTimeMillis(),
        )
    }

    private fun writeSource(source: MangaSource) {
        database.mangaSourceQueries.upsert(
            id = source.id,
            name = source.name,
            lang = source.lang,
            base_url = source.baseUrl,
            package_name = source.packageName,
            source_code_url = source.sourceCodeUrl,
            icon_url = source.iconUrl,
            version = source.version,
            version_code = source.versionCode,
            is_installed = source.isInstalled.toLong(),
            is_pinned = source.isPinned.toLong(),
            is_nsfw = source.isNsfw.toLong(),
            is_obsolete = source.isObsolete.toLong(),
            engine = source.engine.name,
            content_type = source.contentType.name,
            notes = source.notes,
            local_path = source.localPath,
            installed_at = source.installedAt,
            can_uninstall = source.canUninstall.toLong(),
        )
    }

    private fun parseNyoraSyncTimestampMillis(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
    }

    // MARK: - readers

    private fun com.nyora.hasan72341.shared.db.Manga.toManga(): Manga {
        val tagSer = ListSerializer(MangaTag.serializer())
        val chapterSer = ListSerializer(MangaChapter.serializer())
        val stringList = ListSerializer(serializer<String>())
        return Manga(
            id = id,
            title = title,
            altTitles = decodeList(alt_titles, stringList),
            url = url,
            publicUrl = public_url,
            rating = rating.toFloat(),
            isNsfw = is_nsfw != 0L,
            contentRating = content_rating?.let { runCatching { com.nyora.hasan72341.shared.model.ContentRating.valueOf(it) }.getOrNull() },
            coverUrl = cover_url,
            largeCoverUrl = large_cover_url,
            state = state?.let { runCatching { MangaState.valueOf(it) }.getOrNull() },
            authors = decodeList(authors, stringList),
            source = MangaSourceRefCodec.decode(source_ref),
            description = description,
            tags = decodeList(tags, tagSer),
            chapters = decodeList(chapters, chapterSer),
            unread = unread.toInt(),
            progress = progress.toFloat(),
        )
    }

    private fun com.nyora.hasan72341.shared.db.Manga_source.toMangaSource(): MangaSource = MangaSource(
        id = id,
        name = name,
        lang = lang,
        baseUrl = base_url,
        packageName = package_name,
        sourceCodeUrl = source_code_url,
        iconUrl = icon_url,
        version = version,
        versionCode = version_code,
        isInstalled = is_installed != 0L,
        isPinned = is_pinned != 0L,
        isNsfw = is_nsfw != 0L,
        isObsolete = is_obsolete != 0L,
        engine = runCatching { SourceEngine.valueOf(engine) }.getOrDefault(SourceEngine.Mihon),
        contentType = runCatching { SourceContentType.valueOf(content_type) }
            .getOrDefault(SourceContentType.Manga),
        notes = notes,
        localPath = local_path,
        installedAt = installed_at,
        canUninstall = can_uninstall != 0L,
    )

    private fun <T> decodeList(raw: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> {
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L

    companion object {
        // 7 days
        private const val CACHE_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

        fun defaultDatabasePath(): Path {
            // Allow explicit override via system property (e.g. set by HelperLauncher on Windows).
            System.getProperty("nyora.data.dir")?.takeIf { it.isNotBlank() }?.let {
                val dir = Path.of(it)
                Files.createDirectories(dir)
                return dir.resolve("nyora.db")
            }
            val osName = System.getProperty("os.name", "").lowercase()
            val home = Path.of(System.getProperty("user.home"))
            val base = when {
                osName.contains("win") -> {
                    val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                        ?: home.resolve("AppData").resolve("Roaming").toString()
                    Path.of(appData, "Nyora")
                }
                osName.contains("mac") ->
                    home.resolve("Library").resolve("Application Support").resolve("Nyora")
                else -> {
                    val xdgData = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
                        ?: home.resolve(".local").resolve("share")
                    xdgData.resolve("nyora")
                }
            }
            Files.createDirectories(base)
            return base.resolve("nyora.db")
        }
    }
}

internal fun MangaRepo.identity(): String = "$name|$indexUrl"
