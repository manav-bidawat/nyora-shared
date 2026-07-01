package com.nyora.hasan72341.shared.scrobbling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shikimori tracker (REST v2). JVM port of Android's
 * `scrobbling/shikimori/data/ShikimoriRepository.kt`. Shikimori requires a
 * descriptive `User-Agent` matching the registered OAuth app.
 */
class ShikimoriScrobbler(
	tokens: ScrobblerTokenStore,
	http: OkHttpClient = OkHttpClient(),
) : Scrobbler(ScrobblerService.SHIKIMORI, tokens, http) {

	private companion object {
		const val DOMAIN = "shikimori.one"
		const val BASE = "https://shikimori.one/"
		const val REDIRECT_URI = "nyora://shikimori-auth"
		const val PAGE_SIZE = 10
		const val USER_AGENT = "Nyora"
	}

	init {
		statuses[ScrobblingStatus.PLANNED] = "planned"
		statuses[ScrobblingStatus.READING] = "watching"
		statuses[ScrobblingStatus.RE_READING] = "rewatching"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}

	private var cachedUserId: Long? = null

	override val defaultRedirectUri: String = REDIRECT_URI

	override val oauthUrl: String
		get() = "${BASE}oauth/authorize?client_id=${service.clientId}" +
			"&redirect_uri=${enc(redirectUri)}&response_type=code&scope="

	private fun builder() = authedBuilder().header("User-Agent", USER_AGENT)

	override suspend fun authorize(code: String?): ScrobblerUser {
		val body = if (code != null) {
			formBody(
				"client_id" to service.clientId,
				"client_secret" to service.clientSecret,
				"grant_type" to "authorization_code",
				"redirect_uri" to redirectUri,
				"code" to code,
			)
		} else {
			formBody(
				"client_id" to service.clientId,
				"client_secret" to service.clientSecret,
				"grant_type" to "refresh_token",
				"refresh_token" to tokens.refreshToken,
			)
		}
		val resp = callJson(
			Request.Builder().url("${BASE}oauth/token")
				.header("User-Agent", USER_AGENT).post(body).build(),
		)
		tokens.accessToken = resp.str("access_token")
		tokens.refreshToken = resp.str("refresh_token")
		return loadUser()
	}

	override suspend fun loadUser(): ScrobblerUser {
		val resp = callJson(builder().url("${BASE}api/users/whoami").get().build())
		val id = resp.long("id") ?: 0L
		cachedUserId = id
		return ScrobblerUser(
			id = id,
			nickname = resp.str("nickname").orEmpty(),
			avatar = resp.str("avatar"),
			service = service,
		)
	}

	override suspend fun search(query: String, offset: Int): List<ScrobblerManga> {
		val page = offset / PAGE_SIZE
		val pageOffset = offset % PAGE_SIZE
		val url = BASE.toHttpUrl().newBuilder()
			.addPathSegment("api").addPathSegment("mangas")
			.addEncodedQueryParameter("page", (page + 1).toString())
			.addEncodedQueryParameter("limit", PAGE_SIZE.toString())
			.addEncodedQueryParameter("censored", "false")
			.addQueryParameter("search", query)
			.build()
		val list = parseArray(call(builder().url(url).get().build())).mapNotNull { el ->
			val jo = el as? JsonObject ?: return@mapNotNull null
			ScrobblerManga(
				id = jo.long("id") ?: return@mapNotNull null,
				name = jo.str("name").orEmpty(),
				altName = jo.str("russian"),
				cover = jo.obj("image")?.str("preview")?.let { abs(it) },
				url = jo.str("url")?.let { abs(it) }.orEmpty(),
				isBestMatch = jo.str("name").equals(query, true) ||
					jo.str("russian").equals(query, true),
			)
		}
		return if (pageOffset != 0) list.drop(pageOffset) else list
	}

	override suspend fun getMangaInfo(remoteId: Long): ScrobblerMangaInfo {
		val resp = callJson(builder().url("${BASE}api/mangas/$remoteId").get().build())
		return ScrobblerMangaInfo(
			id = resp.long("id") ?: remoteId,
			name = resp.str("name").orEmpty(),
			cover = resp.obj("image")?.str("preview")?.let { abs(it) }.orEmpty(),
			url = resp.str("url")?.let { abs(it) }.orEmpty(),
			descriptionHtml = resp.str("description_html").orEmpty(),
		)
	}

	override suspend fun getState(remoteId: Long): ScrobblingInfo? {
		val rate = findExistingRate(remoteId) ?: return null
		return rate.toInfo(remoteId)
	}

	override suspend fun updateProgress(
		remoteId: Long,
		chapter: Int?,
		status: ScrobblingStatus?,
		rating: Float?,
		comment: String?,
	): ScrobblingInfo {
		val existing = findExistingRate(remoteId)
		val rate = if (existing == null) createRate(remoteId, chapter, status, rating, comment)
		else patchRate(existing.long("id") ?: 0L, chapter, status, rating, comment)
		return rate.toInfo(remoteId)
	}

	private suspend fun findExistingRate(remoteId: Long): JsonObject? {
		val userId = cachedUserId ?: loadUser().id
		val url = BASE.toHttpUrl().newBuilder()
			.addPathSegment("api").addPathSegment("v2").addPathSegment("user_rates")
			.addQueryParameter("user_id", userId.toString())
			.addQueryParameter("target_id", remoteId.toString())
			.addQueryParameter("target_type", "Manga")
			.build()
		return parseArray(call(builder().url(url).get().build())).firstOrNull() as? JsonObject
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
			putJsonObject("user_rate") {
				put("target_id", remoteId)
				put("target_type", "Manga")
				put("user_id", userId)
				if (chapter != null) put("chapters", chapter)
				remoteStatus(status)?.let { put("status", it) }
				if (rating != null) put("score", (rating * 10f).toInt().coerceIn(0, 10))
				if (comment != null) put("text", comment)
			}
		}
		val url = BASE.toHttpUrl().newBuilder()
			.addPathSegment("api").addPathSegment("v2").addPathSegment("user_rates").build()
		return callJson(builder().url(url).post(jsonBody(payload)).build())
	}

	private suspend fun patchRate(
		rateId: Long,
		chapter: Int?,
		status: ScrobblingStatus?,
		rating: Float?,
		comment: String?,
	): JsonObject {
		val payload = buildJsonObject {
			putJsonObject("user_rate") {
				if (chapter != null) put("chapters", chapter)
				remoteStatus(status)?.let { put("status", it) }
				if (rating != null) put("score", (rating * 10f).toInt().coerceIn(0, 10))
				if (comment != null) put("text", comment)
			}
		}
		val url = BASE.toHttpUrl().newBuilder()
			.addPathSegment("api").addPathSegment("v2").addPathSegment("user_rates")
			.addPathSegment(rateId.toString()).build()
		return callJson(builder().url(url).patch(jsonBody(payload)).build())
	}

	private fun JsonObject.toInfo(remoteId: Long) = ScrobblingInfo(
		service = service,
		rateId = long("id") ?: 0L,
		remoteId = remoteId,
		status = statusFromRemote(str("status")),
		chapter = int("chapters") ?: 0,
		rating = ((float("score") ?: 0f) / 10f).coerceIn(0f, 1f),
		comment = str("text"),
		title = "",
		coverUrl = null,
		description = null,
		url = "https://$DOMAIN/mangas/$remoteId",
	)

	private fun abs(path: String): String =
		if (path.startsWith("http")) path else "https://$DOMAIN$path"
}
