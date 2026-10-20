// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.publication.RootRuntimeLayout
import utils.shellQuote

internal object RootStopOwnCommand {
    fun build(layout: RootRuntimeLayout): String = buildString {
        appendLine("(")
        appendLine("set -eu")
        appendLine("set +e")
        appendLine("asteriskd_status=\"$(${layout.asteriskdPath.shellQuote()} status)\"")
        appendLine("asteriskd_status_code=\"\$?\"")
        appendLine("set -e")
        appendLine("case \"\$asteriskd_status\" in")
        appendLine("  *'\"owner\":\"asteriskmeta\"'*) ;;")
        appendLine("  *) printf '%s\\n' \"\$asteriskd_status\"; exit \"\$asteriskd_status_code\" ;;")
        appendLine("esac")
        appendLine("set +e")
        appendLine("asteriskd_stop=\"$(${layout.asteriskdPath.shellQuote()} stop)\"")
        appendLine("asteriskd_stop_code=\"\$?\"")
        appendLine("set -e")
        appendLine("[ \"\$asteriskd_stop_code\" -eq 0 ] || { printf '%s\\n' \"\$asteriskd_stop\"; exit \"\$asteriskd_stop_code\"; }")
        appendLine("printf '%s\\n' \"\$asteriskd_stop\"")
        appendLine(")")
    }.trimEnd()
}

internal object RootShutdownOwnCommand {
    fun build(layout: RootRuntimeLayout): String {
        val base = RootStopOwnCommand.build(layout)
            .replace("asteriskd_stop", "asteriskd_shutdown")
            .replace(" stop)", " shutdown)")
        val failureCheck =
            "[ \"\$asteriskd_shutdown_code\" -eq 0 ] || { printf '%s\\n' \"\$asteriskd_shutdown\"; " +
                "exit \"\$asteriskd_shutdown_code\"; }"
        val shutdown = base.replace(
            failureCheck,
            buildString {
                appendLine("case \"\$asteriskd_shutdown\" in")
                appendLine("  *'\"code\":\"invalid_request\"'*)")
                appendLine("    set +e")
                appendLine("    asteriskd_shutdown=\"\$(${layout.asteriskdPath.shellQuote()} stop)\"")
                appendLine("    asteriskd_shutdown_code=\"\$?\"")
                appendLine("    set -e")
                appendLine("    ;;")
                appendLine("esac")
                append(failureCheck)
            },
        )
        check(shutdown != base) { "Shutdown command failure check was not found" }
        val responsePrint = "printf '%s\\n' \"\$asteriskd_shutdown\""
        val shutdownWithoutEagerResponse = shutdown.substringBeforeLast(responsePrint)
        check(shutdownWithoutEagerResponse != shutdown) { "Shutdown response print was not found" }
        return shutdownWithoutEagerResponse + buildString {
            appendLine("asteriskd_attempt=0")
            appendLine("while [ \"\$asteriskd_attempt\" -lt $SocketReleasePollAttempts ]; do")
            appendLine("  set +e")
            appendLine("  grep -q '[[:space:]]@asteriskd[.]control\$' /proc/net/unix")
            appendLine("  asteriskd_socket_probe_code=\"\$?\"")
            appendLine("  set -e")
            appendLine("  case \"\$asteriskd_socket_probe_code\" in")
            appendLine("    0) ;;")
            appendLine("    1) printf '%s\\n' \"\$asteriskd_shutdown\"; exit 0 ;;")
            appendLine("    *) printf '%s\\n' \"\$asteriskd_shutdown\"; exit \"\$asteriskd_socket_probe_code\" ;;")
            appendLine("  esac")
            appendLine("  asteriskd_attempt=\$((asteriskd_attempt + 1))")
            appendLine("  sleep 0.1")
            appendLine("done")
            appendLine("printf '%s\\n' \"\$asteriskd_shutdown\"")
            appendLine("exit 1")
            append(")")
        }
    }
}

private const val SocketReleasePollAttempts = 50

internal enum class RootOrphanCleanupOutcome { Skipped, Cleaned, SupervisorLive, Failed }

/**
 * Cleanup-only pass for when no supervisor answers: removes Asterisk-owned rules, routes
 * and pins a crashed or killed supervisor left behind. Never starts anything.
 */
internal object RootOrphanCleanupCommand {
    fun build(layout: RootRuntimeLayout): String {
        val config = layout.asteriskdConfigPath.shellQuote()
        return "( [ -f $config ] || exit 0; exec ${layout.asteriskdPath.shellQuote()} cleanup --config $config )"
    }

    fun outcome(result: system.ShellExecResult): RootOrphanCleanupOutcome {
        if (result.errno == 0 && result.stdout.isBlank()) return RootOrphanCleanupOutcome.Skipped
        val response = runCatching {
            engine.root.daemon.control.AsteriskdControlCodec.decodeShellResponse("cleanup", result)
        }.getOrNull() ?: return RootOrphanCleanupOutcome.Failed
        return when (response.result.code) {
            engine.root.daemon.control.AsteriskdResultCode.Ok -> RootOrphanCleanupOutcome.Cleaned
            engine.root.daemon.control.AsteriskdResultCode.AlreadyRunning -> RootOrphanCleanupOutcome.SupervisorLive
            else -> RootOrphanCleanupOutcome.Failed
        }
    }

    /** An explicit Stop fails when the cleanup pass could not remove every owned resource. */
    fun stopFailure(outcome: RootOrphanCleanupOutcome): String? =
        if (outcome == RootOrphanCleanupOutcome.Failed) {
            "ROOT cleanup incomplete: some network rules may remain. Retry Stop, or reboot if it keeps failing"
        } else {
            null
        }
}

/**
 * Private DNS sends queries to port 853, which local DNS interception (port 53) does not see.
 * Returns a user-facing warning, or null when nothing needs attention.
 */
internal fun rootPrivateDnsWarning(
    privateDnsMode: String?,
    localDnsEnabled: Boolean,
    blockEncryptedDns: Boolean,
): String? {
    if (!localDnsEnabled) return null
    return when (privateDnsMode?.trim()?.lowercase()) {
        "hostname" -> if (blockEncryptedDns) {
            "Strict Private DNS is on while port 853 is blocked: DNS will fail. Set Private DNS to Off or Automatic"
        } else {
            "Strict Private DNS is on: DNS bypasses local DNS interception. Set Private DNS to Off or Automatic"
        }
        "opportunistic" -> if (blockEncryptedDns) null else {
            "Private DNS is automatic: DNS may bypass local DNS interception via port 853. " +
                "Enable \"Block encrypted DNS\" or set Private DNS to Off"
        }
        else -> null
    }
}
