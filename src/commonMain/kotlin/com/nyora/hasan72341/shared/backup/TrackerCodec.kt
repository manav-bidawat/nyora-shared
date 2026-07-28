package com.nyora.hasan72341.shared.backup

/**
 * Translates between Nyora's canonical tracking vocabulary and Mihon's
 * per-tracker numeric status codes.
 *
 * All three trackers Nyora supports exist in Mihon, so tracking survives a
 * transfer intact — but the status integers are NOT shared across trackers:
 * MyAnimeList uses 6/7 for plan-to-read/rereading where AniList and MangaBaka
 * use 5/6. Mapping therefore has to be keyed on the tracker.
 *
 * Source of truth: Mihon `TrackerManager` and each tracker's own constants.
 */
object TrackerCodec {

    const val MYANIMELIST = 1
    const val ANILIST = 2
    const val MANGABAKA = 11

    private const val READING = "reading"
    private const val PLANNING = "planning"
    private const val COMPLETED = "completed"
    private const val PAUSED = "paused"
    private const val DROPPED = "dropped"
    private const val REREADING = "rereading"

    /** Nyora tracker id -> Mihon `syncId`. */
    fun toMihonTrackerId(nyoraTrackerId: String): Int? = when (nyoraTrackerId.lowercase()) {
        "anilist" -> ANILIST
        "myanimelist", "mal" -> MYANIMELIST
        "mangabaka" -> MANGABAKA
        else -> null
    }

    /** Mihon `syncId` -> Nyora tracker id. Unsupported trackers return null and are skipped. */
    fun toNyoraTrackerId(syncId: Int): String? = when (syncId) {
        ANILIST -> "anilist"
        MYANIMELIST -> "myanimelist"
        MANGABAKA -> "mangabaka"
        else -> null
    }

    // MyAnimeList: 1 reading, 2 completed, 3 on-hold, 4 dropped, 6 plan, 7 rereading.
    private val MAL_TO_NYORA = mapOf(
        1 to READING, 2 to COMPLETED, 3 to PAUSED, 4 to DROPPED, 6 to PLANNING, 7 to REREADING,
    )

    // AniList / MangaBaka: 1 reading, 2 completed, 3 on-hold/paused, 4 dropped, 5 plan, 6 rereading.
    private val ANILIST_TO_NYORA = mapOf(
        1 to READING, 2 to COMPLETED, 3 to PAUSED, 4 to DROPPED, 5 to PLANNING, 6 to REREADING,
    )

    fun toNyoraStatus(syncId: Int, status: Int): String {
        val table = if (syncId == MYANIMELIST) MAL_TO_NYORA else ANILIST_TO_NYORA
        return table[status] ?: READING
    }

    fun toMihonStatus(syncId: Int, status: String): Int {
        val table = if (syncId == MYANIMELIST) MAL_TO_NYORA else ANILIST_TO_NYORA
        return table.entries.firstOrNull { it.value == status.lowercase() }?.key ?: 1
    }
}
