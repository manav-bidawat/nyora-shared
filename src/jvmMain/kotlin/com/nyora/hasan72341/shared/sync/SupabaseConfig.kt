package com.nyora.hasan72341.shared.sync

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties

object SupabaseConfig {
    var url: String = ""
    var anonKey: String = ""
    var accessToken: String = ""
    var refreshToken: String = ""
    var userId: String = ""
    var email: String = ""
    var lastSyncTimestamp: String = "1970-01-01T00:00:00Z"
    var googleDesktopClientId: String = "181067068545-5r2ob1jv4mc0v8gd52fgk2jt28pk3370.apps.googleusercontent.com"
    var googleServerClientId: String = "181067068545-4jkfesn716ucqbuhcbtvdtlqfg3ar38u.apps.googleusercontent.com"
    var googleClientSecret: String = ""

    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()
    val isAuthenticated: Boolean get() = accessToken.isNotBlank() && userId.isNotBlank()

    fun load(dataDir: Path) {
        url = System.getenv("SUPABASE_URL")?.takeIf { it.isNotBlank() }
            ?: readProp(dataDir, "url").takeIf { it.isNotBlank() }
            ?: "https://fqguzcoytnbnjwaddakn.supabase.co"
        anonKey = System.getenv("SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() }
            ?: readProp(dataDir, "anon_key").takeIf { it.isNotBlank() }
            ?: "sb_publishable_RZTcdZZlzb_UhYAxtB09AQ_URTEftE4"
        
        // Prioritize env vars/properties for these too
        System.getenv("GOOGLE_DESKTOP_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let { googleDesktopClientId = it }
        System.getenv("GOOGLE_SERVER_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let { googleServerClientId = it }
        System.getenv("GOOGLE_CLIENT_SECRET")?.takeIf { it.isNotBlank() }?.let { googleClientSecret = it }

        val tokenFile = dataDir.resolve("supabase_tokens.properties")
        if (Files.exists(tokenFile)) {
            val p = Properties().also { it.load(Files.newBufferedReader(tokenFile)) }
            accessToken = p.getProperty("access_token", "")
            refreshToken = p.getProperty("refresh_token", "")
            userId = p.getProperty("user_id", "")
            email = p.getProperty("email", "")
            lastSyncTimestamp = p.getProperty("last_sync_timestamp", "1970-01-01T00:00:00Z")
        }
    }

    fun saveTokens(dataDir: Path) {
        val p = Properties()
        p["access_token"] = accessToken
        p["refresh_token"] = refreshToken
        p["user_id"] = userId
        p["email"] = email
        p["last_sync_timestamp"] = lastSyncTimestamp
        Files.newBufferedWriter(dataDir.resolve("supabase_tokens.properties"))
            .use { w -> p.store(w, null) }
    }

    fun clearTokens(dataDir: Path) {
        accessToken = ""; refreshToken = ""; userId = ""; email = ""; lastSyncTimestamp = "1970-01-01T00:00:00Z"
        Files.deleteIfExists(dataDir.resolve("supabase_tokens.properties"))
    }

    fun parseUserIdFromJwt(token: String): String = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return ""
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = String(Base64.getUrlDecoder().decode(padded))
        Regex("\"sub\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1) ?: ""
    }.getOrDefault("")

    fun parseEmailFromJwt(token: String): String = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return ""
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = String(Base64.getUrlDecoder().decode(padded))
        Regex("\"email\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1) ?: ""
    }.getOrDefault("")

    private fun readProp(dataDir: Path, key: String): String {
        val file = dataDir.resolve("supabase.properties")
        if (!Files.exists(file)) return ""
        return Properties()
            .also { it.load(Files.newBufferedReader(file)) }
            .getProperty(key, "")
    }
}
