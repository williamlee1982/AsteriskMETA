// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON contract of the AndroidLibClashLite Tailscale state API, shared by the JNI export
 * (`Libclash.queryTailscaleStatus`) and the Clash controller route `GET /asterisk/tailscale`.
 * The status payload never contains a login URL; see [parseTailscaleLoginUrl].
 */
@Serializable
internal data class TailscaleCoreStatusList(
    val outbounds: List<TailscaleCoreStatus> = emptyList(),
)

@Serializable
internal data class TailscaleCoreStatus(
    val name: String = "",
    val state: String = "",
    val loginUrlAvailable: Boolean = false,
    val tailnetIps: List<String> = emptyList(),
    val hostname: String? = null,
    val dnsName: String? = null,
    val magicDnsSuffix: String? = null,
    val health: List<String> = emptyList(),
    val error: String? = null,
    val provider: String? = null,
    val config: TailscaleCoreConfig? = null,
    val inboundForwards: List<TailscaleCoreInboundForward>? = null,
)

@Serializable
internal data class TailscaleCoreInboundForward(
    val name: String = "",
    val network: String = "tcp",
    val listen: Int = 0,
    val target: String = "",
    val state: String = "",
    val error: String? = null,
    val activeConns: Int = 0,
    val totalConns: Long = 0,
)

/** Effective, non-secret outbound options; the core never sends the auth key or control URL. */
@Serializable
internal data class TailscaleCoreConfig(
    val hostname: String? = null,
    val stateDir: String? = null,
    val stateDirDefault: Boolean = false,
    val ephemeral: Boolean = false,
    val hasAuthKey: Boolean = false,
    val customControlUrl: Boolean = false,
    val controlUrlHost: String? = null,
)

@Serializable
private data class TailscaleCoreLoginUrl(val url: String = "")

private val TailscaleCoreJson = Json { ignoreUnknownKeys = true }

/**
 * Parses the core status list: every tailscale outbound of the running configuration, including
 * proxy-provider members, in the core's order. Unnamed and repeated entries are dropped.
 */
internal fun parseTailscaleCoreStatuses(json: String): List<TailscaleCoreOutbound> =
    TailscaleCoreJson.decodeFromString(TailscaleCoreStatusList.serializer(), json)
        .outbounds
        .filter { it.name.isNotBlank() }
        .map { status ->
            val key = TailnetOutboundKey(status.name, status.provider?.takeIf(String::isNotEmpty))
            TailscaleCoreOutbound(
                key,
                status.toBackendState(),
                status.config?.toOutbound(key),
                inboundForwards = status.inboundForwards?.map { it.toInboundForwardStatus() },
            )
        }
        .distinctBy { it.key }

private fun TailscaleCoreConfig.toOutbound(key: TailnetOutboundKey) = MihomoTailscaleOutbound(
    name = key.name,
    hostname = hostname?.trim()?.takeIf(String::isNotEmpty),
    stateDir = stateDir?.trim()?.takeIf(String::isNotEmpty),
    hasAuthKey = hasAuthKey,
    ephemeral = ephemeral,
    usesCustomControlUrl = customControlUrl,
    controlUrlHost = controlUrlHost?.trim()?.lowercase()?.takeIf(String::isNotEmpty),
    provider = key.provider,
    stateDirIsDefault = stateDirDefault,
)

internal fun TailscaleCoreStatus.toBackendState(): TailscaleBackendState = when (state) {
    "Idle" -> TailscaleBackendState.Idle
    "Starting" -> TailscaleBackendState.Starting
    "Stopped" -> TailscaleBackendState.Stopped
    "NeedsLogin" -> TailscaleBackendState.NeedsLogin(loginUrlAvailable = loginUrlAvailable)
    "NeedsMachineAuth" -> TailscaleBackendState.NeedsLogin(loginUrlAvailable = false, machineAuthorization = true)
    "Running" -> TailscaleBackendState.Running(
        tailnetAddresses = tailnetIps.map(String::trim).filter(String::isNotEmpty),
        hostname = hostname?.trim()?.takeIf(String::isNotEmpty),
        dnsName = dnsName?.trim()?.removeSuffix(".")?.takeIf(String::isNotEmpty),
        magicDnsSuffix = magicDnsSuffix?.trim()?.removeSuffix(".")?.takeIf(String::isNotEmpty),
        inboundForwards = inboundForwards?.map { it.toInboundForwardStatus() },
    )
    "Error" -> tailscaleBackendError(error?.takeIf(String::isNotBlank) ?: "Tailscale error")
    else -> tailscaleBackendError("Unknown Tailscale backend state")
}

private fun TailscaleCoreInboundForward.toInboundForwardStatus(): TailscaleInboundForwardStatus {
    val parsedState = when (state) {
        "Listening" -> TailscaleInboundForwardState.Listening
        "Waiting" -> TailscaleInboundForwardState.Waiting
        else -> TailscaleInboundForwardState.Error
    }
    return TailscaleInboundForwardStatus(
        name = name.trim(),
        network = network.trim().ifEmpty { "tcp" },
        listen = listen,
        target = target.trim(),
        state = parsedState,
        error = error?.let(::redactTailnetSecrets)?.take(MaxErrorMessageLength),
        activeConns = activeConns.coerceAtLeast(0),
        totalConns = totalConns.coerceAtLeast(0),
    )
}

/** Extracts the URL from `GET /asterisk/tailscale/login-url`. Keep the result in memory only. */
internal fun parseTailscaleLoginUrl(json: String): String =
    TailscaleCoreJson.decodeFromString(TailscaleCoreLoginUrl.serializer(), json).url

/** Login host of the default Tailscale control plane. */
internal const val DefaultTailscaleLoginHost = "login.tailscale.com"

/** Hosts a login URL of [outbound] may point at: its custom control server, else the default. */
internal fun MihomoTailscaleOutbound.allowedLoginHosts(): Set<String> = when {
    !usesCustomControlUrl -> setOf(DefaultTailscaleLoginHost)
    else -> setOfNotNull(controlUrlHost)
}

/**
 * Returns [url] as a URI only when it is an https URL on one of [allowedHosts] without user
 * info; anything else is rejected so a compromised or misconfigured core cannot make the app
 * open an arbitrary page. The URL itself must never be logged or persisted.
 */
internal fun validatedTailscaleLoginUri(url: String, allowedHosts: Set<String>): URI? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase() ?: return null
    return uri.takeIf { host in allowedHosts.map(String::lowercase) }
}

/** The running core has no Tailscale state API (for example a custom upstream ROOT core). */
internal class MihomoTailscaleUnsupportedException :
    IllegalStateException("The running core does not provide the Tailscale state API")

/** The controller refuses to return login URLs because it has no secret configured. */
internal class MihomoTailscaleSecretRequiredException :
    IllegalStateException("A controller secret is required to open the Tailscale login page")
