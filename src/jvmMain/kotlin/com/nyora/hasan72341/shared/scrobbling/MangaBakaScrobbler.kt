package com.nyora.hasan72341.shared.scrobbling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * MangaBaka tracker. JVM port matching Mihon's `MangaBakaApi`. Search hits the
 * public catalogue; the library entry is a single create-or-update POST to
 * `/v1/my/library/<seriesId>` (the service upserts, so no rate-id lookup is
 * needed). Login is a PKCE browser flow handled desktop-side in Swift, so the
 * token is injected and [authorize] is never exercised here.
 */
class MangaBakaScrobbler(
	tokens: ScrobblerTokenStore,
	http: OkHttpClient = OkHttpClient(),
) : Scrobbler(ScrobblerService.MANGABAKA, tokens, http) {

	private companion object {
		const val API = "https://api.mangabaka.org"
		const val WEB = "https://mangabaka.org"
		const val APP_JSON = "application/json"
	}

	init {
		statuses[ScrobblingStatus.PLANNED] = "plan_to_read"
		statuses[ScrobblingStatus.READING] = "reading"
		statuses[ScrobblingStatus.RE_READING] = "rereading"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "paused"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}

	override val defaultRedirectUri: String = "nyora://mangabaka-auth"

	override val oauthUrl: String
		get() = "$WEB/auth/oauth2/authorize?client_id=${service.clientId}" +
			"&response_type=code&redirect_uri=${enc(redirectUri)}"

	override suspend fun authorize(code: String?): ScrobblerUser =
		error("MangaBaka login is handled by the desktop OAuth flow")

	override suspend fun loadUser(): ScrobblerUser {
		val u = callJson(authedBuilder().url("$API/v1/my/profile").get().build()).obj("data")
			?: JsonObject(emptyMap())
		return ScrobblerUser(
			id = u.long("id") ?: 0L,
			nickname = u.str("username") ?: u.str("name").orEmpty(),
			avatar = u.str("avatar"),
			service = service,
		)
	}

	override suspend fun search(query: String, offset: Int): List<ScrobblerManga> {
		val url = "$API/v1/series/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.build()
		val resp = callJson(authedBuilder().url(url).get().build())
		return resp.arr("data").orEmpty().mapNotNull { el ->
			val jo = el as? JsonObject ?: return@mapNotNull null
			val id = jo.long("id") ?: return@mapNotNull null
			ScrobblerManga(
				id = id,
				name = jo.str("title") ?: jo.str("native_title").orEmpty(),
				altName = jo.str("native_title"),
				cover = jo.coverUrl(),
				url = "$WEB/series/$id",
				isBestMatch = jo.str("title").equals(query, true),
			)
		}
	}

	override suspend fun getMangaInfo(remoteId: Long): ScrobblerMangaInfo {
		val d = callJson(authedBuilder().url("$API/v1/series/$remoteId").get().build()).obj("data")
			?: error("MangaBaka: series $remoteId not found")
		return ScrobblerMangaInfo(
			id = d.long("id") ?: remoteId,
			name = d.str("title").orEmpty(),
			cover = d.coverUrl().orEmpty(),
			url = "$WEB/series/$remoteId",
			descriptionHtml = d.str("description").orEmpty(),
		)
	}

	override suspend fun getState(remoteId: Long): ScrobblingInfo? {
		val resp = try {
			callJson(authedBuilder().url("$API/v1/my/library/$remoteId").get().build())
		} catch (_: Exception) {
			return null
		}
		val d = resp.obj("data") ?: return null
		return ScrobblingInfo(
			service = service,
			rateId = remoteId,
			remoteId = remoteId,
			status = statusFromRemote(d.str("state")),
			chapter = d.float("progress_chapter")?.toInt() ?: 0,
			rating = ((d.float("rating") ?: 0f) / 100f).coerceIn(0f, 1f),
			comment = null,
			title = "",
			coverUrl = null,
			description = null,
			url = "$WEB/series/$remoteId",
		)
	}

	override suspend fun updateProgress(
		remoteId: Long,
		chapter: Int?,
		status: ScrobblingStatus?,
		rating: Float?,
		comment: String?,
	): ScrobblingInfo {
		val body = buildJsonObject {
			put("state", remoteStatus(status) ?: "reading")
			if (chapter != null) put("progress_chapter", chapter)
			if (rating != null) put("rating", (rating * 100f).toInt().coerceIn(0, 100))
		}
		// MangaBaka upserts and answers 201 { "status": 201, "data": true }. Use the
		// checked call so a rejected write (bad token / payload) surfaces as an error
		// instead of being silently reported as a successful scrobble.
		callChecked(
			authedBuilder().url("$API/v1/my/library/$remoteId")
				.post(jsonBody(body, APP_JSON)).build(),
		)
		return ScrobblingInfo(
			service = service,
			rateId = remoteId,
			remoteId = remoteId,
			status = status ?: ScrobblingStatus.READING,
			chapter = chapter ?: 0,
			rating = rating ?: 0f,
			comment = comment,
			title = "",
			coverUrl = null,
			description = null,
			url = "$WEB/series/$remoteId",
		)
	}

	/** MangaBaka returns nested cover-size variants; return the first URL found. */
	private fun JsonObject.coverUrl(): String? {
		val cover = obj("cover") ?: return null
		cover.str("default")?.let { return it }
		cover.obj("raw")?.str("url")?.let { return it }
		cover.obj("x250")?.str("x1")?.let { return it }
		return null
	}
}
