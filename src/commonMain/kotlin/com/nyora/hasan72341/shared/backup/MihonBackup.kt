@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nyora.hasan72341.shared.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Wire-compatible mirror of Mihon's `.tachibk` backup schema.
 *
 * Every [ProtoNumber] here MUST match Mihon's `eu.kanade.tachiyomi.data.backup.models`
 * exactly — the field numbers, not the names, are the contract. Two tags are
 * permanently burned and must never be reused:
 *
 *   - [Backup] tag 100  — legacy 0.x source model
 *   - [MihonManga] tag 102 — legacy 0.x history model
 *
 * Nyora-specific data rides in the `memo` extension bags (see [NyoraMemo]) so a
 * Nyora backup restores cleanly into stock Mihon and round-trips back without loss.
 */
@Serializable
data class MihonBackup(
    @ProtoNumber(1) val backupManga: List<MihonManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<MihonCategory> = emptyList(),
    // tag 100 burned: legacy backupBrokenSources
    @ProtoNumber(101) val backupSources: List<MihonSource> = emptyList(),
    @ProtoNumber(104) val backupPreferences: List<MihonPreference> = emptyList(),
    @ProtoNumber(105) val backupSourcePreferences: List<MihonSourcePreferences> = emptyList(),
    @ProtoNumber(106) val backupExtensionStores: List<MihonExtensionStore> = emptyList(),
)

@Serializable
data class MihonManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(14) val viewer: Int = 0,
    @ProtoNumber(16) val chapters: List<MihonChapter> = emptyList(),
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(18) val tracking: List<MihonTracking> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(101) val chapterFlags: Int = 0,
    // tag 102 burned: legacy brokenHistory
    @ProtoNumber(103) val viewerFlags: Int? = null,
    @ProtoNumber(104) val history: List<MihonHistory> = emptyList(),
    @ProtoNumber(105) val updateStrategy: Int = 0,
    @ProtoNumber(106) val lastModifiedAt: Long = 0,
    @ProtoNumber(107) val favoriteModifiedAt: Long? = null,
    @ProtoNumber(108) val excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(109) val version: Long = 0,
    @ProtoNumber(110) val notes: String = "",
    @ProtoNumber(111) val initialized: Boolean = false,
    @ProtoNumber(112) val memo: ByteArray = EMPTY_MEMO,
) {
    // ByteArray breaks data-class equality; compare by identity of the stable key.
    override fun equals(other: Any?): Boolean =
        this === other || (other is MihonManga && source == other.source && url == other.url)

    override fun hashCode(): Int = 31 * source.hashCode() + url.hashCode()
}

@Serializable
data class MihonChapter(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(5) val bookmark: Boolean = false,
    @ProtoNumber(6) val lastPageRead: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val chapterNumber: Float = 0f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
    @ProtoNumber(11) val lastModifiedAt: Long = 0,
    @ProtoNumber(12) val version: Long = 0,
    @ProtoNumber(13) val memo: ByteArray = EMPTY_MEMO,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MihonChapter && url == other.url)

    override fun hashCode(): Int = url.hashCode()
}

@Serializable
data class MihonCategory(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val order: Long = 0,
    @ProtoNumber(3) val id: Long = 0,
    @ProtoNumber(100) val flags: Long = 0,
)

@Serializable
data class MihonSource(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val sourceId: Long,
)

@Serializable
data class MihonHistory(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val lastRead: Long,
    @ProtoNumber(3) val readDuration: Long = 0,
)

@Serializable
data class MihonTracking(
    @ProtoNumber(1) val syncId: Int,
    @ProtoNumber(2) val libraryId: Long = 0,
    @ProtoNumber(3) val mediaIdInt: Int = 0,
    @ProtoNumber(4) val trackingUrl: String = "",
    @ProtoNumber(5) val title: String = "",
    @ProtoNumber(6) val lastChapterRead: Float = 0f,
    @ProtoNumber(7) val totalChapters: Int = 0,
    @ProtoNumber(8) val score: Float = 0f,
    @ProtoNumber(9) val status: Int = 0,
    @ProtoNumber(10) val startedReadingDate: Long = 0,
    @ProtoNumber(11) val finishedReadingDate: Long = 0,
    @ProtoNumber(12) val private: Boolean = false,
    @ProtoNumber(100) val mediaId: Long = 0,
) {
    /** 1.x wrote the remote id into [mediaIdInt]; 0.x/current uses [mediaId]. */
    val remoteId: Long get() = if (mediaIdInt != 0) mediaIdInt.toLong() else mediaId
}

@Serializable
data class MihonPreference(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: MihonPreferenceValue,
)

@Serializable
data class MihonSourcePreferences(
    @ProtoNumber(1) val sourceKey: String,
    @ProtoNumber(2) val prefs: List<MihonPreference> = emptyList(),
)

@Serializable
sealed class MihonPreferenceValue

@Serializable
data class IntPreferenceValue(val value: Int) : MihonPreferenceValue()

@Serializable
data class LongPreferenceValue(val value: Long) : MihonPreferenceValue()

@Serializable
data class FloatPreferenceValue(val value: Float) : MihonPreferenceValue()

@Serializable
data class StringPreferenceValue(val value: String) : MihonPreferenceValue()

@Serializable
data class BooleanPreferenceValue(val value: Boolean) : MihonPreferenceValue()

@Serializable
data class StringSetPreferenceValue(val value: Set<String>) : MihonPreferenceValue()

@Serializable
data class MihonExtensionStore(
    @ProtoNumber(1) val indexUrl: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val badgeLabel: String? = null,
    @ProtoNumber(5) val signingKey: String = "",
    @ProtoNumber(4) val contactWebsite: String = "",
    @ProtoNumber(6) val contactDiscord: String? = null,
    @ProtoNumber(7) val isLegacy: Boolean? = null,
    @ProtoNumber(8) val extensionListUrl: String? = null,
)

/** Mihon's `memo` columns default to an encoded empty JSON object, never null. */
val EMPTY_MEMO: ByteArray = "{}".encodeToByteArray()
