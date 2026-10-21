// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import app.modes.isRootRunMode
import kotlin.coroutines.cancellation.CancellationException
import system.RootShellGateway
import system.ShellExecOptions
import utils.shellQuote

/**
 * Identity of one native binary. A running root process is stale after an APK upgrade when its
 * executable was unlinked from the previous install path, or when path, inode, mtime, or the
 * embedded mihomo module version disagrees with the file in the new install.
 */
internal data class RootNativeBinaryIdentity(
    val path: String,
    val inode: Long? = null,
    val mtimeEpochSeconds: Long? = null,
    val embeddedVersion: String? = null,
)

internal data class RootNativeBinaryProbeTarget(
    val role: String,
    val pid: Int,
    val installedPath: String,
)

internal data class RootNativeBinaryComparison(
    val role: String,
    val installed: RootNativeBinaryIdentity,
    val running: RootNativeBinaryIdentity,
)

internal enum class RootBinaryClassification {
    NotRunning,
    Match,
    Mismatch,
    Unknown,
}

internal enum class RootRunningBinaryAction {
    /** Keep the running process. */
    Reattach,

    /** One supervised stop+start so the new install's binaries replace the old process. */
    ReplaceOnce,

    /** Do not start and do not replace. */
    Ignore,
}

internal fun rootNativeBinaryMismatch(
    installed: RootNativeBinaryIdentity,
    running: RootNativeBinaryIdentity,
): Boolean {
    if (running.path.endsWith(" (deleted)")) return true
    if (installed.path.isNotBlank() && running.path.isNotBlank() && installed.path != running.path) return true
    if (installed.inode != null && running.inode != null && installed.inode != running.inode) return true
    if (
        installed.mtimeEpochSeconds != null &&
        running.mtimeEpochSeconds != null &&
        installed.mtimeEpochSeconds != running.mtimeEpochSeconds
    ) {
        return true
    }
    val installedVersion = installed.embeddedVersion?.takeIf { it.isNotBlank() }
    val runningVersion = running.embeddedVersion?.takeIf { it.isNotBlank() }
    return installedVersion != null && runningVersion != null && installedVersion != runningVersion
}

/**
 * VPN mode must not reach the probe. Callers check this before any root shell command.
 * An explicit stop is not restarted. A mismatch is replaced at most once per process;
 * a later mismatch reattaches so a failed or still-stale probe cannot loop.
 * Unknown identity reattaches rather than restarting on every cold start.
 */
internal fun rootRunningBinaryAction(
    explicitStop: Boolean,
    alreadyAttempted: Boolean,
    classification: RootBinaryClassification,
): RootRunningBinaryAction = when {
    explicitStop || classification == RootBinaryClassification.NotRunning -> RootRunningBinaryAction.Ignore
    classification == RootBinaryClassification.Mismatch && !alreadyAttempted -> RootRunningBinaryAction.ReplaceOnce
    else -> RootRunningBinaryAction.Reattach
}

internal fun shouldConsiderRootBinaryReplacement(runMode: Int): Boolean = runMode.isRootRunMode()

internal fun rootProcessProbeTargets(
    supervisorPid: Int,
    asteriskdPath: String,
    corePid: Int?,
    corePath: String,
    helperPid: Int?,
    helperPath: String?,
): List<RootNativeBinaryProbeTarget> {
    val targets = mutableListOf<RootNativeBinaryProbeTarget>()
    if (supervisorPid > 0 && asteriskdPath.isNotBlank()) {
        targets += RootNativeBinaryProbeTarget("supervisor", supervisorPid, asteriskdPath)
    }
    if (corePid != null && corePid > 0 && corePath.isNotBlank()) {
        targets += RootNativeBinaryProbeTarget("core", corePid, corePath)
    }
    if (helperPid != null && helperPid > 0 && !helperPath.isNullOrBlank()) {
        targets += RootNativeBinaryProbeTarget("helper", helperPid, helperPath)
    }
    return targets
}

internal fun embeddedNativeVersionToken(snippet: String): String? {
    if (
        !snippet.contains("github.com/metacubex/mihomo") &&
        !snippet.contains("github.com/MetaCubeX/mihomo")
    ) {
        return null
    }
    return EmbeddedVersionToken.find(snippet)?.value
}

internal fun parseInodeMtime(statFields: String): Pair<Long?, Long?> {
    val parts = statFields.trim().split(Whitespace).filter { it.isNotEmpty() }
    if (parts.size < 2) return null to null
    val inode = parts[0].toLongOrNull()?.takeIf { it > 0L }
    val mtime = parts[1].toLongOrNull()
    return inode to mtime
}

internal object RootNativeBinaryProbeCommand {
    fun build(targets: List<RootNativeBinaryProbeTarget>): String {
        require(targets.isNotEmpty())
        targets.forEach { target ->
            require(target.pid > 0)
            require(target.role.matches(RolePattern))
            require(target.installedPath.isNotBlank())
        }
        return buildString {
            appendLine("(")
            appendLine("set +e")
            targets.forEach { target ->
                appendLine("printf '%s\\n' 'role=${target.role}'")
                appendIdentity("running", "/proc/${target.pid}/exe", readlink = true)
                appendIdentity("installed", target.installedPath, readlink = false)
                appendLine("printf '%s\\n' '---'")
            }
            appendLine("exit 0")
            append(")")
        }.trimEnd()
    }

    private fun StringBuilder.appendIdentity(label: String, path: String, readlink: Boolean) {
        val quoted = path.shellQuote()
        if (readlink) {
            appendLine("root_bin_link=\"\$(readlink $quoted 2>/dev/null)\"")
            appendLine("printf '%s\\n' \"$label" + "_path=\$root_bin_link\"")
        } else {
            appendLine("root_bin_path=$quoted")
            appendLine("printf '%s\\n' \"$label" + "_path=\$root_bin_path\"")
        }
        appendLine("root_bin_stat=\"\$(stat -c '%i %Y' $quoted 2>/dev/null)\"")
        appendLine("printf '%s\\n' \"$label" + "_stat=\$root_bin_stat\"")
        appendLine(
            "root_bin_ver=\"\$(grep -a -o -m 1 -E ${RootNativeEmbeddedVersionPattern.shellQuote()} $quoted 2>/dev/null)\"",
        )
        appendLine("printf '%s\\n' \"$label" + "_version=\$root_bin_ver\"")
    }
}

internal fun parseRootNativeBinaryBlocks(stdout: String): Map<String, RootNativeBinaryComparison> {
    val blocks = linkedMapOf<String, RootNativeBinaryComparison>()
    var role = ""
    val fields = linkedMapOf<String, String>()
    fun flush() {
        if (role.isBlank()) {
            fields.clear()
            return
        }
        blocks[role] = comparisonFrom(role, fields)
        fields.clear()
        role = ""
    }
    stdout.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        if (line == "---") {
            flush()
            return@forEach
        }
        val key = line.substringBefore('=', missingDelimiterValue = "")
        val value = line.substringAfter('=', missingDelimiterValue = "")
        when (key) {
            "role" -> {
                if (role.isNotBlank()) flush()
                role = value
                fields.clear()
            }
            "running_path", "running_stat", "running_version",
            "installed_path", "installed_stat", "installed_version",
            -> fields[key] = value
        }
    }
    flush()
    return blocks
}

internal fun interpretRootNativeBinaryProbe(
    expectedRoles: List<String>,
    stdout: String,
): RootBinaryClassification {
    if (expectedRoles.isEmpty() || stdout.isBlank()) return RootBinaryClassification.Unknown
    val blocks = parseRootNativeBinaryBlocks(stdout)
    if (blocks.isEmpty()) return RootBinaryClassification.Unknown
    var comparable = false
    for (role in expectedRoles) {
        val comparison = blocks[role] ?: return RootBinaryClassification.Unknown
        if (!comparison.hasComparableField()) continue
        comparable = true
        if (rootNativeBinaryMismatch(comparison.installed, comparison.running)) {
            return RootBinaryClassification.Mismatch
        }
    }
    return if (comparable) RootBinaryClassification.Match else RootBinaryClassification.Unknown
}

internal class RootNativeBinaryProbe(
    private val shell: RootShellGateway,
) {
    suspend fun classify(targets: List<RootNativeBinaryProbeTarget>): RootBinaryClassification {
        if (targets.isEmpty()) return RootBinaryClassification.Unknown
        val result = try {
            shell.exec(
                RootNativeBinaryProbeCommand.build(targets),
                ShellExecOptions(logFailure = false),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RootBinaryClassification.Unknown
        }
        return interpretRootNativeBinaryProbe(targets.map { it.role }, result.stdout)
    }
}

private fun comparisonFrom(role: String, fields: Map<String, String>): RootNativeBinaryComparison {
    val (runningInode, runningMtime) = parseInodeMtime(fields["running_stat"].orEmpty())
    val (installedInode, installedMtime) = parseInodeMtime(fields["installed_stat"].orEmpty())
    return RootNativeBinaryComparison(
        role = role,
        installed = RootNativeBinaryIdentity(
            path = fields["installed_path"].orEmpty(),
            inode = installedInode,
            mtimeEpochSeconds = installedMtime,
            embeddedVersion = embeddedNativeVersionToken(fields["installed_version"].orEmpty()),
        ),
        running = RootNativeBinaryIdentity(
            path = fields["running_path"].orEmpty(),
            inode = runningInode,
            mtimeEpochSeconds = runningMtime,
            embeddedVersion = embeddedNativeVersionToken(fields["running_version"].orEmpty()),
        ),
    )
}

private fun RootNativeBinaryComparison.hasComparableField(): Boolean {
    if (installed.path.isNotBlank() && running.path.isNotBlank()) return true
    if (installed.inode != null && running.inode != null) return true
    if (installed.mtimeEpochSeconds != null && running.mtimeEpochSeconds != null) return true
    return !installed.embeddedVersion.isNullOrBlank() && !running.embeddedVersion.isNullOrBlank()
}

private val RolePattern = Regex("supervisor|core|helper")
private val Whitespace = Regex("\\s+")
private val EmbeddedVersionToken = Regex("""v\d+\.\d+\.\d+(?:[-._+A-Za-z0-9]+)?""")

/** Grep pattern for the mihomo module path plus a short printable trailer. asteriskd has no product version string. */
internal const val RootNativeEmbeddedVersionPattern =
    """github.com/(metacubex|MetaCubeX)/mihomo[[:print:]]{0,48}"""
