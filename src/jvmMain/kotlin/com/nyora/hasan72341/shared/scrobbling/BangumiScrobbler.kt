package com.nyora.hasan72341.shared.scrobbling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bangumi (bgm.tv) tracker. JVM port matching Mihon's `BangumiApi`, using the
 * v0 REST API. A descriptive `User-Agent` is required on every call; the
 * current user is addressed as `-` so no separate user-id lookup is needed for
 * collection writes.
 */
class BangumiScrobbler(
	tokens: ScrobblerTokenStore,
	http: OkHttpClient = OkHttpClient(),
) : Scrobbler(ScrobblerService.BANGUMI, tokens, http) {

	private companion object {
		const val API = "https://api.bgm.tv"
		const val OAUTH = "https://bgm.tv/oauth"
		const val WEB = "https://bgm.tv"
		const val UA = "Nyora (https://github.com/Nyora-Manga)"
		const val APP_JSON = "application/json"
	}

	init {
		// Bangumi collection "type": 1 wish, 2 collect, 3 do, 4 on_hold, 5 dropped.
		statuses[ScrobblingStatus.PLANNED] = "1"
		statuses[ScrobblingStatus.COMPLETED] = "2"
		statuses[ScrobblingStatus.READING] = "3"
		statuses[ScrobblingStatus.RE_READING] = "3"
		statuses[ScrobblingStatus.ON_HOLD] = "4"
		statuses[ScrobblingStatus.DROPPED] = "5"
	}

	override val defaultRedirectUri: String = "nyora://bangumi-auth"

	override val oauthUrl: String
		get() = "$OAUTH/authorize?client_id=${service.clientId}" +
			"&response_type=code&redirect_uri=${enc(redirectUri)}"

	private fun Request.Builder.ua(): Request.Builder = header("User-Agent", UA)

	override suspend fun authorize(code: String?): ScrobblerUser {
		val body = if (code != null) {
			formBody(
				"grant_type" to "authorization_code",
				"client_id" to service.clientId,
				"client_secret" to service.clientSecret,
				"code" to code,
				"redirect_uri" to redirectUri,
			)
		} else {
			formBody(
				"grant_type" to "refresh_token",
				"client_id" to service.clientId,
				"client_secret" to service.clientSecret,
				"refresh_token" to tokens.refreshToken,
				"redirect_uri" to redirectUri,
			)
		}
		val resp = callJson(Request.Builder().url("$OAUTH/access_token").ua().post(body).build())
		tokens.accessToken = resp.str("access_token")
		tokens.refreshToken = resp.str("refresh_token")
		return loadUser()
	}

	override suspend fun loadUser(): ScrobblerUser {
		val u = callJson(authedBuilder().ua().url("$API/v0/me").get().build())
		return ScrobblerUser(
			id = u.long("id") ?: 0L,
			nickname = u.str("nickname") ?: u.str("username").orEmpty(),
			avatar = u.obj("avatar")?.str("large"),
			service = service,
		)
	}

	override suspend fun search(query: String, offset: Int): List<ScrobblerManga> {
		val body = buildJsonObject {
			put("keyword", query)
			put("sort", "match")
			putJsonObject("filter") { putJsonArray("type") { add(1) } } // 1 = book/manga
		}
		val resp = callJson(
			authedBuilder().ua().url("$API/v0/search/subjects?limit=20")
				.post(jsonBody(body, APP_JSON)).build(),
		)
		return resp.arr("data").orEmpty().mapNotNull { el ->
			val jo = el as? JsonObject ?: return@mapNotNull null
			val id = jo.long("id") ?: return@mapNotNull null
			ScrobblerManga(
				id = id,
				name = jo.str("name_cn")?.ifBlank { null } ?: jo.str("name").orEmpty(),
				altName = jo.str("name"),
				cover = jo.obj("images")?.let { it.str("common") ?: it.str("medium") },
				url = "$WEB/subject/$id",
				isBestMatch = jo.str("name").equals(query, true) || jo.str("name_cn").equals(query, true),
			)
		}
	}

	override suspend fun getMangaInfo(remoteId: Long): ScrobblerMangaInfo {
		val d = callJson(authedBuilder().ua().url("$API/v0/subjects/$remoteId").get().build())
		return ScrobblerMangaInfo(
			id = d.long("id") ?: remoteId,
			name = d.str("name_cn")?.ifBlank { null } ?: d.str("name").orEmpty(),
			cover = d.obj("images")?.str("common").orEmpty(),
			url = "$WEB/subject/$remoteId",
			descriptionHtml = d.str("summary").orEmpty(),
		)
	}

	override suspend fun getState(remoteId: Long): ScrobblingInfo? {
		val resp = try {
			callJson(authedBuilder().ua().url("$API/v0/users/-/collections/$remoteId").get().build())
		} catch (_: Exception) {
			return null
		}
		val type = resp.int("type") ?: return null
		return ScrobblingInfo(
			service = service,
			rateId = 0L,
			remoteId = remoteId,
			status = statusFromRemote(type.toString()),
			chapter = resp.int("ep_status") ?: 0,
			rating = ((resp.int("rate") ?: 0).toFloat() / 10f).coerceIn(0f, 1f),
			comment = resp.str("comment"),
			title = "",
			coverUrl = null,
			description = null,
			url = "$WEB/subject/$remoteId",
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
			(remoteStatus(status) ?: "3").toIntOrNull()?.let { put("type", it) }
			if (chapter != null) put("ep_status", chapter)
			if (rating != null) put("rate", (rating * 10f).toInt().coerceIn(0, 10))
			if (comment != null) put("comment", comment)
		}
		// Bangumi answers 202/204 with an empty body on success.
		call(
			authedBuilder().ua().url("$API/v0/users/-/collections/$remoteId")
				.post(jsonBody(body, APP_JSON)).build(),
		)
		return ScrobblingInfo(
			service = service,
			rateId = 0L,
			remoteId = remoteId,
			status = status ?: ScrobblingStatus.READING,
			chapter = chapter ?: 0,
			rating = rating ?: 0f,
			comment = comment,
			title = "",
			coverUrl = null,
			description = null,
			url = "$WEB/subject/$remoteId",
		)
	}
}
