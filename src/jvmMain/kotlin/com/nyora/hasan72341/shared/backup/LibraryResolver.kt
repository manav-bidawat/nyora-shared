package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaSourceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ResolveProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val bound: Int = 0,
    val failed: Int = 0,
    val remaining: Int = 0,
)

/**
 * Rebinds imported Mihon entries onto real Nyora sources.
 *
 * A Mihon URL is meaningless to a Nyora parser even for the same website, so the
 * only portable handle is the title. For each queued entry we search its matched
 * Nyora source, pick a confident title match, and swap the placeholder entry for
 * the real one — carrying favourite state, categories, history and bookmarks
 * across to the new id.
 *
 * Runs in two modes, both over the same [ResolveQueue]:
 *  - [resolveOne] — lazy, called when the user opens an unresolved entry.
 *  - [start] — eager background pass over everything, driven by the user.
 *
 * Nothing here is destructive: a failed resolve leaves the placeholder in place
 * with its progress intact and simply increments the attempt counter.
 */
class LibraryResolver(
    private val facade: NyoraFacade,
    private val queue: ResolveQueue,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val done = AtomicInteger(0)
    private val bound = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    @Volatile private var total = 0

    fun progress(): ResolveProgress = ResolveProgress(
        running = running.get(),
        total = total,
        done = done.get(),
        bound = bound.get(),
        failed = failed.get(),
        remaining = queue.pending().size,
    )

    /** Starts the eager pass. No-op if one is already running. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val pending = queue.pending()
        total = pending.size
        done.set(0); bound.set(0); failed.set(0)

        scope.launch {
            try {
                val snapshot = LibrarySnapshot(facade)
                for (entry in pending) {
                    if (!isActive || !running.get()) break
                    if (resolveEntry(entry, snapshot)) bound.incrementAndGet() else failed.incrementAndGet()
                    done.incrementAndGet()
                    // Sources are rate-limited and several sit behind Cloudflare;
                    // pace the pass so a large library never looks like an attack.
                    delay(REQUEST_SPACING_MS)
                }
                // One sync run at the end rather than per entry.
                runCatching { facade.nyoraSyncNow() }
            } finally {
                running.set(false)
            }
        }
        return true
    }

    fun stop() {
        running.set(false)
    }

    /**
     * Resolves a single entry on demand, e.g. when the user taps an unresolved
     * title. Returns the bound manga id, or null if it could not be resolved.
     */
    fun resolveOne(mangaId: String): String? {
        val entry = queue.load().firstOrNull { it.mangaId == mangaId } ?: return null
        var resolvedId: String? = null
        runBlocking {
            resolveEntry(entry, LibrarySnapshot(facade)) { resolvedId = it.id }
        }
        return resolvedId
    }

    private suspend fun resolveEntry(
        entry: ResolveEntry,
        snapshot: LibrarySnapshot,
        onBound: (Manga) -> Unit = {},
    ): Boolean {
        val sources = facade.listSources()
        // An entry whose Mihon source had no Nyora parser at import time gets a
        // second chance here: the user may have enabled a matching source since.
        val targetId = entry.targetSourceId.ifEmpty {
            MihonSourceMapper(sources, MihonSourceBridge.map).match(entry.originSourceName)?.id.orEmpty()
        }
        val source = sources.firstOrNull { it.id == targetId }
        if (source == null) {
            queue.update(entry.copy(attempts = entry.attempts + 1, lastError = "source unavailable"))
            return false
        }

        val match = try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                val service = facade.openExtension(source)
                val results = service.search(entry.title, page = 1).entries
                bestMatch(entry.title, results)
            }
        } catch (e: Throwable) {
            queue.update(entry.copy(attempts = entry.attempts + 1, lastError = e.message ?: "search failed"))
            return false
        }

        if (match == null) {
            queue.update(entry.copy(attempts = entry.attempts + 1, lastError = "no confident title match"))
            return false
        }

        rebind(entry, match, snapshot)
        onBound(match)
        queue.remove(listOf(entry.mangaId))
        return true
    }

    /**
     * Moves everything the user cares about from the placeholder id onto the real
     * entry, then retires the placeholder. Order matters: write the new rows
     * before dropping the old ones so a crash mid-way loses nothing.
     */
    private fun rebind(entry: ResolveEntry, resolved: Manga, snapshot: LibrarySnapshot) {
        val oldId = entry.mangaId
        val newId = resolved.id
        if (oldId == newId) return

        facade.upsertManga(resolved)
        // Rows store the colon-namespaced source id, never the ref name.
        val sourceId = MihonSourceIds.sourceIdFor(resolved.source)

        if (facade.isFavourited(oldId) && !facade.isFavourited(newId)) {
            facade.toggleFavourite(newId)
        }

        // Everything but the "Missing Source" bucket: this entry is no longer
        // missing, and the category must not accumulate resolved entries.
        val missingSourceId = facade.favouriteCategories()
            .firstOrNull { it.title == MihonBackupImporter.MISSING_SOURCE_CATEGORY }
            ?.id
        for (category in facade.categoriesForManga(oldId)) {
            if (category.id == missingSourceId) continue
            facade.addToCategory(newId, category.id)
        }
        if (missingSourceId != null) {
            runCatching { facade.removeFromCategory(oldId, missingSourceId) }
        }

        for (h in snapshot.historyFor(oldId)) {
            facade.recordHistory(newId, sourceId, h.chapterId, h.chapterTitle, h.page, h.percent)
        }

        for (b in snapshot.bookmarksFor(oldId)) {
            if (!facade.isPageBookmarked(newId, b.chapterId, b.page)) {
                facade.addBookmark(newId, b.chapterId, b.chapterTitle, b.page, b.note)
            }
        }

        facade.mangaPrefs(oldId)?.let { facade.saveMangaPrefs(it.copy(mangaId = newId)) }

        for (t in snapshot.trackingFor(oldId)) {
            facade.saveTracking(t.copy(mangaId = newId, sourceId = sourceId))
        }

        // Retire the placeholder: unfavourite it (a soft-delete tombstone, so the
        // removal propagates to other devices) and drop its history so it stops
        // showing up. The orphan manga row is harmless and costs one DB entry.
        if (facade.isFavourited(oldId)) facade.toggleFavourite(oldId)
        runCatching { facade.removeHistory(oldId) }
        runCatching { facade.clearMangaPrefs(oldId) }
        snapshot.forget(oldId)
    }

    /**
     * Only accepts a confident match. An exact normalised title wins outright;
     * otherwise we require a containment match on a title long enough that a
     * false positive is unlikely. Anything vaguer is left for the user.
     */
    private fun bestMatch(title: String, candidates: List<Manga>): Manga? {
        if (candidates.isEmpty()) return null
        val target = normalise(title)
        if (target.isEmpty()) return null

        candidates.firstOrNull { normalise(it.title) == target }?.let { return it }

        candidates.firstOrNull {
            val other = normalise(it.title)
            other.isNotEmpty() && target.length >= MIN_FUZZY_TITLE &&
                (other.contains(target) || target.contains(other))
        }?.let { return it }

        // Fall back to the alternative titles the source reports.
        return candidates.firstOrNull { candidate ->
            candidate.altTitles.any { normalise(it) == target }
        }
    }

    private fun normalise(raw: String): String =
        raw.lowercase().replace(NON_ALNUM, "")

    private companion object {
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val REQUEST_SPACING_MS = 750L
        const val MIN_FUZZY_TITLE = 8
        val NON_ALNUM = Regex("""[^a-z0-9]""")
    }
}

/**
 * One-shot index of the rows a rebind needs, keyed by manga id.
 *
 * Without this each rebind re-read the entire history, bookmark and tracking
 * tables, making an eager pass over a large imported library quadratic. The
 * placeholder rows being moved are only touched once, so a snapshot taken at the
 * start of a pass stays correct as long as retired ids are dropped via [forget].
 */
internal class LibrarySnapshot(facade: NyoraFacade) {
    private val history = facade.history(limit = SCAN_LIMIT).groupBy { it.manga.id }.toMutableMap()
    private val bookmarks = facade.bookmarks().groupBy { it.mangaId }.toMutableMap()
    private val tracking = facade.allTracking().groupBy { it.mangaId }.toMutableMap()

    fun historyFor(mangaId: String) = history[mangaId].orEmpty()

    fun bookmarksFor(mangaId: String) = bookmarks[mangaId].orEmpty()

    fun trackingFor(mangaId: String) = tracking[mangaId].orEmpty()

    fun forget(mangaId: String) {
        history -= mangaId
        bookmarks -= mangaId
        tracking -= mangaId
    }

    private companion object {
        const val SCAN_LIMIT = 100_000
    }
}
