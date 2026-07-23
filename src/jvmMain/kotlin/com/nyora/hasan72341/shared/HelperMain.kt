package com.nyora.hasan72341.shared

import com.nyora.hasan72341.shared.data.ExtensionInstaller
import com.nyora.hasan72341.shared.data.SourceCatalogClient
import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.model.SourceEngine
import com.nyora.hasan72341.shared.proxy.NyoraRestServer
import com.nyora.hasan72341.shared.reader.PageImageLoader
import com.nyora.hasan72341.shared.repository.JsonToSqlMigration
import com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

object HelperMain {

    /** Bootstrapped state shared by every entry point (desktop helper, web server). */
    class Bootstrap(
        val repository: SqlDelightLibraryRepository,
        val facade: NyoraFacade,
        val downloads: com.nyora.hasan72341.shared.download.DownloadManager,
        val networkConfig: HelperNetworkConfig,
    )

    /**
     * Open the DB, run migrations, seed the built-in source catalog and wire up
     * a [NyoraFacade] + [com.nyora.hasan72341.shared.download.DownloadManager]. Reused by
     * both the loopback desktop helper and the deployable web server so source
     * seeding stays in one place.
     */
    fun bootstrap(): Bootstrap {
        val repository = SqlDelightLibraryRepository()
        JsonToSqlMigration.runIfNeeded(repository)
        seedBuiltInSources(repository)

        val (mangaCount, sourceCount) = repository.count()

        val networkConfig = HelperNetworkConfig()
        val runtime = com.nyora.hasan72341.shared.extension.JvmExtensionRuntime(
            networkConfig = networkConfig,
        )
        val facade = NyoraFacade(
            repository = repository,
            runtime = runtime,
        )
        val downloadManager = com.nyora.hasan72341.shared.download.DownloadManager(facade = facade)

        // Nyora Sync Sync initialization
        val dataDir = SqlDelightLibraryRepository.defaultDatabasePath().parent
        com.nyora.hasan72341.shared.sync.NyoraSyncConfig.load(dataDir)
        
        // Always try loading .env.sync if it exists (prioritize these values)

        // Search for .env.sync in standard locations
        val envPaths = listOf(
            java.nio.file.Path.of(".env.sync"),
            java.nio.file.Path.of("../.env.sync"),
            java.nio.file.Path.of("../../.env.sync")
        )
        for (envSync in envPaths) {
            if (java.nio.file.Files.exists(envSync)) {
                val props = java.util.Properties()
                java.nio.file.Files.newInputStream(envSync).use { props.load(it) }
                props.getProperty("NYORA_SYNC_URL")?.takeIf { it.isNotBlank() }?.let { com.nyora.hasan72341.shared.sync.NyoraSyncConfig.url = it }
                props.getProperty("GOOGLE_DESKTOP_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let { com.nyora.hasan72341.shared.sync.NyoraSyncConfig.googleDesktopClientId = it }
                props.getProperty("GOOGLE_SERVER_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let { com.nyora.hasan72341.shared.sync.NyoraSyncConfig.googleServerClientId = it }
                props.getProperty("GOOGLE_CLIENT_SECRET")?.takeIf { it.isNotBlank() }?.let { com.nyora.hasan72341.shared.sync.NyoraSyncConfig.googleClientSecret = it }
                break
            }
        }
        run {
            val envSync = Path.of("../.env.sync")
            if (Files.exists(envSync)) {
                val props = java.util.Properties()
                Files.newInputStream(envSync).use { props.load(it) }
                props.getProperty("NYORA_SYNC_URL")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { com.nyora.hasan72341.shared.sync.NyoraSyncConfig.url = it }
                props.getProperty("GOOGLE_DESKTOP_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let {
                    com.nyora.hasan72341.shared.sync.NyoraSyncConfig.googleDesktopClientId = it
                }
                props.getProperty("GOOGLE_SERVER_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let {
                    com.nyora.hasan72341.shared.sync.NyoraSyncConfig.googleServerClientId = it
                }
                props.getProperty("GOOGLE_CLIENT_SECRET")?.takeIf { it.isNotBlank() }?.let {
                    com.nyora.hasan72341.shared.sync.NyoraSyncConfig.googleClientSecret = it
                }
            }
        }

        if (com.nyora.hasan72341.shared.sync.NyoraSyncConfig.isConfigured) {
            val sync = com.nyora.hasan72341.shared.sync.NyoraSync(repository, dataDir)
            repository.nyoraSync = sync
            // Trigger an initial pull in the background if we are authenticated
            if (com.nyora.hasan72341.shared.sync.NyoraSyncConfig.isAuthenticated) {
                Thread {
                    sync.refreshToken()
                    sync.pullAll()
                }.also { it.isDaemon = true; it.name = "nyora-sync-initial-pull" }.start()
            }
        }

        return Bootstrap(repository, facade, downloadManager, networkConfig)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val boot = bootstrap()
        val facade = boot.facade
        val downloadManager = boot.downloads
        val server = NyoraRestServer(
            facade = facade,
            catalog = SourceCatalogClient(networkConfig = boot.networkConfig),
            installer = ExtensionInstaller(networkConfig = boot.networkConfig),
            pageLoader = PageImageLoader(networkConfig = boot.networkConfig),
            downloads = downloadManager,
            networkConfig = boot.networkConfig,
            // Pin the port for a stable reverse-proxy upstream when hosted (else ephemeral).
            port = System.getenv("NYORA_HELPER_PORT")?.toIntOrNull() ?: 0,
        )

        val baseUrl = server.start()
        val port = baseUrl.substringAfterLast(":")
        println("Nyora helper listening at $baseUrl")

        val portFilePath = System.getProperty("nyora.helper.port-file")
            ?: System.getenv("NYORA_HELPER_PORT_FILE")
            ?: defaultPortFile().toString()
        val portFile = Path.of(portFilePath)
        Files.createDirectories(portFile.parent ?: Path.of("."))
        portFile.writeText(port)

        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { Files.deleteIfExists(portFile) }
            server.stop()
        })

        // Parent-PID watchdog: if our launcher (SwiftUI app) dies without a
        // clean termination, exit so we don't linger as a zombie helper.
        startParentWatchdog(args)

        Thread.currentThread().join()
    }

    private fun startParentWatchdog(args: Array<String>) {
        val watchedPid = args.firstNotNullOfOrNull { arg ->
            arg.removePrefix("--watch-pid=").takeIf { it != arg && it.isNotBlank() }?.toLongOrNull()
        } ?: System.getProperty("nyora.watch.pid")?.toLongOrNull()
        ?: System.getenv("NYORA_WATCH_PID")?.toLongOrNull()
        ?: return

        val thread = Thread {
            while (true) {
                if (!ProcessHandle.of(watchedPid).map { it.isAlive }.orElse(false)) {
                    System.err.println("Watched parent pid $watchedPid is gone, shutting down helper.")
                    System.exit(0)
                }
                Thread.sleep(1500)
            }
        }
        thread.isDaemon = true
        thread.name = "nyora-parent-watchdog"
        thread.start()
    }

    private fun defaultPortFile(): Path {
        val osName = System.getProperty("os.name", "").lowercase()
        val home = Path.of(System.getProperty("user.home"))
        val dir = when {
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: home.resolve("AppData").resolve("Roaming").toString()
                Path.of(appData, "Nyora")
            }
            osName.contains("mac") ->
                home.resolve("Library").resolve("Application Support").resolve("Nyora")
            else -> {
                val base = System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
                    ?: home.resolve(".config")
                base.resolve("nyora")
            }
        }
        Files.createDirectories(dir)
        return dir.resolve("helper.port")
    }

    private fun seedBuiltInSources(repository: SqlDelightLibraryRepository) {
        // One-time cleanup: drop every Mihon-engine source row from the DB.
        // Mihon APKs need a Dalvik-compatible runtime which the desktop JVM
        // doesn't provide, so these rows can never be opened. Removing them
        // also clears stale references that history rows may still point at.
        val current = repository.load()
        val (mihonRows, keepRows) = current.sources.partition {
            it.engine == SourceEngine.Mihon
        }
        for (row in mihonRows) repository.deleteSource(row.id)
        val existing = keepRows.associateBy { it.id }

        // Every `parser:` id the native engine (kotatsu-parsers-redo) ships. The DB may
        // still carry orphaned `parser:` rows from an older catalogue; any `parser:`
        // source NOT in this set is pruned below so the visible catalogue matches the
        // native enum exactly.
        val validParserIds = HashSet<String>()

        // Pre-installed catalogue = the iOS-curated source set (ported from the
        // Nyora iOS app's NyoraEngine) plus the native defaults. Every other
        // parser ships available-but-not-installed so the user opts in — this
        // also keeps global search fast, since it only fans out over installed
        // sources.
        val nativeDefaults = setOf("MANGADEX", "MANGAPLUS", "MANGAREADER", "ASURASCANS", "ASURASCANS_US", "MANGAFIRE_EN", "MANGAFIRE_JA", "COMICK_FUN")
        fun isCurated(name: String) = name in IosCuratedSources.CURATED || name in nativeDefaults
        // One-time migration: older builds seeded EVERY parser as installed.
        // Flip non-curated parser rows to not-installed exactly once, then leave
        // the user's later install/uninstall choices alone.
        val curatedMarker = SqlDelightLibraryRepository.defaultDatabasePath().parent.resolve(".curated_sources_v1")
        val curatedMigrationDone = Files.exists(curatedMarker)

        if (existing.containsKey("demo:javascript")) {
            repository.deleteSource("demo:javascript")
        }

        // Seed every native parser (kotatsu-parsers-redo) into the catalog. Curated
        // sources ship installed; the rest are available-but-not-installed so the user
        // opts in (keeps global search fast). Existing rows are updated in place so a
        // user's install/uninstall choices and NSFW flag are preserved.
        val catalog = com.nyora.hasan72341.shared.extension.nativeParserCatalog()
        for (seed in catalog) {
            val id = seed.id
            val parserName = id.removePrefix("parser:")
            validParserIds.add(id)
            val prior = existing[id]
            if (prior != null) {
                var updated = prior.copy(
                    name = seed.name,
                    lang = seed.lang,
                    baseUrl = seed.baseUrl,
                    engine = SourceEngine.Parser,
                    contentType = seed.contentType,
                    notes = seed.notes,
                    localPath = seed.localPath,
                    canUninstall = false,
                )
                if (prior.isNsfw != seed.isNsfw) updated = updated.copy(isNsfw = seed.isNsfw)
                if (!curatedMigrationDone) {
                    val shouldInstall = isCurated(parserName)
                    if (updated.isInstalled != shouldInstall) updated = updated.copy(isInstalled = shouldInstall)
                }
                if (updated != prior) repository.upsertSource(updated)
                continue
            }
            repository.upsertSource(seed.copy(isInstalled = isCurated(parserName)))
        }

        // Prune orphaned parser rows the current catalog no longer ships (e.g. legacy
        // sources from an older catalogue). Deleting a manga_source row is safe — no FK
        // references it, so the user's library/history/favourites are untouched.
        if (validParserIds.isNotEmpty()) {
            for (row in keepRows) {
                if (row.id.startsWith("parser:") && row.id !in validParserIds) {
                    repository.deleteSource(row.id)
                }
            }
        }

        if (!curatedMigrationDone) {
            runCatching { Files.writeString(curatedMarker, "v1") }
        }

        // One-time migration for returning users: remap legacy manga ids (details rows were
        // keyed by url.hashCode) to the cross-platform nyoraId scheme so favourites/history/
        // bookmarks survive the id change and merge with the web app on the next sync.
        val mangaIdMarker = SqlDelightLibraryRepository.defaultDatabasePath().parent.resolve(".manga_id_nyora_v1")
        if (!Files.exists(mangaIdMarker)) {
            runCatching { repository.migrateMangaIdsToNyoraId() }
                .onFailure { System.err.println("manga_id migration failed (non-fatal): ${it.message}") }
            runCatching { Files.writeString(mangaIdMarker, "v1") }
        }
    }

}
