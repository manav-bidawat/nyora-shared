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
    /** Returns raw bytes — the platform UI is responsible for decoding them. */
    fun loadBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            val settings = networkConfig.snapshot()
            fetchBytes(rewriteRemoteUrl(url, settings, RemoteUrlKind.Image), settings, headers)
        } else {
            Files.readAllBytes(Path.of(url))
        }
    }
}
