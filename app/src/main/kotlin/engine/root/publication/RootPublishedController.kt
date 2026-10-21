// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import java.io.File

/**
 * The Mihomo controller endpoint a ROOT core was actually launched with.
 *
 * The control port is resolved at every ROOT start: when the configured port is still held (for
 * example by the core being replaced) a random free port is chosen. App-side state can lag behind
 * that choice (process killed before persistence, boot-script start, resume after a kill), which
 * made the app poll a dead port (`127.0.0.1:33279` while the core listened on `:35277`). The
 * published `config.yaml` is the source of truth for the running ROOT core, so readers prefer it.
 */
internal data class RootPublishedController(
    val port: Int,
    val secret: String?,
) {
    override fun toString(): String = "RootPublishedController(port=$port, secret=<redacted>)"
}

internal object RootPublishedControllerReader {
    private data class CacheKey(val path: String, val modified: Long, val length: Long)

    @Volatile private var cache: Pair<CacheKey, RootPublishedController?>? = null

    fun read(configFile: File): RootPublishedController? {
        val key = runCatching {
            if (!configFile.isFile) return null
            CacheKey(configFile.absolutePath, configFile.lastModified(), configFile.length())
        }.getOrNull() ?: return null
        cache?.takeIf { (cachedKey, _) -> cachedKey == key }?.let { (_, value) -> return value }
        val parsed = runCatching {
            configFile.bufferedReader().useLines { lines -> parse(lines) }
        }.getOrNull()
        cache = key to parsed
        return parsed
    }

    /** Reads only top-level `external-controller` / `secret` scalars; never logs the secret. */
    fun parse(lines: Sequence<String>): RootPublishedController? {
        var controller: String? = null
        var secret: String? = null
        for (line in lines) {
            if (line.isEmpty() || line[0].isWhitespace() || line[0] == '#') continue
            when {
                line.startsWith("external-controller:") ->
                    controller = scalar(line.substringAfter(':'))
                line.startsWith("secret:") ->
                    secret = scalar(line.substringAfter(':'))
            }
            if (controller != null && secret != null) break
        }
        val port = controller
            ?.substringAfterLast(':', missingDelimiterValue = "")
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: return null
        return RootPublishedController(port = port, secret = secret)
    }

    private fun scalar(raw: String): String {
        val value = raw.trim()
        return when {
            value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') ->
                value.substring(1, value.length - 1).replace("''", "'")
            value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
                value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            else -> value.substringBefore(" #").trim()
        }
    }
}

/** Prefer the endpoint the running ROOT core was published with over possibly stale app state. */
internal fun engine.mihomo.MihomoControlConfig.withPublishedRootController(
    published: RootPublishedController?,
): engine.mihomo.MihomoControlConfig {
    if (published == null) return this
    val publishedSecret = published.secret ?: secret
    if (published.port == port && publishedSecret == secret) return this
    return copy(port = published.port, secret = publishedSecret)
}
