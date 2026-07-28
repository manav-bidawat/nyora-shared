package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.data.JsonStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class ResolveEntry(
    /** The synthetic Nyora manga id the importer created. */
    val mangaId: String,
    val title: String,
    /** Nyora source to search, or empty when no parser matched the Mihon source. */
    val targetSourceId: String = "",
    val originSourceName: String = "",
    /** Failed attempts so far; entries stop being retried past [MAX_ATTEMPTS]. */
    val attempts: Int = 0,
    val lastError: String = "",
)

/**
 * Durable list of imported Mihon entries still waiting to be bound to a real
 * Nyora source.
 *
 * Kept as a plain file next to the library rather than in the database: it is
 * transient migration state, it must never sync to other devices, and it must not
 * require a schema migration on existing installs.
 */
class ResolveQueue(
    private val file: Path = JsonStore.defaultStorePath().parent.resolve("mihon-resolve-queue.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Any()

    /** In-memory mirror of the file; an eager pass writes once per entry. */
    private var cache: MutableList<ResolveEntry>? = null

    fun load(): List<ResolveEntry> = synchronized(lock) { cached().toList() }

    private fun cached(): MutableList<ResolveEntry> {
        cache?.let { return it }
        val loaded = if (!file.exists()) {
            mutableListOf()
        } else {
            runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ResolveEntry.serializer()),
                    file.readText(),
                ).toMutableList()
            }.getOrDefault(mutableListOf())
        }
        cache = loaded
        return loaded
    }

    /** Adds entries, replacing any queued entry for the same manga. */
    fun enqueue(entries: List<ResolveEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val merged = LinkedHashMap<String, ResolveEntry>()
            cached().forEach { merged[it.mangaId] = it }
            entries.forEach { merged[it.mangaId] = it }
            write(merged.values.toList())
        }
    }

    fun remove(mangaIds: Collection<String>) {
        if (mangaIds.isEmpty()) return
        synchronized(lock) {
            val drop = mangaIds.toSet()
            write(cached().filterNot { it.mangaId in drop })
        }
    }

    fun update(entry: ResolveEntry) = synchronized(lock) {
        write(cached().map { if (it.mangaId == entry.mangaId) entry else it })
    }

    fun clear() = synchronized(lock) { write(emptyList()) }

    /**
     * Entries still worth retrying, including those with no matched source: the
     * resolver re-runs the source match on each attempt, so an entry starts
     * resolving once a matching source is enabled.
     */
    fun pending(): List<ResolveEntry> = synchronized(lock) {
        cached().filter { it.attempts < MAX_ATTEMPTS }
    }

    private fun write(entries: List<ResolveEntry>) {
        cache = entries.toMutableList()
        file.parent.createDirectories()
        file.writeText(
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ResolveEntry.serializer()),
                entries,
            ),
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
