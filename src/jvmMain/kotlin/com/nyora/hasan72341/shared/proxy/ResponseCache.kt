package com.nyora.hasan72341.shared.proxy

import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny in-memory TTL cache for hot, globally-identical responses — the catalog
 * (static ~960 sources) and popular/latest browse lists (same for every user).
 * Collapses the most common load so repeat browses don't re-fetch upstream or
 * re-serialize, cutting CPU + memory + outbound requests. Bounded so it can't
 * grow the small VM's heap.
 */
internal object ResponseCache {
    private class Entry(val value: String, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()
    private const val MAX_ENTRIES = 600

    fun get(key: String): String? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: String, ttlMs: Long) {
        if (map.size >= MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { it.value.expiresAt <= now }
            // hard cap: if still full, drop everything rather than grow unbounded
            if (map.size >= MAX_ENTRIES) map.clear()
        }
        map[key] = Entry(value, System.currentTimeMillis() + ttlMs)
    }

    fun invalidate(key: String) {
        map.remove(key)
    }
}
