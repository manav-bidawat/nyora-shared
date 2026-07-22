package com.nyora.hasan72341.shared.proxy

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory response cache for hot, globally-identical responses — the catalog
 * and popular/latest browse lists (same for every user). Designed so users
 * NEVER wait on a cold upstream fetch after the first population:
 *
 *  - **Persistent**: the whole map is snapshotted to ONE file and reloaded on
 *    startup, so a helper restart comes back instantly warm (no cold-fetch
 *    latency). Reads are pure memory; disk is touched only for the snapshot,
 *    off the request hot path (debounced background writer). This is NOT the
 *    old per-request disk sidecar.
 *  - **Serve-stale-while-revalidate**: `peek` returns a value even past its TTL
 *    (marked `stale`) so the handler can respond instantly and refresh in the
 *    background — the user gets an immediate answer every time.
 *
 * Bounded so it can't grow the small VM's heap.
 */
internal object ResponseCache {
    private class Entry(val value: String, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()
    private const val MAX_ENTRIES = 600

    private val snapshotFile: File = File(
        System.getenv("NYORA_CACHE_DIR") ?: System.getProperty("java.io.tmpdir") ?: "/tmp",
        "nyora-response-cache.snap",
    )
    private val loaded = AtomicBoolean(false)
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "nyora-cache-saver").apply { isDaemon = true } }
    @Volatile private var lastSaveAt = 0L
    private const val SAVE_DEBOUNCE_MS = 10_000L

    /** A cache hit + whether it is past its TTL (still served, refreshed async). */
    class Hit(val value: String, val stale: Boolean)

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        try {
            if (!snapshotFile.exists()) return
            // Format per line: <expiresAt>\t<base64(key)>\t<base64(value)>
            snapshotFile.bufferedReader().useLines { lines ->
                val now = System.currentTimeMillis()
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size != 3) continue
                    val exp = parts[0].toLongOrNull() ?: continue
                    // Keep even expired entries — they're served stale (revalidated) so
                    // a restart is instantly warm rather than cold.
                    val key = String(java.util.Base64.getDecoder().decode(parts[1]))
                    val value = String(java.util.Base64.getDecoder().decode(parts[2]))
                    // Give reloaded entries a short grace so they're served fresh briefly.
                    map[key] = Entry(value, maxOf(exp, now + 30_000))
                }
            }
        } catch (_: Throwable) { /* corrupt snapshot → ignore, rebuild live */ }
    }

    /** Value if present and NOT expired. */
    fun get(key: String): String? {
        ensureLoaded()
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) return null
        return entry.value
    }

    /** Value if present at all, with a `stale` flag (past TTL). Never evicts. */
    fun peek(key: String): Hit? {
        ensureLoaded()
        val entry = map[key] ?: return null
        return Hit(entry.value, System.currentTimeMillis() >= entry.expiresAt)
    }

    fun put(key: String, value: String, ttlMs: Long) {
        ensureLoaded()
        if (map.size >= MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { it.value.expiresAt <= now }
            if (map.size >= MAX_ENTRIES) map.clear()
        }
        map[key] = Entry(value, System.currentTimeMillis() + ttlMs)
        scheduleSave()
    }

    fun invalidate(key: String) {
        map.remove(key)
        scheduleSave()
    }

    /** Drop every entry whose key starts with [prefix] (e.g. "browse:") — used to force a
     *  fresh fetch after a Cloudflare solve, so content cached empty/blocked pre-solve is
     *  replaced instead of served stale. */
    fun invalidatePrefix(prefix: String) {
        val removed = map.keys.removeIf { it.startsWith(prefix) }
        if (removed) scheduleSave()
    }

    /** Debounced async snapshot — disk write never blocks a request. */
    private fun scheduleSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < SAVE_DEBOUNCE_MS) return
        lastSaveAt = now
        saver.execute {
            try {
                val tmp = File(snapshotFile.parentFile, snapshotFile.name + ".tmp")
                val enc = java.util.Base64.getEncoder()
                tmp.bufferedWriter().use { w ->
                    for ((key, entry) in map) {
                        w.write(entry.expiresAt.toString())
                        w.write("\t"); w.write(enc.encodeToString(key.toByteArray()))
                        w.write("\t"); w.write(enc.encodeToString(entry.value.toByteArray()))
                        w.newLine()
                    }
                }
                tmp.renameTo(snapshotFile)
            } catch (_: Throwable) { /* best-effort persistence */ }
        }
    }
}
