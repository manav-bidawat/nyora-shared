package com.nyora.hasan72341.shared.scrobbling

/**
 * The multi-service tracker registry for the desktop (mac / linux / windows)
 * variants. Mirrors Android's
 * `scrobbling/common/domain/model/ScrobblerService.kt` (same integer [id]s so
 * the sync `tracker_id` slug <-> int mapping stays stable across platforms).
 *
 * OAuth [clientId] / [clientSecret] are Nyora's own registered apps (redirect
 * nyora://<slug>-auth). Login runs desktop-side in Swift, so these are used
 * only by the scrobbler OAuth helpers; set them to your registered client ids.
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
		clientId = "YOUR_SHIKIMORI_CLIENT_ID",
		clientSecret = "YOUR_SHIKIMORI_CLIENT_SECRET",
	),

	ANILIST(
		id = 2,
		slug = "anilist",
		title = "AniList",
		clientId = "46413",
		clientSecret = null, // AniList uses the implicit grant (no secret)
	),

	MAL(
		id = 3,
		slug = "myanimelist",
		title = "MyAnimeList",
		clientId = "f3fec032a062ca0ba0c37330ca63730a",
		clientSecret = null, // MAL uses PKCE (public client, no secret)
	),

	KITSU(
		id = 4,
		slug = "kitsu",
		title = "Kitsu",
		clientId = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd",
		clientSecret = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151",
	),

	BANGUMI(
		id = 5,
		slug = "bangumi",
		title = "Bangumi",
		clientId = "YOUR_BANGUMI_CLIENT_ID",
		clientSecret = "YOUR_BANGUMI_CLIENT_SECRET",
	),

	MANGABAKA(
		id = 6,
		slug = "mangabaka",
		title = "MangaBaka",
		clientId = "WFVyyltyYIteXTlesNaCnZwLnkjwGWRp",
		clientSecret = null, // MangaBaka uses PKCE (public client, no secret)
	);

	companion object {
		fun fromId(id: Int): ScrobblerService? = entries.firstOrNull { it.id == id }
		fun fromSlug(slug: String?): ScrobblerService? =
			entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
	}
}
