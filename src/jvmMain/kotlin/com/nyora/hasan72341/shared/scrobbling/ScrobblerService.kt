package com.nyora.hasan72341.shared.scrobbling

/**
 * The multi-service tracker registry for the desktop (mac / linux / windows)
 * variants. Mirrors Android's
 * `scrobbling/common/domain/model/ScrobblerService.kt` (same integer [id]s so
 * the sync `tracker_id` slug <-> int mapping stays stable across platforms).
 *
 * OAuth [clientId] / [clientSecret] values are the SAME real credentials the
 * Android app ships in `res/values/constants.xml` — reused here per TS-008 so
 * the desktop OAuth flows (TS-011) authenticate against the same registered
 * applications.
 */
enum class ScrobblerService(
	val id: Int,
	/** Canonical `tracker_id` used in the `nyora_tracking` sync table. */
	val slug: String,
	val title: String,
	val clientId: String,
	val clientSecret: String?,
) {

	SHIKIMORI(
		id = 1,
		slug = "shikimori",
		title = "Shikimori",
		clientId = "Mw6F0tPEOgyV7F9U9Twg50Q8SndMY7hzIOfXg0AX_XU",
		clientSecret = "euBMt1GGRSDpVIFQVPxZrO7Kh6X4gWyv0dABuj4B-M8",
	),

	ANILIST(
		id = 2,
		slug = "anilist",
		title = "AniList",
		clientId = "9887",
		clientSecret = "wrMqFosItQWsmB8dtAHfIFPDt15FfQi2ZGiKkJoW",
	),

	MAL(
		id = 3,
		slug = "myanimelist",
		title = "MyAnimeList",
		clientId = "66e27ac5d5a1764e944677b42e2c4737",
		clientSecret = null, // MAL uses PKCE (public client, no secret)
	),

	KITSU(
		id = 4,
		slug = "kitsu",
		title = "Kitsu",
		clientId = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd",
		clientSecret = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151",
	);

	companion object {
		fun fromId(id: Int): ScrobblerService? = entries.firstOrNull { it.id == id }
		fun fromSlug(slug: String?): ScrobblerService? =
			entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
	}
}
