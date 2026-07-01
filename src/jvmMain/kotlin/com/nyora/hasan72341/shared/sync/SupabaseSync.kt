package com.nyora.hasan72341.shared.sync

import com.nyora.hasan72341.shared.model.MangaSourceRef
import com.nyora.hasan72341.shared.model.MangaSourceRefCodec
import com.nyora.hasan72341.shared.model.SourceEngine
import com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.nio.file.Path
import java.time.Instant

/**
 * Pushes / pulls Nyora library data through the nyora-sync Supabase Edge Function.
 *
 * Sync strategy: push-then-pull, last-write-wins via updated_at.
 * Call [syncAll] on a background thread (it blocks on HTTP).
 *
 * NOT synced (local-only): manga_source (install state), chapter_pages (cache).
 *
 * Platforms covered: mac, linux, windows, web — all share this jvmMain module.
 */
class SupabaseSync(
    private val repo: SqlDelightLibraryRepository,
    private val dataDir: Path,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncFunctionUrl get() = "${SupabaseConfig.url}/functions/v1/nyora-sync"
    private val INITIAL_SYNC_TIMESTAMP = "1970-01-01T00:00:00Z"
    private var pulledMangaRows: Map<String, SbManga> = emptyMap()
    private var pulledMangaSourceNames: Map<String, String> = emptyMap()

    private fun debug(msg: String) {
        val logFile = dataDir.resolve("auth_debug.log")
        val text = "[${java.time.Instant.now()}] $msg\n"
        runCatching {
            java.nio.file.Files.write(logFile, text.toByteArray(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        }
    }

    // ── Auth ────────────────────────────────────────────────────────────────

    private fun applyAuth(text: String) {
        val r = json.decodeFromString<AuthResponse>(text)
        val previousUserId = SupabaseConfig.userId
        val newUserId = SupabaseConfig.parseUserIdFromJwt(r.access_token)
        val parsedEmail = SupabaseConfig.parseEmailFromJwt(r.access_token)
        SupabaseConfig.accessToken = r.access_token
        SupabaseConfig.refreshToken = r.refresh_token ?: ""
        SupabaseConfig.userId = newUserId
        if (parsedEmail.isNotBlank()) SupabaseConfig.email = parsedEmail
        if (previousUserId.isNotBlank() && previousUserId != newUserId) {
            SupabaseConfig.lastSyncTimestamp = INITIAL_SYNC_TIMESTAMP
        }
        SupabaseConfig.saveTokens(dataDir)
    }

    /** OAuth2 password grant (form-encoded) → POST /auth/token. */
    fun signIn(email: String, password: String): Result<Unit> {
        if (!SupabaseConfig.isConfigured) return Result.failure(IllegalStateException("Sync server not configured"))
        val body = okhttp3.FormBody.Builder()
            .add("grant_type", "password")
            .add("username", email.trim())
            .add("password", password)
            .build()
        val req = Request.Builder().url("${SupabaseConfig.url}/auth/token").post(body).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: error("Empty body")
                check(resp.isSuccessful) { "Sign-in failed ${resp.code}: $text" }
                applyAuth(text)
            }
        }
    }

    /** Create an account → POST /auth/register {email,password}; returns tokens on success. */
    fun register(email: String, password: String): Result<Unit> {
        if (!SupabaseConfig.isConfigured) return Result.failure(IllegalStateException("Sync server not configured"))
        val body = json.encodeToString(mapOf("email" to email.trim(), "password" to password)).toRequestBody(JSON_MT)
        val req = Request.Builder().url("${SupabaseConfig.url}/auth/register").post(body).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: error("Empty body")
                check(resp.isSuccessful) { "Registration failed ${resp.code}: $text" }
                applyAuth(text)
            }
        }
    }

    fun refreshToken(): Boolean {
        if (!SupabaseConfig.isConfigured || SupabaseConfig.refreshToken.isBlank()) return false
        return runCatching {
            val body = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", SupabaseConfig.refreshToken)
                .build()
            val req = Request.Builder().url("${SupabaseConfig.url}/auth/token").post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@use false
                if (!resp.isSuccessful) return@use false
                applyAuth(text)
                true
            }
        }.getOrDefault(false)
    }

    fun signOut() {
        SupabaseConfig.clearTokens(dataDir)
    }

    // ── Push ────────────────────────────────────────────────────────────────

    fun syncNow() {
        if (!SupabaseConfig.isAuthenticated) return
        refreshToken()
        val isBootstrap = SupabaseConfig.lastSyncTimestamp == INITIAL_SYNC_TIMESTAMP
        val cutoff = if (isBootstrap) 0L else Instant.parse(SupabaseConfig.lastSyncTimestamp).toEpochMilli()
        pushAll(cutoff)
        pullAll(if (isBootstrap) INITIAL_SYNC_TIMESTAMP else SupabaseConfig.lastSyncTimestamp)
    }

    fun restoreFromCloud() {
        if (!SupabaseConfig.isAuthenticated) return
        refreshToken()
        repo.clearDatabase()
        pullAll(INITIAL_SYNC_TIMESTAMP)
    }

    /** Push all library tables to Supabase. Runs on the caller's thread — use a background executor. */
    fun pushAll() {
        if (!SupabaseConfig.isAuthenticated) return
        val cutoff = if (SupabaseConfig.lastSyncTimestamp == INITIAL_SYNC_TIMESTAMP) 0L 
                    else Instant.parse(SupabaseConfig.lastSyncTimestamp).toEpochMilli()
        pushAll(cutoff)
    }

    private fun pushAll(cutoff: Long) {
        pushFavourites(cutoff)
        pushHistory(cutoff)
        pushBookmarks(cutoff)
        pushMangaPrefs()
        pushCategories()
        pushMangaCategories()
        pushUpdates(cutoff)
        pushSourcePrefs()
    }

    private fun pushFavourites(cutoff: Long) {
        val favs = repo.allFavouritesIncludingDeleted()
        if (favs.isEmpty()) return
        val uid = SupabaseConfig.userId
        val now = Instant.now().toString()
        val rows = mutableListOf<SbFavourite>()
        val mangaRows = mutableListOf<SbManga>()
        val mangas = repo.load().mangas.associateBy { it.id }
        for (f in favs) {
            if (f.added_at <= cutoff && (f.deleted_at ?: 0L) <= cutoff) continue
            rows += SbFavourite(
                user_id = uid, 
                manga_id = f.manga_id,
                sort_key = f.sort_key.toInt(),
                deleted_at = f.deleted_at?.let { Instant.ofEpochMilli(it).toString() },
                updated_at = now
            )
            mangas[f.manga_id]?.toRemoteManga(uid, now)?.let { mangaRows += it }
        }
        if (rows.isEmpty()) return
        upsert("nyora_manga", mangaRows.distinctBy { it.id })
        upsert("nyora_favourite", rows)
    }

    // ── Cross-platform source_id normalization ───────────────────────────────
    // Desktop namespaces parser sources locally as "parser:<NAME>", but the sync
    // wire format is the BARE enum name <NAME> — matching Android and the already-
    // unified `source_ref` JSON ({"name":"<NAME>"}). Strip "parser:" when writing,
    // re-add it on read. Other source types (mihon:/local:/script:) keep their prefix.
    private fun String.toWireSourceId(): String = removePrefix("parser:")

    private fun String.fromWireSourceId(): String =
        if (isBlank() || contains(':')) this else "parser:$this"

    private fun pushHistory(cutoff: Long) {
        val history = repo.allHistoryIncludingDeleted()
        if (history.isEmpty()) return
        val uid = SupabaseConfig.userId
        val now = Instant.now().toString()
        val rows = mutableListOf<SbHistory>()
        val mangaRows = mutableListOf<SbManga>()
        val mangas = repo.load().mangas.associateBy { it.id }
        for (h in history) {
            if (h.updated_at <= cutoff && (h.deleted_at ?: 0L) <= cutoff) continue
            val updatedAt = Instant.ofEpochMilli(h.updated_at).toString()
            rows += SbHistory(
                user_id = uid, manga_id = h.manga_id,
                source_id = h.source_id.toWireSourceId(), chapter_id = h.chapter_id,
                chapter_title = h.chapter_title, page = h.page.toInt(),
                percent = h.percent.toDouble(),
                updated_at = updatedAt,
                deleted_at = h.deleted_at?.let { Instant.ofEpochMilli(it).toString() }
            )
            mangas[h.manga_id]?.toRemoteManga(uid, updatedAt)?.let { mangaRows += it }
        }
        if (rows.isEmpty()) return
        upsert("nyora_manga", mangaRows.distinctBy { it.id })
        upsert("nyora_history", rows)
    }

    private fun pushBookmarks(cutoff: Long) {
        val bookmarks = repo.allBookmarksIncludingDeleted()
        if (bookmarks.isEmpty()) return
        val uid = SupabaseConfig.userId
        val now = Instant.now().toString()
        val rows = mutableListOf<SbBookmark>()
        for (b in bookmarks) {
            if (b.created_at <= cutoff && (b.deleted_at ?: 0L) <= cutoff) continue
            rows += SbBookmark(
                user_id = uid,
                id = "${b.manga_id}:${b.chapter_id}:${b.page}",
                manga_id = b.manga_id, chapter_id = b.chapter_id,
                chapter_title = b.chapter_title, page = b.page.toInt(),
                note = b.note,
                created_at = Instant.ofEpochMilli(b.created_at).toString(),
                updated_at = now,
                deleted_at = b.deleted_at?.let { Instant.ofEpochMilli(it).toString() }
            )
        }
        if (rows.isEmpty()) return
        upsert("nyora_bookmark", rows)
    }

    private fun pushMangaPrefs() {
        val prefs = repo.allMangaPrefs()
        if (prefs.isEmpty()) return
        val uid = SupabaseConfig.userId
        val now = Instant.now().toString()
        val rows = prefs.map { p ->
            SbMangaPrefs(
                user_id = uid,
                manga_id = p.mangaId,
                reader_mode = p.readerMode,
                brightness = p.brightness,
                contrast = p.contrast,
                saturation = p.saturation,
                hue = p.hue,
                palette = p.palette,
                updated_at = now
            )
        }
        upsert("nyora_manga_prefs", rows)
    }

    private fun pushCategories() {
        // Include soft-deleted categories so deletions (e.g. duplicate cleanup) propagate.
        val cats = repo.categoriesForSync()
        if (cats.isEmpty()) return
        val uid = SupabaseConfig.userId
        val now = Instant.now().toString()
        val rows = cats.map { c ->
            SbCategory(
                user_id = uid,
                id = c.id.toString(),
                title = c.title,
                sort_key = c.sortKey.toInt(),
                updated_at = now,
                deleted_at = c.deletedAt?.let { Instant.ofEpochMilli(it).toString() },
            )
        }
        upsert("nyora_category", rows)
    }

    private fun pushMangaCategories() {
        val uid = SupabaseConfig.userId
        val now = Instant.now().toString()
        val rows = repo.database.favouriteCategoryQueries.selectAllMangaCategories().executeAsList().map { 
            SbMangaCategory(user_id = uid, manga_id = it.manga_id, category_id = it.category_id.toString(), updated_at = now)
        }
        if (rows.isEmpty()) return
        upsert("nyora_manga_category", rows)
    }

    private fun pushUpdates(cutoff: Long) {
        val updates = repo.allUpdatesIncludingDeleted()
        if (updates.isEmpty()) return
        val uid = SupabaseConfig.userId
        val now = Instant.now().toString()
        val rows = mutableListOf<SbUpdate>()
        for (u in updates) {
            if (u.last_synced_at <= cutoff) continue
            rows += SbUpdate(
                user_id = uid, manga_id = u.manga_id, source_id = u.source_id.toWireSourceId(),
                last_chapter_count = u.last_chapter_count.toInt(), 
                new_chapters_count = u.new_chapters_count.toInt(),
                latest_chapter_title = u.latest_chapter_title,
                last_synced_at = Instant.ofEpochMilli(u.last_synced_at).toString(),
                updated_at = now
            )
        }
        if (rows.isEmpty()) return
        upsert("nyora_update", rows)
    }

    private fun pushSourcePrefs() {
        val uid = SupabaseConfig.userId
        val sources = repo.database.mangaSourceQueries.selectAll().executeAsList()
        if (sources.isEmpty()) return
        val now = Instant.now().toString()
        val rows = sources.map { s ->
            SbSourcePref(
                user_id = uid,
                source_id = s.id.toWireSourceId(),
                is_pinned = s.is_pinned != 0L,
                is_enabled = true,
                updated_at = now
            )
        }
        upsert("nyora_source_prefs", rows)
    }

    // ── Pull ────────────────────────────────────────────────────────────────

    /** Pull all library tables from Supabase and apply locally. */
    fun pullAll() {
        if (!SupabaseConfig.isAuthenticated) return
        pullAll(SupabaseConfig.lastSyncTimestamp)
    }

    private fun pullAll(cutoff: String) {
        debug("Starting cloud pull. cutoff=$cutoff")
        pullManga(cutoff)
        pullFavourites(cutoff)
        pullHistory(cutoff)
        pullBookmarks(cutoff)
        pullCategories(cutoff)
        pullMangaCategories(cutoff)
        pullMangaPrefs(cutoff)
        pullUpdates(cutoff)
        pullSourcePrefs(cutoff)
        SupabaseConfig.lastSyncTimestamp = Instant.now().toString()
        SupabaseConfig.saveTokens(dataDir)
        debug(
            "Cloud pull complete. manga=${repo.load().mangas.size} " +
                "history=${repo.allHistoryIncludingDeleted().size} " +
                "favourites=${repo.allFavouritesIncludingDeleted().size}"
        )
    }

    private fun pullManga(cutoff: String) {
        val text = fetch("nyora_manga", cutoff)
        runCatching {
            val rows = json.decodeFromString<List<SbManga>>(text)
            pulledMangaRows = rows.associateBy { it.id }
            pulledMangaSourceNames = rows.mapNotNull { row ->
                sourceNameFromRef(row.source_ref)?.let { name -> row.id to name }
            }.toMap()
            if (rows.isEmpty()) return@runCatching
            val existingMangas = repo.load().mangas.associateBy { it.id }
            val tagSer = kotlinx.serialization.builtins.ListSerializer(com.nyora.hasan72341.shared.model.MangaTag.serializer())
            val stringList = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
            rows.forEach { row ->
                val existing = existingMangas[row.id]
                val pulledSource = MangaSourceRefCodec.decode(row.source_ref)
                val source = if (pulledSource is MangaSourceRef.Unknown) {
                    existing?.source?.takeUnless { it is MangaSourceRef.Unknown }
                        ?: inferSourceRefForManga(row.id)
                        ?: pulledSource
                } else {
                    pulledSource
                }
                val m = com.nyora.hasan72341.shared.model.Manga(
                    id = row.id,
                    title = row.title,
                    altTitles = runCatching { json.decodeFromString(stringList, row.alt_titles) }.getOrDefault(emptyList()),
                    url = row.url,
                    publicUrl = row.public_url,
                    rating = row.rating.toFloat(),
                    isNsfw = row.is_nsfw,
                    contentRating = row.content_rating?.let { runCatching { com.nyora.hasan72341.shared.model.ContentRating.valueOf(it) }.getOrNull() },
                    coverUrl = row.cover_url,
                    largeCoverUrl = row.large_cover_url,
                    state = row.state?.let { runCatching { com.nyora.hasan72341.shared.model.MangaState.valueOf(it) }.getOrNull() },
                    authors = runCatching { json.decodeFromString(stringList, row.authors) }.getOrDefault(emptyList()),
                    source = source,
                    description = row.description,
                    tags = runCatching { json.decodeFromString(tagSer, row.tags) }.getOrDefault(emptyList()),
                    chapters = existing?.chapters ?: emptyList(),
                    unread = existing?.unread ?: 0,
                    progress = existing?.progress ?: 0f
                )
                repo.upsertManga(m)
            }
        }.onFailure { System.err.println("[SupabaseSync] pullManga failed: ${it.message}") }.getOrThrow()
    }

    private fun pullFavourites(cutoff: String) {
        val text = fetch("nyora_favourite", cutoff)
        runCatching {
            json.decodeFromString<List<SbFavourite>>(text).forEach { row ->
                if (row.deleted_at != null) {
                    repo.database.mangaFavouriteQueries.softDelete(
                        deleted_at = System.currentTimeMillis(),
                        manga_id = row.manga_id
                    )
                } else {
                    if (!repo.isFavourited(row.manga_id)) {
                        val now = System.currentTimeMillis()
                        repo.database.mangaFavouriteQueries.insert(row.manga_id, now, row.sort_key.toLong(), now)
                    }
                }
            }
        }.onFailure { System.err.println("[SupabaseSync] pullFavourites failed: ${it.message}") }.getOrThrow()
    }

    private fun pullHistory(cutoff: String) {
        val text = fetch("nyora_history", cutoff)
        runCatching {
            json.decodeFromString<List<SbHistory>>(text).forEach { row ->
                if (row.deleted_at != null) {
                    repo.database.mangaHistoryQueries.softDelete(
                        deleted_at = System.currentTimeMillis(),
                        manga_id = row.manga_id
                    )
                } else {
                    val sourceId = row.source_id.fromWireSourceId().ifBlank { inferSourceIdForManga(row.manga_id) }
                    repo.upsertHistoryFromSync(
                        mangaId = row.manga_id, sourceId = sourceId,
                        chapterId = row.chapter_id, chapterTitle = row.chapter_title,
                        page = row.page, scroll = row.scroll,
                        percent = row.percent.toFloat(),
                        chaptersCount = row.chapters_count,
                        updatedAt = row.updated_at,
                    )
                }
            }
        }.onFailure { System.err.println("[SupabaseSync] pullHistory failed: ${it.message}") }.getOrThrow()
    }

    private fun pullBookmarks(cutoff: String) {
        val text = fetch("nyora_bookmark", cutoff)
        runCatching {
            json.decodeFromString<List<SbBookmark>>(text).forEach { row ->
                if (row.deleted_at != null) {
                    repo.database.bookmarkQueries.softDelete(
                        deleted_at = System.currentTimeMillis(),
                        manga_id = row.manga_id,
                        chapter_id = row.chapter_id,
                        page = row.page.toLong()
                    )
                } else {
                    if (!repo.isPageBookmarked(row.manga_id, row.chapter_id, row.page)) {
                        repo.addBookmark(
                            mangaId = row.manga_id, chapterId = row.chapter_id,
                            chapterTitle = row.chapter_title, page = row.page, note = row.note,
                        )
                    }
                }
            }
        }.onFailure { System.err.println("[SupabaseSync] pullBookmarks failed: ${it.message}") }.getOrThrow()
    }

    private fun pullCategories(cutoff: String) {
        val text = fetch("nyora_category", cutoff)
        runCatching {
            json.decodeFromString<List<SbCategory>>(text).forEach { row ->
                if (row.deleted_at != null) {
                    repo.database.favouriteCategoryQueries.softDeleteCategory(
                        deleted_at = System.currentTimeMillis(),
                        id = row.id.toLongOrNull() ?: return@forEach
                    )
                } else {
                    val existing = repo.favouriteCategories()
                    if (existing.none { it.title == row.title }) {
                        repo.createCategory(row.title)
                    }
                }
            }
            // Collapse any duplicate-title categories that arrived from legacy per-device seeds.
            repo.dedupeCategories()
        }.onFailure { System.err.println("[SupabaseSync] pullCategories failed: ${it.message}") }.getOrThrow()
    }

    private fun pullUpdates(cutoff: String) {
        val text = fetch("nyora_update", cutoff)
        runCatching {
            json.decodeFromString<List<SbUpdate>>(text).forEach { row ->
                repo.recordUpdateSync(
                    mangaId = row.manga_id, sourceId = row.source_id.fromWireSourceId(),
                    currentChapterCount = row.last_chapter_count,
                    latestChapterTitle = row.latest_chapter_title,
                )
            }
        }.onFailure { System.err.println("[SupabaseSync] pullUpdates failed: ${it.message}") }.getOrThrow()
    }

    private fun pullSourcePrefs(cutoff: String) {
        val text = fetch("nyora_source_prefs", cutoff)
        runCatching {
            val rows = json.decodeFromString<List<SbSourcePref>>(text)
            rows.forEach { row ->
                val localId = row.source_id.fromWireSourceId()
                val existing = repo.database.mangaSourceQueries.selectById(localId).executeAsOneOrNull()
                if (existing != null) {
                    val currentPinned = existing.is_pinned != 0L
                    if (currentPinned != row.is_pinned) {
                        repo.database.mangaSourceQueries.togglePin(localId)
                    }
                }
            }
        }.onFailure { System.err.println("[SupabaseSync] pullSourcePrefs failed: ${it.message}") }.getOrThrow()
    }

    private fun pullMangaCategories(cutoff: String) {
        val text = fetch("nyora_manga_category", cutoff)
        runCatching {
            json.decodeFromString<List<SbMangaCategory>>(text).forEach { row ->
                val catId = row.category_id.toLongOrNull() ?: return@forEach
                if (row.deleted_at != null) {
                    repo.removeFromCategory(row.manga_id, catId)
                } else {
                    repo.addToCategory(row.manga_id, catId)
                }
            }
        }.onFailure { System.err.println("[SupabaseSync] pullMangaCategories failed: ${it.message}") }.getOrThrow()
    }

    private fun pullMangaPrefs(cutoff: String) {
        val text = fetch("nyora_manga_prefs", cutoff)
        runCatching {
            val rows = json.decodeFromString<List<SbMangaPrefs>>(text)
            rows.forEach { row ->
                repo.saveMangaPrefs(
                    com.nyora.hasan72341.shared.repository.MangaPrefsRow(
                        mangaId = row.manga_id,
                        readerMode = row.reader_mode,
                        brightness = row.brightness,
                        contrast = row.contrast,
                        saturation = row.saturation,
                        hue = row.hue,
                        palette = row.palette,
                    )
                )
            }
        }.onFailure { System.err.println("[SupabaseSync] pullMangaPrefs failed: ${it.message}") }.getOrThrow()
    }

    // ── Edge Function Helpers ───────────────────────────────────────────────

    private inline fun <reified T> upsert(table: String, rows: List<T>) {
        if (rows.isEmpty()) return
        runCatching {
            val body = json.encodeToString(UpsertRequest(table = table, rows = rows)).toRequestBody(JSON_MT)
            val req = Request.Builder()
                .url(syncFunctionUrl)
                .header("apikey", SupabaseConfig.anonKey)
                .header("Authorization", "Bearer ${SupabaseConfig.accessToken}")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("[SupabaseSync] upsert $table failed ${resp.code}: ${resp.body?.string()}")
                }
            }
        }.onFailure { System.err.println("[SupabaseSync] upsert $table network error: ${it.message}") }.getOrThrow()
    }

    private fun fetch(table: String, cutoff: String? = null): String = runCatching {
        val request = buildJsonObject {
            put("action", "select")
            put("table", table)
            if (cutoff != null) {
                put("since", cutoff)
            }
        }
        val body = request.toString().toRequestBody(JSON_MT)
        val req = Request.Builder()
            .url(syncFunctionUrl)
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer ${SupabaseConfig.accessToken}")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("[SupabaseSync] fetch $table failed ${resp.code}: ${resp.body?.string()}")
            }
            val text = resp.body?.string() ?: error("[SupabaseSync] fetch $table failed: empty body")
            val obj = json.parseToJsonElement(text).asJsonObject
            obj["data"]?.toString() ?: "[]"
        }
    }.onFailure { System.err.println("[SupabaseSync] fetch $table network error: ${it.message}") }.getOrThrow()

    private val kotlinx.serialization.json.JsonElement.asJsonObject: kotlinx.serialization.json.JsonObject
        get() = this as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())

    private fun sourceNameFromRef(sourceRef: String): String? =
        MangaSourceRefCodec.decode(sourceRef).name.takeIf { it.isNotBlank() && it != MangaSourceRef.Unknown.name }

    private fun inferSourceIdForManga(mangaId: String): String {
        val sources = repo.database.mangaSourceQueries.selectAll().executeAsList()

        val sourceName = pulledMangaSourceNames[mangaId]
        if (!sourceName.isNullOrBlank()) {
            val normalized = sourceName
                .removePrefix("JS_")
                .removePrefix("SCRIPT_")
            val candidates = setOf(
                sourceName,
                normalized,
                "parser:$sourceName",
                "parser:$normalized",
                "script:$sourceName",
                "script:$normalized",
            )
            return sources.firstOrNull { it.id in candidates }?.id
                ?: sources.firstOrNull { it.id.endsWith(":$normalized") }?.id
                ?: ""
        }

        val manga = pulledMangaRows[mangaId] ?: return ""
        val url = listOf(manga.url, manga.public_url).firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?: return ""
        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: return ""
        return sources.firstOrNull { source ->
            val sourceHost = runCatching { URI(source.base_url).host?.removePrefix("www.") }.getOrNull()
            sourceHost != null && (host == sourceHost || host.endsWith(".$sourceHost"))
        }?.id
            ?: ""
    }

    private fun inferSourceRefForManga(mangaId: String): MangaSourceRef? {
        val sourceId = inferSourceIdForManga(mangaId).takeIf { it.isNotBlank() } ?: return null
        val source = repo.database.mangaSourceQueries.selectById(sourceId).executeAsOneOrNull()
        val normalized = sourceId
            .removePrefix("parser:")
            .removePrefix("script:")
            .removePrefix("mihon:")
        val engine = source?.engine?.let { runCatching { SourceEngine.valueOf(it) }.getOrNull() }
        return when (engine) {
            SourceEngine.JavaScript -> MangaSourceRef.Script("JS_$normalized")
            SourceEngine.Parser -> MangaSourceRef.Parser(normalized)
            SourceEngine.Mihon -> MangaSourceRef.Mihon("MIHON_$normalized", normalized.toLongOrNull() ?: 0L)
            SourceEngine.Dart -> null
            null -> MangaSourceRef.Script("JS_$normalized").takeIf { sourceId.startsWith("parser:") || sourceId.startsWith("script:") }
        }
    }

    private fun com.nyora.hasan72341.shared.model.Manga.toRemoteManga(uid: String, updatedAt: String): SbManga? {
        if (source is MangaSourceRef.Unknown) return null
        return SbManga(
            user_id = uid,
            id = id,
            title = title,
            alt_titles = json.encodeToString(altTitles),
            url = url,
            public_url = publicUrl,
            rating = rating.toDouble(),
            is_nsfw = isNsfw,
            content_rating = contentRating?.name,
            cover_url = coverUrl,
            large_cover_url = largeCoverUrl,
            state = state?.name,
            authors = json.encodeToString(authors),
            source_ref = MangaSourceRefCodec.encode(source),
            description = description,
            tags = json.encodeToString(tags),
            updated_at = updatedAt,
        )
    }

    // ── DTOs (match Supabase column names) ───────────────────────────────────

    @Serializable private data class AuthResponse(
        val access_token: String,
        val refresh_token: String? = null,
    )

    @Serializable private data class UpsertRequest<T>(
        val action: String = "upsert",
        val table: String,
        val rows: List<T>,
    )

    @Serializable private data class SbManga(
        val user_id: String = "", val id: String, val title: String,
        val alt_titles: String = "[]", val url: String = "", val public_url: String = "",
        val rating: Double = -1.0, val is_nsfw: Boolean = false,
        val content_rating: String? = null, val cover_url: String = "",
        val large_cover_url: String? = null, val state: String? = null,
        val authors: String = "[]", val source_ref: String = "{}",
        val description: String = "", val tags: String = "[]",
        val updated_at: String? = null,
    )

    @Serializable private data class SbFavourite(
        val user_id: String = "", val manga_id: String,
        val sort_key: Int = 0,
        val updated_at: String? = null,
        val deleted_at: String? = null,
    )

    @Serializable private data class SbHistory(
        val user_id: String = "", val manga_id: String,
        val source_id: String = "", val chapter_id: String = "",
        val chapter_title: String = "", val page: Int = 0,
        val scroll: Double = 0.0, val percent: Double = 0.0,
        val chapters_count: Int = 0, val updated_at: String? = null,
        val deleted_at: String? = null,
    )

    @Serializable private data class SbBookmark(
        val user_id: String = "", val id: String = "", val manga_id: String,
        val chapter_id: String = "", val chapter_title: String = "",
        val page: Int = 0, val scroll: Double = 0.0, val note: String = "",
        val image_url: String = "", val percent: Double = 0.0,
        val created_at: String? = null,
        val updated_at: String? = null,
        val deleted_at: String? = null,
    )

    @Serializable private data class SbCategory(
        val user_id: String = "", val id: String, val title: String,
        val sort_key: Int = 0,
        val updated_at: String? = null,
        val deleted_at: String? = null,
    )

    @Serializable private data class SbMangaCategory(
        val user_id: String = "", val manga_id: String, val category_id: String,
        val updated_at: String? = null,
        val deleted_at: String? = null,
    )

    @Serializable private data class SbUpdate(
        val user_id: String = "", val manga_id: String, val source_id: String = "",
        val last_chapter_count: Int = 0, val new_chapters_count: Int = 0,
        val latest_chapter_title: String = "", val last_synced_at: String? = null,
        val updated_at: String? = null,
    )

    @Serializable private data class SbSourcePref(
        val user_id: String = "",
        val source_id: String,
        val is_pinned: Boolean,
        val is_enabled: Boolean,
        val updated_at: String? = null,
    )

    @Serializable private data class SbMangaPrefs(
        val user_id: String = "",
        val manga_id: String,
        val reader_mode: String = "",
        val brightness: Double = 0.0,
        val contrast: Double = 1.0,
        val saturation: Double = 1.0,
        val hue: Double = 0.0,
        val palette: String = "",
        val updated_at: String? = null,
    )
}
