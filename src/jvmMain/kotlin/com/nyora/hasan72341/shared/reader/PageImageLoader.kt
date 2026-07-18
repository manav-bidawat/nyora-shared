package com.nyora.hasan72341.shared.reader

import com.nyora.hasan72341.shared.net.HelperNetworkConfig
import com.nyora.hasan72341.shared.net.RemoteUrlKind
import com.nyora.hasan72341.shared.net.fetchBytes
import com.nyora.hasan72341.shared.net.rewriteRemoteUrl
import java.nio.file.Files
import java.nio.file.Path

class PageImageLoader(
    private val networkConfig: HelperNetworkConfig = HelperNetworkConfig(),
) {
    /**
     * Returns raw bytes — the platform UI is responsible for decoding them.
     *
     * @param allowLocalFiles when false (the public `/image` proxy), a non-http URL is rejected
     *   instead of read from disk, closing the arbitrary-file-read hole.
     * @param blockPrivateHosts when true (the public `/image` proxy), refuse fetches that resolve
     *   to private/reserved addresses (SSRF protection).
     */
    fun loadBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowLocalFiles: Boolean = true,
        blockPrivateHosts: Boolean = false,
    ): ByteArray {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            val settings = networkConfig.snapshot()
            fetchBytes(rewriteRemoteUrl(url, settings, RemoteUrlKind.Image), settings, headers, blockPrivateHosts)
        } else {
            if (!allowLocalFiles) throw SecurityException("local file access is not permitted")
            Files.readAllBytes(Path.of(url))
        }
    }
}
