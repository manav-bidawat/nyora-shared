package com.nyora.hasan72341.shared.data

import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.net.RemoteUrlKind
import com.nyora.hasan72341.shared.net.fetchBytes
import com.nyora.hasan72341.shared.net.rewriteRemoteUrl
import com.nyora.hasan72341.shared.model.MangaSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class ExtensionInstaller(
    private val extensionsDir: Path = JsonStore.defaultStorePath().parent.resolve("extensions"),
    private val networkConfig: HelperNetworkConfig = HelperNetworkConfig(),
) {
    fun install(source: MangaSource): MangaSource {
        require(source.sourceCodeUrl.isNotBlank()) {
            "Source has no downloadable extension artifact"
        }
        Files.createDirectories(extensionsDir)
        val filename = source.sourceCodeUrl
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "${source.id.replace(':', '_')}.bin" }
        val target = extensionsDir.resolve(filename)
        val settings = networkConfig.snapshot()
        Files.write(target, fetchBytes(rewriteRemoteUrl(source.sourceCodeUrl, settings, RemoteUrlKind.General), settings))
        return source.copy(
            isInstalled = true,
            localPath = target.toAbsolutePath().toString(),
            installedAt = System.currentTimeMillis(),
        )
    }

    fun uninstall(source: MangaSource): MangaSource {
        val path = source.localPath.takeIf { it.isNotBlank() }?.let(Path::of)
        if (path != null && path.exists()) {
            path.deleteIfExists()
        }
        return source.copy(isInstalled = false, localPath = "", installedAt = 0)
    }
}
