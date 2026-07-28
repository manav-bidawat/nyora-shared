package com.nyora.hasan72341.shared.backup

import com.nyora.hasan72341.shared.model.ContentRating
import com.nyora.hasan72341.shared.model.MangaState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Nyora-only fields that have no home in Mihon's schema, carried inside the
 * per-row `memo` extension bag (`MihonManga.memo` tag 112 / `MihonChapter.memo` tag 13).
 *
 * Mihon stores `memo` as an opaque JSON object and copies it through backup and
 * restore untouched, so Nyora -> Mihon -> Nyora round-trips without loss while a
 * stock Mihon install simply ignores the contents.
 */
@Serializable
data class NyoraMangaMemo(
    val nyoraId: String = "",
    val sourceRef: String = "",
    val altTitles: List<String> = emptyList(),
    val publicUrl: String = "",
    val largeCoverUrl: String = "",
    val contentRating: String = "",
    val state: String = "",
    val rating: Float = -1f,
    val isNsfw: Boolean = false,
    /** True while this entry still points at a Mihon URL awaiting source resolution. */
    val unresolved: Boolean = false,
    /** Original Mihon source name, kept so the resolver can retry after a source install. */
    val originSourceName: String = "",
    val readerMode: String = "",
    val brightness: Double = 0.0,
    val contrast: Double = 1.0,
    val saturation: Double = 1.0,
    val hue: Double = 0.0,
    val palette: String = "",
)

@Serializable
data class NyoraChapterMemo(
    val nyoraChapterId: String = "",
    val scroll: Float = 0f,
    val percent: Float = 0f,
    val branch: String = "",
    val volume: Int = 0,
    /** Page bookmarks Nyora keeps per (chapter, page); Mihon only has a chapter-level flag. */
    val pageBookmarks: List<NyoraPageBookmark> = emptyList(),
)

@Serializable
data class NyoraPageBookmark(
    val page: Int = 0,
    val note: String = "",
    val scroll: Float = 0f,
    val percent: Float = 0f,
    val createdAt: Long = 0,
)

object MemoCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeManga(memo: NyoraMangaMemo): ByteArray =
        json.encodeToString(NyoraMangaMemo.serializer(), memo).encodeToByteArray()

    fun decodeManga(bytes: ByteArray?): NyoraMangaMemo = decode(bytes, NyoraMangaMemo())

    fun encodeChapter(memo: NyoraChapterMemo): ByteArray =
        json.encodeToString(NyoraChapterMemo.serializer(), memo).encodeToByteArray()

    fun decodeChapter(bytes: ByteArray?): NyoraChapterMemo = decode(bytes, NyoraChapterMemo())

    /**
     * Foreign memo bags (written by Mihon or another fork) decode to the default
     * instance rather than throwing — an unreadable extension bag must never fail
     * a restore.
     */
    private inline fun <reified T> decode(bytes: ByteArray?, fallback: T): T {
        if (bytes == null || bytes.isEmpty()) return fallback
        return runCatching { json.decodeFromString<T>(bytes.decodeToString()) }.getOrDefault(fallback)
    }
}

/** Mihon `MangaStatus` ordinals <-> Nyora [MangaState]. */
object StatusCodec {
    fun toNyora(status: Int): MangaState? = when (status) {
        1 -> MangaState.ONGOING
        2 -> MangaState.FINISHED
        3 -> MangaState.ABANDONED   // Mihon LICENSED
        4 -> MangaState.FINISHED    // Mihon PUBLISHING_FINISHED
        5 -> MangaState.ABANDONED   // Mihon CANCELLED
        6 -> MangaState.PAUSED      // Mihon ON_HIATUS
        else -> null
    }

    fun fromNyora(state: MangaState?): Int = when (state) {
        MangaState.ONGOING -> 1
        MangaState.FINISHED -> 2
        MangaState.ABANDONED -> 5
        MangaState.PAUSED -> 6
        MangaState.UPCOMING -> 0
        MangaState.RESTRICTED -> 3
        null -> 0
    }
}

object ContentRatingCodec {
    fun encode(rating: ContentRating?): String = rating?.name.orEmpty()

    fun decode(raw: String): ContentRating? =
        ContentRating.entries.firstOrNull { it.name == raw }
}

object MangaStateCodec {
    fun encode(state: MangaState?): String = state?.name.orEmpty()

    fun decode(raw: String): MangaState? = MangaState.entries.firstOrNull { it.name == raw }
}
