package com.nyora.hasan72341.shared.extension

import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.net.fetchBytes
import com.nyora.hasan72341.shared.net.fetchText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Over-the-air updater for the JS parser bundle + source catalog.
 *
 * Built from nyora-ota-parser (`build.mjs` publishes dist/manifest.json + bundle +
 * sources.json to GitHub-raw). On launch we check the manifest; if a newer version
 * verifies by sha256 we download it into `<appdata>/ota/`. It is **applied on the
 * NEXT launch** (atomic, no mid-session swap) and is strictly **fallback-first**:
 * if the OTA dir is absent/incomplete the app uses the bundled-at-build parsers, so
 * an unreachable/invalid manifest can never break the app.
 *
 * Bundle and catalog are always taken together (both-or-neither) so `getParser`'s
 * embedded source list stays consistent with the seeded catalog.
 */
object ParserOtaUpdater {

    const val BUNDLED_VERSION = 36

    private const val DEFAULT_MANIFEST_URL =
        "https://hasan72341.github.io/nyora-ota-parsers/manifest.json"

    private fun manifestUrl(): String =
        System.getProperty("nyora.ota.manifest")
            ?: System.getenv("NYORA_OTA_MANIFEST")
            ?: DEFAULT_MANIFEST_URL

    private fun otaDir(baseDir: Path): Path = baseDir.resolve("ota")
    private fun readIfExists(p: Path): String? =
        if (Files.exists(p)) runCatching { Files.readString(p) }.getOrNull() else null

    private fun localVersion(baseDir: Path): Int =
        readIfExists(otaDir(baseDir).resolve("version")).orEmpty().trim().toIntOrNull() ?: 0

    /** Current OTA version on disk (0 if no OTA payload exists). */
    fun otaVersion(baseDir: Path): Int = localVersion(baseDir)

    /** True only when a complete, versioned OTA payload is present. */
    fun isActive(baseDir: Path): Boolean {
        val dir = otaDir(baseDir)
        return localVersion(baseDir) > 0 &&
            Files.exists(dir.resolve("parsers.bundle.js")) &&
            Files.exists(dir.resolve("sources.json"))
    }

    /** OTA bundle text, or null to signal "use the bundled classpath copy". */
    fun bundle(baseDir: Path): String? =
        if (isActive(baseDir)) readIfExists(otaDir(baseDir).resolve("parsers.bundle.js")) else null

    /** OTA catalog text, or null to signal "use the bundled classpath copy". */
    fun sources(baseDir: Path): String? =
        if (isActive(baseDir)) readIfExists(otaDir(baseDir).resolve("sources.json")) else null

    /** Fire-and-forget background check; never throws, never blocks boot. */
    fun updateInBackground(baseDir: Path, networkConfig: HelperNetworkConfig) {
        Thread {
            runCatching { updateOnce(baseDir, networkConfig) }
                .onFailure { println("[OTA] check skipped: ${it.message}") }
        }.apply { isDaemon = true; name = "nyora-parser-ota" }.start()
    }

    fun updateOnce(baseDir: Path, networkConfig: HelperNetworkConfig) {
        val settings = networkConfig.snapshot()
        val manifest = Json.parseToJsonElement(fetchText(manifestUrl(), settings)).jsonObject
        val remoteVersion = manifest["version"]?.jsonPrimitive?.intOrNull ?: return
        if (remoteVersion <= localVersion(baseDir)) {
            println("[OTA] parsers up to date (v$remoteVersion)"); return
        }
        val bundleObj = manifest["bundle"]?.jsonObject ?: return
        val sourcesObj = manifest["sources"]?.jsonObject ?: return

        val bundleBytes = fetchBytes(bundleObj["url"]!!.jsonPrimitive.content, settings)
        if (sha256(bundleBytes) != bundleObj["sha256"]?.jsonPrimitive?.content) {
            println("[OTA] bundle sha256 mismatch — ignoring update"); return
        }
        val sourcesBytes = fetchBytes(sourcesObj["url"]!!.jsonPrimitive.content, settings)
        if (sha256(sourcesBytes) != sourcesObj["sha256"]?.jsonPrimitive?.content) {
            println("[OTA] sources sha256 mismatch — ignoring update"); return
        }

        val dir = otaDir(baseDir)
        Files.createDirectories(dir)
        writeAtomic(dir.resolve("parsers.bundle.js"), bundleBytes)
        writeAtomic(dir.resolve("sources.json"), sourcesBytes)
        Files.writeString(dir.resolve("version"), remoteVersion.toString())
        println("[OTA] parser bundle v$remoteVersion downloaded — active on next launch")
    }

    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
