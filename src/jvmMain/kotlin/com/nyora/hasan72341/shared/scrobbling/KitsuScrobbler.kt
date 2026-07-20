package com.nyora.hasan72341.shared.scrobbling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Kitsu tracker (JSON:API). JVM port of Android's
 * `scrobbling/kitsu/data/KitsuRepository.kt`. Auth uses the resource-owner
 * password grant (`username;password` passed as the `code`).
 */
class KitsuScrobbler(
	tokens: ScrobblerTokenStore,
	http: OkHttpClient = OkHttpClient(),
) : Scrobbler(ScrobblerService.KITSU, tokens, http) {

	private companion object {
		const val WEB = "https://kitsu.app"
		const val VND_JSON = "application/vnd.api+json"
	}

	init {
		statuses[ScrobblingStatus.PLANNED] = "planned"
		statuses[ScrobblingStatus.READING] = "current"
		statuses[ScrobblingStatus.RE_READING] = "current"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}

	private var cachedUserId: Long? = null

	// Kitsu is JSON:API: it 406s any request whose Accept isn't
	// application/vnd.api+json. authedBuilder() sets Accept: application/json, so
	// every Kitsu call must override it — otherwise search/state/create all fail.
	private fun kitsuBuilder(): Request.Builder = authedBuilder().header("Accept", VND_JSON)

	// Kitsu uses a resource-owner password grant (no browser redirect); the
	// desktop UI collects username+password and calls ScrobblerOAuth.loginWithPassword.
	override val defaultRedirectUri: String = "nyora+kitsu://auth"
	override val oauthUrl: String = defaultRedirectUri

	override suspend fun authorize(code: String?): ScrobblerUser {
		val body = if (code != null) {
			formBody(
				"grant_type" to "password",
				"username" to code.substringBefore(';'),
				"password" to code.substringAfter(';'),
			)
		} else {
			formBody(
				"grant_type" to "refresh_token",
				"refresh_token" to tokens.refreshToken,
			)
		}
		val resp = callJson(
			Request.Builder().url("$WEB/api/oauth/token").post(body).build(),
		)
		tokens.accessToken = resp.str("access_token")
		tokens.refreshToken = resp.str("refresh_token")
		return loadUser()
	}

	override suspend fun loadUser(): ScrobblerUser {
		val user = callJson(
			kitsuBuilder().url("$WEB/api/edge/users?filter[self]=true").get().build(),
		).arr("data")?.firstOrNull() as? JsonObject ?: error("Kitsu: no user")
		val attrs = user.obj("attributes")
		val id = user.str("id")?.toLongOrNull() ?: 0L
		cachedUserId = id
		return ScrobblerUser(
			id = id,
			nickname = attrs?.str("name").orEmpty(),
			avatar = attrs?.obj("avatar")?.str("small"),
			service = service,
		)
	}

	override suspend fun search(query: String, offset: Int): List<ScrobblerManga> {
		val url = "$WEB/api/edge/manga".toHttpUrl().newBuilder()
			.addQueryParameter("page[limit]", "20")
			.addQueryParameter("page[offset]", offset.toString())
			.addQueryParameter("filter[text]", query)
			.build()
		val resp = callJson(kitsuBuilder().url(url).get().build())
		return resp.arr("data").orEmpty().mapNotNull { el ->
			val jo = el as? JsonObject ?: return@mapNotNull null
			val attrs = jo.obj("attributes") ?: return@mapNotNull null
			val titles = attrs.obj("titles")?.let { t ->
				t.keys.mapNotNull { t.str(it) }
			}.orEmpty()
			ScrobblerManga(
				id = jo.str("id")?.toLongOrNull() ?: return@mapNotNull null,
				name = titles.firstOrNull() ?: attrs.str("canonicalTitle").orEmpty(),
				altName = titles.drop(1).joinToString().ifEmpty { null },
				cover = attrs.obj("posterImage")?.str("small"),
				url = "$WEB/manga/${attrs.str("slug")}",
				isBestMatch = titles.any { it.equals(query, true) },
			)
		}
	}

	override suspend fun getMangaInfo(remoteId: Long): ScrobblerMangaInfo {
		val data = callJson(
			kitsuBuilder().url("$WEB/api/edge/manga/$remoteId").get().build(),
		).obj("data") ?: error("Kitsu: manga $remoteId not found")
		val attrs = data.obj("attributes")
		return ScrobblerMangaInfo(
			id = data.str("id")?.toLongOrNull() ?: remoteId,
			name = attrs?.str("canonicalTitle").orEmpty(),
			cover = attrs?.obj("posterImage")?.str("medium").orEmpty(),
			url = "$WEB/manga/${attrs?.str("slug")}",
			descriptionHtml = attrs?.str("description")?.replace("\\n", "<br>").orEmpty(),
		)
	}

	override suspend fun getState(remoteId: Long): ScrobblingInfo? {
		val entry = findExistingRate(remoteId) ?: return null
		return entry.toInfo(remoteId)
	}

	override suspend fun updateProgress(
		remoteId: Long,
		chapter: Int?,
		status: ScrobblingStatus?,
		rating: Float?,
		comment: String?,
	): ScrobblingInfo {
		val existing = findExistingRate(remoteId)
		val entry = if (existing == null) {
			createRate(remoteId, chapter, status, rating, comment)
		} else {
			patchRate(existing.str("id")!!, chapter, status, rating, comment)
		}
		return entry.toInfo(remoteId)
	}

	private suspend fun findExistingRate(remoteId: Long): JsonObject? {
		val userId = cachedUserId ?: loadUser().id
		val url = "$WEB/api/edge/library-entries".toHttpUrl().newBuilder()
			.addQueryParameter("filter[manga_id]", remoteId.toString())
			.addQueryParameter("filter[userId]", userId.toString())
			.build()
		return callJson(kitsuBuilder().url(url).get().build())
			.arr("data")?.firstOrNull() as? JsonObject
	}

	private suspend fun createRate(
		remoteId: Long,
		chapter: Int?,
		status: ScrobblingStatus?,
		rating: Float?,
		comment: String?,
	): JsonObject {
		val userId = cachedUserId ?: loadUser().id
		val payload = buildJsonObject {
			putJsonObject("data") {
				put("type", "libraryEntries")
				putJsonObject("attributes") {
					put("status", remoteStatus(status) ?: "current")
					put("progress", chapter ?: 0)
					if (rating != null) put("ratingTwenty", (rating * 20f).toInt().coerceIn(2, 20))
					if (comment != null) put("notes", comment)
				}
				putJsonObject("relationships") {
					putJsonObject("manga") {
						putJsonObject("data") { put("type", "manga"); put("id", remoteId.toString()) }
					}
					putJsonObject("user") {
						putJsonObject("data") { put("type", "users"); put("id", userId.toString()) }
					}
				}
			}
		}
		return callJson(
			kitsuBuilder().url("$WEB/api/edge/library-entries")
				.post(jsonBody(payload, VND_JSON)).build(),
		).obj("data") ?: error("Kitsu: create failed")
	}

	private suspend fun patchRate(
		rateId: String,
		chapter: Int?,
		status: ScrobblingStatus?,
		rating: Float?,
		comment: String?,
	): JsonObject {
		val payload = buildJsonObject {
			putJsonObject("data") {
				put("type", "libraryEntries")
				put("id", rateId)
				putJsonObject("attributes") {
					if (chapter != null) put("progress", chapter)
					remoteStatus(status)?.let { put("status", it) }
					if (rating != null) put("ratingTwenty", (rating * 20f).toInt().coerceIn(2, 20))
					if (comment != null) put("notes", comment)
				}
			}
		}
		return callJson(
			Request.Builder().url("$WEB/api/edge/library-entries/$rateId")
				.header("Authorization", "Bearer ${tokens.accessToken}")
				.header("Accept", VND_JSON)
				.patch(jsonBody(payload, VND_JSON)).build(),
		).obj("data") ?: error("Kitsu: patch failed")
	}

	private fun JsonObject.toInfo(remoteId: Long): ScrobblingInfo {
		val attrs = obj("attributes")
		return ScrobblingInfo(
			service = service,
			rateId = str("id")?.toLongOrNull() ?: 0L,
			remoteId = remoteId,
			status = statusFromRemote(attrs?.str("status")),
			chapter = attrs?.int("progress") ?: 0,
			rating = ((attrs?.float("ratingTwenty") ?: 0f) / 20f).coerceIn(0f, 1f),
			comment = attrs?.str("notes"),
			title = "",
			coverUrl = null,
			description = null,
			url = "$WEB/manga/$remoteId",
		)
	}
}
