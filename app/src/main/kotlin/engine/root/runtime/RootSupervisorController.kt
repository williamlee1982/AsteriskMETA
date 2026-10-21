
// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import android.content.Context
import engine.proxy.ProxyEngineStatus
import engine.root.config.RootStartConfig
import engine.root.daemon.AsteriskdClient
import engine.root.daemon.config.AsteriskdConfig
import engine.root.daemon.config.AsteriskdConfigEncoder
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdOwner
import engine.root.daemon.control.AsteriskdControlCodec
import engine.root.daemon.control.AsteriskdControlResponse
import engine.root.daemon.control.AsteriskdPhase
import engine.root.daemon.control.AsteriskdResultCode
import engine.root.daemon.control.AsteriskdSnapshot
import engine.root.publication.RootBootConfigWriter
import engine.root.publication.RootBootPublicationCommand
import engine.root.publication.RootPublicationBundle
import engine.root.publication.RootPublicationCommand
import engine.root.publication.RootPublicationWriter
import engine.root.publication.RootPublicationLaunchMode
import engine.root.publication.RootServiceLogCleanupWarningPrefix
import engine.root.publication.prepareRootPublicationDirectories
import engine.root.daemon.control.AsteriskdHelperType
import engine.root.publication.rootRuntimeLayout
import features.logs.AndroidAppLogger
import features.logs.clearServiceLogRepositories
import features.subscription.runtime.mihomoCoreFetchLock
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import system.RootShellGateway
import system.ShellExecOptions
import system.ShellExecResult
import kotlin.time.Duration.Companion.milliseconds

internal class RootSupervisorController(
    context: Context,
    private val shell: RootShellGateway,
) {
    private val appContext = context.applicationContext
    private val runtimeLayout = appContext.rootRuntimeLayout()
    private val client = AsteriskdClient(shell)
    suspend fun status(): AsteriskdControlResponse = client.status(runtimeLayout.asteriskdPath)

    fun observeStatus(): Flow<AsteriskdSnapshot> = client.observeStatus(runtimeLayout.asteriskdPath)
        .onEach { snapshot -> observeRunningFailure(snapshot) }

    suspend fun preflightStart(expectedMode: AsteriskdMode, explicitRestart: Boolean): AsteriskdSnapshot? {
        return status().preflightStart(AsteriskdOwner.AsteriskMeta, expectedMode, explicitRestart)
            ?.also { snapshot -> observeRunningFailure(snapshot, explicitRootAction = true) }
    }

    suspend fun ownsRuntime(): Boolean = status().boundSnapshot()?.owner == AsteriskdOwner.AsteriskMeta

    suspend fun proxyStatus(runMode: Int, expectedMode: AsteriskdMode): ProxyEngineStatus {
        val snapshot = status().boundSnapshot() ?: return ProxyEngineStatus(running = false, runMode = runMode)
        observeRunningFailure(snapshot)
        return snapshot.toProxyEngineStatus(runMode, expectedMode)
    }

    private suspend fun observeRunningFailure(snapshot: AsteriskdSnapshot, explicitRootAction: Boolean = false) {
        if (snapshot.owner == AsteriskdOwner.AsteriskMeta && snapshot.phase == AsteriskdPhase.Running) {
            RootFailureWatcher.ensureStarted(appContext, shell, runtimeLayout, explicitRootAction)
        }
    }

    fun proxyStatus(snapshot: AsteriskdSnapshot, runMode: Int, expectedMode: AsteriskdMode): ProxyEngineStatus =
        snapshot.toProxyEngineStatus(runMode, expectedMode)

    fun requireRunning(snapshot: AsteriskdSnapshot, expectedMode: AsteriskdMode) {
        snapshot.requireRunning(AsteriskdOwner.AsteriskMeta, expectedMode)
    }

    suspend fun ownRunningSnapshotOrNull(): AsteriskdSnapshot? {
        val snapshot = try {
            status().boundSnapshot()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        } ?: return null
        if (snapshot.owner != AsteriskdOwner.AsteriskMeta) return null
        if (snapshot.phase != AsteriskdPhase.Running) return null
        return snapshot
    }

    suspend fun classifyInstalledBinaries(snapshot: AsteriskdSnapshot): RootBinaryClassification {
        val helperPath = when (snapshot.helperType) {
            AsteriskdHelperType.HevSocks5Tunnel -> runtimeLayout.hevSocks5TunnelPath
            AsteriskdHelperType.Bpf2Socks -> runtimeLayout.bpf2socksPath
            null -> null
        }
        return RootNativeBinaryProbe(shell).classify(
            rootProcessProbeTargets(
                supervisorPid = snapshot.supervisorPid,
                asteriskdPath = runtimeLayout.asteriskdPath,
                corePid = snapshot.corePid,
                corePath = runtimeLayout.mihomoCorePath,
                helperPid = snapshot.helperPid,
                helperPath = helperPath,
            ),
        )
    }

    suspend fun start(
        root: RootStartConfig,
        config: AsteriskdConfig,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): AsteriskdSnapshot {
        RootFailureWatcher.beginAttempt()
        status().boundSnapshot()?.let { snapshot ->
            val disposition = snapshot.ordinaryStartDisposition(AsteriskdOwner.AsteriskMeta, config.mode)
            if (disposition == RootOrdinaryStartDisposition.Reuse) {
                observeRunningFailure(snapshot, explicitRootAction = true)
                return snapshot
            }
            if (disposition.shutdownBeforeLaunch) shutdownOwn(trigger)
            return launch(
                root = root,
                config = config,
                restartExpectedOwner = snapshot.owner,
                launchMode = RootPublicationLaunchMode.Service,
                trigger = trigger,
            )
        }

        return launch(root, config, restartExpectedOwner = null, RootPublicationLaunchMode.Service, trigger)
    }

    suspend fun restart(
        root: RootStartConfig,
        config: AsteriskdConfig,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): AsteriskdSnapshot {
        RootFailureWatcher.beginAttempt()
        val snapshot = status().boundSnapshot()
        if (snapshot != null && snapshot.owner != AsteriskdOwner.AsteriskMeta) {
            throw RootRuntimeConflictException(snapshot)
        }
        return launch(
            root = root,
            config = config,
            restartExpectedOwner = snapshot?.owner,
            launchMode = RootPublicationLaunchMode.Service,
            trigger = trigger,
        )
    }

    suspend fun reconfigureServiceControl(
        root: RootStartConfig,
        config: AsteriskdConfig,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): Boolean {
        RootFailureWatcher.beginAttempt()
        val snapshot = status().boundSnapshot()
        if (snapshot != null && snapshot.owner != AsteriskdOwner.AsteriskMeta) {
            throw RootRuntimeConflictException(snapshot)
        }
        val plan = try {
            serviceControlReconfigurePlan(snapshot?.phase, config.serviceControl.enabled)
        } catch (_: IllegalArgumentException) {
            throw RootRuntimeBusyException(requireNotNull(snapshot))
        }
        if (plan.shutdownRequired) shutdownOwn(trigger)
        when (plan.launchMode) {
            RootPublicationLaunchMode.Service -> launch(
                root,
                config,
                restartExpectedOwner = snapshot?.owner,
                launchMode = RootPublicationLaunchMode.Service,
                trigger = trigger,
            )
            RootPublicationLaunchMode.Monitor -> launch(
                root,
                config,
                restartExpectedOwner = snapshot?.owner,
                launchMode = RootPublicationLaunchMode.Monitor,
                trigger = trigger,
            )
            RootPublicationLaunchMode.None -> RootFailureWatcher.stop()
        }
        return plan.launchMode == RootPublicationLaunchMode.Service
    }

    private suspend fun launch(
        root: RootStartConfig,
        config: AsteriskdConfig,
        restartExpectedOwner: AsteriskdOwner?,
        launchMode: RootPublicationLaunchMode,
        trigger: RootLifecycleTrigger,
    ): AsteriskdSnapshot {
        var stage = "prepare_directories"
        RootFailureWatcher.ensureStarted(appContext, shell, runtimeLayout, explicitRootAction = true, running = false)
        runCatching { AndroidAppLogger.info(LogTag, "root_start trigger=${trigger.wire} mode=${config.mode.wireValue} launch=$launchMode stage=$stage") }
        try {
            preparePublication()
            stage = "encode_config"
            val daemonConfigBytes = AsteriskdConfigEncoder.encode(config).toByteArray(Charsets.UTF_8)
            val publication = RootPublicationBundle(
                runtimeLayout = runtimeLayout,
                bootEnabled = root.enableBoot,
                launchMode = launchMode,
                restartExpectedOwner = restartExpectedOwner?.wireValue,
                manageProviders = root.manageProviders,
            )
            clearInMemoryServiceLogs()
            stage = "root_prepare"
            val preparationResult = shell.exec(
                RootPublicationCommand.buildPreparation(publication),
                ShellExecOptions(logFailure = false),
            )
            reportServiceLogCleanupFailures(preparationResult.stderr)
            if (preparationResult.errno != 0 || preparationResult.stdout.isNotBlank()) {
                throw launchFailure(preparationResult)
            }
            stage = "config_write"
            RootPublicationWriter.write(runtimeLayout, root.mihomoProfileBytes, daemonConfigBytes)
            runCatching { AndroidAppLogger.info(LogTag, "root_start trigger=${trigger.wire} stage=config_write result=ok") }
            stage = "launch"
            val launchResult = mihomoCoreFetchLock.withLock {
                shell.exec(
                    RootPublicationCommand.buildLaunch(publication),
                    ShellExecOptions(logFailure = false),
                )
            }
            if (launchResult.errno != 0 || launchResult.stdout.isNotBlank()) {
                throw launchFailure(launchResult)
            }
            stage = "await_ready"
            runCatching { AndroidAppLogger.info(LogTag, "root_start trigger=${trigger.wire} stage=launch result=sent") }
            if (launchMode == RootPublicationLaunchMode.Service) warnAboutPrivateDns(root)
            // The supervisor bounds each start stage itself and ends in running/failed; the app
            // waits for that terminal state (re-watching while it progresses) up to a hard cap.
            val snapshot = withTimeoutOrNull(StartHardTimeoutMilliseconds.milliseconds) {
                when (launchMode) {
                    RootPublicationLaunchMode.Service -> client.awaitRunning(
                        runtimeLayout.asteriskdPath,
                        unboundTimeoutMilliseconds = StartTimeoutMilliseconds,
                    )
                    RootPublicationLaunchMode.Monitor -> client.awaitStopped(runtimeLayout.asteriskdPath)
                    RootPublicationLaunchMode.None -> error("A non-launch publication has no runtime snapshot")
                }
            } ?: run {
                // Do not leave a half-started supervisor behind once the app has given up.
                withContext(NonCancellable) { runCatching { stopOwn(trigger) } }
                throw IllegalStateException("asteriskd did not reach the requested phase before timeout")
            }
            if (snapshot.owner != AsteriskdOwner.AsteriskMeta) throw RootRuntimeConflictException(snapshot)
            require(snapshot.mode == config.mode) { "Unexpected ROOT mode ${snapshot.mode.wireValue}" }
            if (launchMode == RootPublicationLaunchMode.Service) {
                observeRunningFailure(snapshot, explicitRootAction = true)
            } else {
                // A resident supervisor waiting for a trigger has no running core to monitor.
                RootFailureWatcher.stop()
            }
            runCatching { AndroidAppLogger.info(LogTag, "root_start trigger=${trigger.wire} stage=ready phase=${snapshot.phase}") }
            return snapshot
        } catch (error: Exception) {
            withContext(NonCancellable) { RootFailureWatcher.stop() }
            val outcome = if (error is kotlinx.coroutines.CancellationException) "cancelled" else "failed"
            runCatching { AndroidAppLogger.warn(LogTag, "root_start trigger=${trigger.wire} stage=$stage result=$outcome type=${error.javaClass.simpleName}") }
            throw error
        }
    }

    suspend fun stopOwn(trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume): AsteriskdControlResponse {
        runCatching { AndroidAppLogger.info(LogTag, "root_stop trigger=${trigger.wire}") }
        val initial = status()
        val initialSnapshot = initial.boundSnapshot() ?: run {
            RootFailureWatcher.stop()
            RootOrphanCleanupCommand.stopFailure(cleanupOrphanedResources())?.let(::error)
            return initial
        }
        if (initialSnapshot.owner != AsteriskdOwner.AsteriskMeta) {
            throw RootRuntimeConflictException(initialSnapshot)
        }
        val result = shell.exec(RootStopOwnCommand.build(runtimeLayout), ShellExecOptions(logFailure = false))
        val response = AsteriskdControlCodec.decodeShellResponse(result)
        when (response.requestId) {
            "status" -> response.boundSnapshot()?.let { snapshot ->
                if (snapshot.owner != AsteriskdOwner.AsteriskMeta) throw RootRuntimeConflictException(snapshot)
            }
            "stop" -> Unit
            else -> error("Unexpected stop-own response id")
        }
        if (response.result.code == AsteriskdResultCode.Ok || response.result.code == AsteriskdResultCode.NotRunning) {
            RootFailureWatcher.stop()
            return response
        }
        error(response.result.message ?: "Failed to stop asteriskd")
    }

    suspend fun shutdownOwn(trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume): AsteriskdControlResponse {
        runCatching { AndroidAppLogger.info(LogTag, "root_shutdown trigger=${trigger.wire}") }
        val initial = status()
        val initialSnapshot = initial.boundSnapshot() ?: run {
            RootFailureWatcher.stop()
            cleanupOrphanedResources()
            return initial
        }
        if (initialSnapshot.owner != AsteriskdOwner.AsteriskMeta) {
            throw RootRuntimeConflictException(initialSnapshot)
        }
        val result = shell.exec(
            RootShutdownOwnCommand.build(runtimeLayout),
            ShellExecOptions(logFailure = false),
        )
        val response = AsteriskdControlCodec.decodeShellResponse(result)
        when (response.requestId) {
            "status" -> response.boundSnapshot()?.let { snapshot ->
                if (snapshot.owner != AsteriskdOwner.AsteriskMeta) {
                    throw RootRuntimeConflictException(snapshot)
                }
            }
            "shutdown", "stop" -> Unit
            else -> error("Unexpected shutdown-own response id")
        }
        if (response.result.code == AsteriskdResultCode.Ok ||
            response.result.code == AsteriskdResultCode.NotRunning
        ) {
            RootFailureWatcher.stop()
            return response
        }
        error(response.result.message ?: "Failed to shutdown asteriskd")
    }

    suspend fun publishBoot(
        root: RootStartConfig,
        config: AsteriskdConfig,
    ) {
        preparePublication()
        RootBootConfigWriter.write(
            layout = runtimeLayout,
            coreConfigBytes = root.mihomoProfileBytes,
            encodedDaemonConfig = AsteriskdConfigEncoder.encode(config),
        )
        val result = shell.exec(
            RootBootPublicationCommand.buildInstallation(runtimeLayout),
            ShellExecOptions(logFailure = false),
        )
        requirePublicationSuccess(result)
    }

    private fun requirePublicationSuccess(result: ShellExecResult) {
        if (result.errno != 0 || result.stdout.isNotBlank()) throw launchFailure(result)
    }

    suspend fun removeBoot() {
        val result = shell.exec(
            RootBootPublicationCommand.buildRemoval(runtimeLayout),
            ShellExecOptions(logFailure = false),
        )
        requirePublicationSuccess(result)
    }

    private fun launchFailure(result: ShellExecResult): IllegalStateException {
        runCatching { AndroidAppLogger.warn(LogTag, "root_launcher exit=${result.errno} stderr=${sanitizeLauncherStderr(result.stderr).take(512)}") }
        val controlResponse = result.controlResponseOrNull()
        controlResponse?.result?.snapshot?.rejectBound(AsteriskdOwner.AsteriskMeta)
        val message = controlResponse?.result?.message ?: sanitizeLauncherStderr(result.stderr)
            .ifBlank { "asteriskd launcher exited with ${result.errno}" }
        return IllegalStateException(message)
    }

    /** No supervisor answers: remove Asterisk-owned rules/routes a dead supervisor left behind. */
    private suspend fun cleanupOrphanedResources(): RootOrphanCleanupOutcome {
        val result = try {
            shell.exec(RootOrphanCleanupCommand.build(runtimeLayout), ShellExecOptions(logFailure = false))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return RootOrphanCleanupOutcome.Failed
        }
        val outcome = RootOrphanCleanupCommand.outcome(result)
        runCatching {
            if (outcome == RootOrphanCleanupOutcome.Failed) {
                AndroidAppLogger.warn(LogTag, "root_cleanup result=failed exit=${result.errno}")
            } else if (outcome != RootOrphanCleanupOutcome.Skipped) {
                AndroidAppLogger.info(LogTag, "root_cleanup result=${outcome.name.lowercase()}")
            }
        }
        return outcome
    }

    private suspend fun warnAboutPrivateDns(root: RootStartConfig) {
        if (!root.enableLocalDns) return
        val mode = runCatching {
            shell.exec("settings get global private_dns_mode", ShellExecOptions(logFailure = false))
                .takeIf { it.errno == 0 }?.stdout
        }.getOrNull()
        rootPrivateDnsWarning(mode, root.enableLocalDns, root.blockEncryptedDns)?.let { warning ->
            runCatching { AndroidAppLogger.warn(LogTag, "root_private_dns $warning") }
        }
    }

    private fun preparePublication() {
        appContext.prepareRootPublicationDirectories()
    }

    private fun clearInMemoryServiceLogs() {
        runCatching { clearServiceLogRepositories() }.onFailure { error ->
            runCatching { AndroidAppLogger.warn(LogTag, "Failed to clear in-memory service logs", error) }
        }
    }

    private fun reportServiceLogCleanupFailures(stderr: String) {
        stderr.lineSequence()
            .filter { line -> line.startsWith(RootServiceLogCleanupWarningPrefix) }
            .forEach { warning -> runCatching { AndroidAppLogger.warn(LogTag, warning) } }
    }

}

private const val LogTag = "RootSupervisorController"

internal fun sanitizeLauncherStderr(stderr: String): String {
    val retained = mutableListOf<String>()
    var readingFileContexts = false
    stderr.lineSequence().forEach { line ->
        if (line.startsWith(RootServiceLogCleanupWarningPrefix)) return@forEach
        if (line.trim() == "SELinux: Loaded file context from:") {
            readingFileContexts = true
            return@forEach
        }
        val trimmed = line.trim()
        if (
            readingFileContexts &&
            trimmed.startsWith('/') &&
            "/selinux/" in trimmed &&
            trimmed.endsWith("_file_contexts")
        ) {
            return@forEach
        }
        readingFileContexts = false
        retained += line
    }
    val stderrWithoutCleanupWarnings = stderr.lineSequence()
        .filterNot { line -> line.startsWith(RootServiceLogCleanupWarningPrefix) }
        .joinToString("\n")
        .trim()
    return retained.joinToString("\n").trim().ifBlank { stderrWithoutCleanupWarnings }
}

/** Soft limit: the supervisor must bind its control socket within this window. */
private const val StartTimeoutMilliseconds = 15_000L
/** Hard cap for a progressing start (core readiness up to 60 s plus rule apply and rollback). */
private const val StartHardTimeoutMilliseconds = 150_000L
