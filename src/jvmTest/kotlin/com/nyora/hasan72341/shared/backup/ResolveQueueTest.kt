package com.nyora.hasan72341.shared.backup

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveQueueTest {

    private fun queue() = ResolveQueue(createTempDirectory("nyora-queue").resolve("q.json"))

    @Test
    fun `entries persist and reload from disk`() {
        val path = createTempDirectory("nyora-queue").resolve("q.json")
        ResolveQueue(path).enqueue(
            listOf(ResolveEntry(mangaId = "a", title = "A", targetSourceId = "parser:X")),
        )
        // A fresh instance must see the file, not an empty cache.
        val reloaded = ResolveQueue(path).load()
        assertEquals(1, reloaded.size)
        assertEquals("A", reloaded.first().title)
    }

    @Test
    fun `enqueue replaces an existing entry for the same manga`() {
        val q = queue()
        q.enqueue(listOf(ResolveEntry(mangaId = "a", title = "old")))
        q.enqueue(listOf(ResolveEntry(mangaId = "a", title = "new")))
        assertEquals(1, q.load().size)
        assertEquals("new", q.load().first().title)
    }

    @Test
    fun `unmatched entries stay pending so they can be retried later`() {
        // The resolver re-matches on each attempt, so an entry with no source yet
        // must stay queued rather than being dropped.
        val q = queue()
        q.enqueue(
            listOf(
                ResolveEntry(mangaId = "a", title = "A", targetSourceId = "", originSourceName = "SomeSite"),
                ResolveEntry(mangaId = "b", title = "B", targetSourceId = "parser:X"),
            ),
        )
        assertEquals(2, q.pending().size)
    }

    @Test
    fun `entries retire after the attempt cap`() {
        val q = queue()
        q.enqueue(listOf(ResolveEntry(mangaId = "a", title = "A")))
        repeat(ResolveQueue.MAX_ATTEMPTS) { i ->
            q.update(q.load().first().copy(attempts = i + 1))
        }
        assertTrue(q.pending().isEmpty())
        // The row itself is kept for diagnostics.
        assertEquals(1, q.load().size)
    }

    @Test
    fun `update and remove keep the in-memory cache and the file in step`() {
        val path = createTempDirectory("nyora-queue").resolve("q.json")
        val q = ResolveQueue(path)
        q.enqueue(listOf(ResolveEntry(mangaId = "a", title = "A"), ResolveEntry(mangaId = "b", title = "B")))
        q.update(ResolveEntry(mangaId = "a", title = "A2", lastError = "boom"))
        q.remove(listOf("b"))

        assertEquals(listOf("a"), q.load().map { it.mangaId })
        assertEquals("A2", q.load().first().title)
        // Re-read from disk: the cache must have been written through, not just mutated.
        assertEquals(listOf("a"), ResolveQueue(path).load().map { it.mangaId })
        assertEquals("boom", ResolveQueue(path).load().first().lastError)
    }

    @Test
    fun `a corrupt queue file degrades to empty instead of throwing`() {
        val path = createTempDirectory("nyora-queue").resolve("q.json")
        path.toFile().writeText("{ not valid json")
        assertTrue(ResolveQueue(path).load().isEmpty())
    }

    @Test
    fun `clear empties the queue`() {
        val q = queue()
        q.enqueue(listOf(ResolveEntry(mangaId = "a", title = "A")))
        q.clear()
        assertTrue(q.load().isEmpty())
    }
}
