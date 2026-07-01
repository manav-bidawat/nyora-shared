package com.nyora.hasan72341.shared.scrobbling

import java.awt.Desktop
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

/**
 * Desktop OAuth login orchestration for the tracker [Scrobbler]s (TS-011).
 *
 * The authorization-code services (AniList, MyAnimeList, Shikimori) are driven
 * through a [OAuthLoopbackServer]: we spin up a throw-away `http://127.0.0.1`
 * listener, point the scrobbler's [Scrobbler.redirectUri] at it, open the
 * consent page in the user's browser, wait for the service to redirect back
 * with the `code`, and exchange it via [Scrobbler.authorize]. Tokens then land
 * in whatever [ScrobblerTokenStore] the scrobbler was built with (use
 * [ScrobblerRepository.persistent] so they survive a restart).
 *
 * Kitsu uses a resource-owner password grant instead of a redirect, so it goes
 * through [loginWithPassword].
 *
 * IMPORTANT (redirect URI registration): a loopback redirect only succeeds if
 * the service's registered OAuth application allows it. Some providers accept
 * any `http://127.0.0.1:<port>` for "installed app" style clients; AniList /
 * MAL / Shikimori generally require the *exact* redirect URI to be pre-listed
 * in the app's settings. If loopback is rejected, register the fixed
 * [FIXED_LOOPBACK_PORT] URI (or fall back to the built-in `nyora://` deep link
 * handled by the platform's URL-scheme handler and feed the captured code to
 * [Scrobbler.authorize] directly).
 */
object ScrobblerOAuth {

	/**
	 * A stable port desktop clients can register as the loopback redirect URI
	 * (`http://127.0.0.1:$FIXED_LOOPBACK_PORT/callback`) with each service's
	 * OAuth app, so the redirect URI is deterministic. Pass `port = 0` to
	 * [login] instead to use an ephemeral port when the service allows any
	 * loopback port.
	 */
	const val FIXED_LOOPBACK_PORT: Int = 43217

	/**
	 * Run the full loopback authorization-code flow for [scrobbler] and return
	 * the signed-in user. Blocks (suspends) until the browser round-trips or
	 * [timeoutMillis] elapses.
	 *
	 * @param port loopback port to listen on (default [FIXED_LOOPBACK_PORT];
	 *   `0` = ephemeral).
	 * @param openBrowser how to open the consent URL — defaults to the system
	 *   browser via AWT [Desktop]; the UI can override (e.g. show the URL).
	 */
	suspend fun login(
		scrobbler: Scrobbler,
		port: Int = FIXED_LOOPBACK_PORT,
		timeoutMillis: Long = 300_000L,
		openBrowser: (String) -> Unit = ::openInSystemBrowser,
	): ScrobblerUser {
		require(scrobbler.service != ScrobblerService.KITSU) {
			"Kitsu uses password login — call loginWithPassword() instead"
		}
		val state = randomState()
		val server = OAuthLoopbackServer(requestedPort = port, expectedState = state)
		return try {
			scrobbler.redirectUri = server.redirectUri
			// All code-grant services use a query-string authorize URL, so a
			// CSRF `state` param appends cleanly.
			openBrowser(scrobbler.oauthUrl + "&state=" + state)
			val code = server.awaitCode(timeoutMillis)
			scrobbler.authorize(code)
		} finally {
			server.stop()
		}
	}

	/** Kitsu resource-owner password grant. */
	suspend fun loginWithPassword(
		scrobbler: Scrobbler,
		username: String,
		password: String,
	): ScrobblerUser {
		require(scrobbler.service == ScrobblerService.KITSU) {
			"Password login is only supported for Kitsu"
		}
		return scrobbler.authorize("$username;$password")
	}

	/** Open [url] in the user's default browser; no-op-with-log if unsupported (headless). */
	fun openInSystemBrowser(url: String) {
		runCatching {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI(url))
				return
			}
		}
		// Fallback for platforms without AWT Desktop support (e.g. some Linux
		// desktops): shell out to the platform opener.
		val opener = when {
			System.getProperty("os.name", "").lowercase().contains("mac") -> "open"
			System.getProperty("os.name", "").lowercase().contains("win") -> "explorer"
			else -> "xdg-open"
		}
		runCatching { ProcessBuilder(opener, url).start() }
	}

	private fun randomState(): String {
		val bytes = ByteArray(16)
		SecureRandom().nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}
}
