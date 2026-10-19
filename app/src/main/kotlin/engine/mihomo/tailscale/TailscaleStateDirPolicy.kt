// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import java.nio.file.Path
import java.nio.file.Paths

/** Mihomo's default `state-dir` for a tailscale outbound (`adapter/outbound/tailscale.go`). */
internal const val MihomoTailscaleDefaultStateDir = "tailscale"

/**
 * Resolves a tailscale `state-dir` exactly like Mihomo's `C.Path.Resolve`: empty means
 * [MihomoTailscaleDefaultStateDir], relative paths are joined to the Mihomo home directory and
 * absolute paths are kept. The result is lexically normalized like Go's `filepath.Join`.
 */
internal fun resolveTailscaleStateDir(stateDir: String?, mihomoHomeDirectory: String): String {
    val configured = stateDir?.trim()?.takeIf(String::isNotEmpty) ?: MihomoTailscaleDefaultStateDir
    val path = Paths.get(configured)
    val resolved = if (path.isAbsolute) path else Paths.get(mihomoHomeDirectory).resolve(path)
    return resolved.normalize().toString()
}

internal enum class TailscaleStateDirIssue {
    /** No explicit `state-dir`; every such outbound shares `<home>/tailscale`. */
    ImplicitDefault,

    /** Two outbounds resolve to the same directory and would fight over one node identity. */
    SharedWithAnotherOutbound,

    /**
     * Outside the Mihomo home. The embedded VPN core (CMFA build) accepts any path, but the ROOT
     * core rejects it as an unsafe path, so the identity would not be portable across modes.
     */
    OutsideMihomoHome,

    /** Inside another Android package's data directory, e.g. a standalone Tailscale app. */
    ForeignAppData,

    /** On shared/external storage that other apps can read; tsnet keys must stay private. */
    SharedStorage,
}

internal data class TailscaleStateDirFinding(
    val outboundName: String,
    val resolvedStateDir: String,
    val issues: Set<TailscaleStateDirIssue>,
)

/**
 * Checks tsnet state directories of all tailscale outbounds in one profile.
 *
 * The check is read-only and never touches the file system: it neither creates, reads, nor
 * changes permissions of state directories.
 */
internal fun diagnoseTailscaleStateDirs(
    outbounds: List<MihomoTailscaleOutbound>,
    mihomoHomeDirectory: String,
    ownPackageName: String,
): List<TailscaleStateDirFinding> {
    val home = Paths.get(mihomoHomeDirectory).normalize()
    val resolved = outbounds.map { outbound ->
        outbound to resolveTailscaleStateDir(outbound.stateDir, mihomoHomeDirectory)
    }
    val counts = resolved.groupingBy { (_, path) -> path }.eachCount()
    return resolved.map { (outbound, path) ->
        val normalized = Paths.get(path)
        val issues = buildSet {
            if (outbound.stateDir.isNullOrBlank()) add(TailscaleStateDirIssue.ImplicitDefault)
            if ((counts[path] ?: 0) > 1) add(TailscaleStateDirIssue.SharedWithAnotherOutbound)
            if (!normalized.startsWith(home) || normalized == home) add(TailscaleStateDirIssue.OutsideMihomoHome)
            if (normalized.foreignPackageOrNull(ownPackageName) != null) add(TailscaleStateDirIssue.ForeignAppData)
            if (normalized.isSharedStorage()) add(TailscaleStateDirIssue.SharedStorage)
        }
        TailscaleStateDirFinding(outbound.name, path, issues)
    }
}

/** Returns the package name when [this] lies in an app data directory not owned by [ownPackageName]. */
internal fun Path.foreignPackageOrNull(ownPackageName: String): String? {
    val parts = (0 until nameCount).map { getName(it).toString() }
    val packageName = when {
        parts.size >= 3 && parts[0] == "data" && parts[1] == "data" -> parts[2]
        parts.size >= 4 && parts[0] == "data" && parts[1] in UserDataRoots -> parts[3]
        else -> parts.indexOf("Android").takeIf { it >= 0 && parts.size > it + 2 && parts[it + 1] in ExternalAppRoots }
            ?.let { parts[it + 2] }
    } ?: return null
    return packageName.takeIf { it != ownPackageName }
}

private fun Path.isSharedStorage(): Boolean {
    val first = if (nameCount > 0) getName(0).toString() else return false
    val second = if (nameCount > 1) getName(1).toString() else ""
    return first == "sdcard" || first == "storage" || (first == "mnt" && second in setOf("sdcard", "user", "media_rw")) ||
        (first == "data" && second == "media")
}

private val UserDataRoots = setOf("user", "user_de")
private val ExternalAppRoots = setOf("data", "media", "obb")
