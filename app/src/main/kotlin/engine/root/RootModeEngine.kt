// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import android.content.Context
import engine.proxy.LocalProxyRuntime
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyEngineStatus
import engine.proxy.mode.AndroidModeProxyEngine
import engine.root.config.prepareRootConfigBuildContext
import engine.root.config.RootModeStartConfig
import engine.root.config.RootBpf2SocksDefaultBridgePort as ConfigRootBpf2SocksDefaultBridgePort
import engine.root.mode.RootModeCatalog
import engine.root.mode.RootModeDefinition
import engine.root.mode.DefaultTproxyPort as ModeDefaultTproxyPort
import engine.root.mode.DefaultTun2SocksProxyPort as ModeDefaultTun2SocksProxyPort
import engine.root.publication.rootRuntimeLayout
import engine.mihomo.MihomoControlConfig
import engine.mihomo.runtime.MihomoControlClient
import engine.root.daemon.config.AsteriskdConfigEncoder
import engine.root.dns.withRootDnsMutation
import engine.root.publication.RootPublicationWriter
import engine.root.publication.RootPublishedControllerReader
import engine.root.publication.prepareRootPublicationDirectories
import engine.root.publication.publishRootProviderUpdates
import engine.root.runtime.RootConfigChangePlan
import engine.root.runtime.planRootConfigChange
import features.logs.AndroidAppLogger
import java.io.File
import engine.root.runtime.RootFailureWatcher
import engine.root.runtime.RootFailureAnalyzer
import engine.root.runtime.RootFailureReport
import engine.root.runtime.RootRuntimeBusyException
import engine.root.runtime.RootRuntimeConflictException
import engine.root.runtime.RootSupervisorController
import engine.root.runtime.toStableProxyEngineStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlin.coroutines.cancellation.CancellationException
import system.RootShellGateway

internal class RootModeEngine(
    private val context: Context,
    private val rootAccess: RootShellGateway,
    private val definition: RootModeDefinition,
) : AndroidModeProxyEngine {
    private val controller = RootSupervisorController(context, rootAccess)

    internal val daemonMode: engine.root.daemon.config.AsteriskdMode
        get() = definition.daemonMode

    override val runMode: Int
        get() = definition.runMode

    override suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return start(request, explicitRestart = false, resumeAlreadyChecked = false)
    }

    suspend fun startAfterResumeCheck(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return start(request, explicitRestart = false, resumeAlreadyChecked = true)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus {
        applyWithoutRestart(request)?.let { return it }
        return start(request, explicitRestart = true, resumeAlreadyChecked = false)
    }

    /**
     * A requested restart of a running AsteriskMETA core first compares what would be published
     * with what the core and asteriskd are running. Identical configuration is a no-op; a change
     * the Clash API can apply is hot-reloaded (`PUT /configs?force=true`) so rules, routes and
     * open connections survive. Returns `null` when a supervised restart is required or the hot
     * path failed (the caller then restarts as before).
     */
    private suspend fun applyWithoutRestart(request: ProxyEngineStartRequest): ProxyEngineStatus? {
        if (!rootAccess.hasRootAccess()) return null
        val running = runCatching {
            controller.preflightStart(definition.daemonMode, explicitRestart = false)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        } ?: return null
        val layout = context.rootRuntimeLayout()
        return try {
            val config = definition.buildConfig(context.prepareRootConfigBuildContext(request))
            if (config.asteriskdConfig.mode != definition.daemonMode) return null
            val nextDaemon = AsteriskdConfigEncoder.encode(config.asteriskdConfig).toByteArray(Charsets.UTF_8)
            val currentProfile = runCatching { File(layout.configPath).readBytes() }.getOrNull()
            val currentDaemon = runCatching { File(layout.asteriskdConfigPath).readBytes() }.getOrNull()
            val plan = planRootConfigChange(currentProfile, currentDaemon, config.root.mihomoProfileBytes, nextDaemon)
            runCatching { AndroidAppLogger.info(LogTag, "root_restart plan=${plan.name}") }
            when (plan) {
                RootConfigChangePlan.Restart -> return null
                RootConfigChangePlan.NoOp -> Unit
                RootConfigChangePlan.HotReload -> {
                    val published = RootPublishedControllerReader.parse(
                        requireNotNull(currentProfile).toString(Charsets.UTF_8).lineSequence(),
                    ) ?: return null
                    val control = MihomoControlConfig(port = published.port, secret = published.secret.orEmpty())
                    context.prepareRootPublicationDirectories()
                    RootPublicationWriter.write(layout, config.root.mihomoProfileBytes, nextDaemon)
                    context.withRootDnsMutation(request.appState) {
                        if (config.root.manageProviders) context.publishRootProviderUpdates()
                        MihomoControlClient().reloadProfile(
                            config = control,
                            profilePath = layout.configPath,
                            reloadLocally = false,
                        )
                    }
                    runCatching { AndroidAppLogger.info(LogTag, "root_restart hot_reload=ok") }
                }
            }
            config.localProxyOptions?.let(LocalProxyRuntime::update) ?: LocalProxyRuntime.clear()
            controller.proxyStatus(running, runMode, definition.daemonMode)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            runCatching {
                AndroidAppLogger.warn(LogTag, "root_restart hot_reload=failed type=${error.javaClass.simpleName}; falling back to supervised restart")
            }
            null
        }
    }

    suspend fun resumeIfRunning(request: ProxyEngineStartRequest): ProxyEngineStatus? {
        if (!rootAccess.hasRootAccess()) error(context.getString(definition.rootRequiredErrorResId))
        controller.preflightStart(definition.daemonMode, explicitRestart = false) ?: return null
        val restored = buildLocalProxyOptions(request)
        val confirmed = controller.preflightStart(definition.daemonMode, explicitRestart = false) ?: return null
        restored?.let(LocalProxyRuntime::update) ?: LocalProxyRuntime.clear()
        return controller.proxyStatus(confirmed, runMode, definition.daemonMode)
    }

    private suspend fun start(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean,
        resumeAlreadyChecked: Boolean,
    ): ProxyEngineStatus {
        if (!resumeAlreadyChecked && !rootAccess.hasRootAccess()) {
            error(context.getString(definition.rootRequiredErrorResId))
        }
        if (!explicitRestart) {
            if (!resumeAlreadyChecked) resumeIfRunning(request)?.let { return it }
        } else {
            controller.preflightStart(definition.daemonMode, explicitRestart = true)
        }

        LocalProxyRuntime.clear()
        val rootContext = context.prepareRootConfigBuildContext(request)
        val config = definition.buildConfig(rootContext)
        require(config.asteriskdConfig.mode == definition.daemonMode)
        return runCatching {
            val snapshot = if (explicitRestart) {
                controller.restart(config.root, config.asteriskdConfig)
            } else {
                controller.start(config.root, config.asteriskdConfig)
            }
            controller.requireRunning(snapshot, definition.daemonMode)
            config.localProxyOptions?.let(LocalProxyRuntime::update) ?: LocalProxyRuntime.clear()
            controller.proxyStatus(snapshot, runMode, definition.daemonMode)
        }.onFailure {
            LocalProxyRuntime.clear()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (error is RootRuntimeConflictException || error is RootRuntimeBusyException) throw error
            // Diagnostics are best effort; keep the original startup failure as the cause.
            val occurredAt = System.currentTimeMillis()
            val attempt = RootFailureWatcher.currentAttempt()
            val explanation = RootFailureAnalyzer.analyze(runMode, error, occurredAt)
            val report = try {
                RootFailureReport.build(context, rootAccess, context.rootRuntimeLayout(), occurredAt)
            } catch (diagnosticError: Exception) {
                if (diagnosticError is CancellationException) throw diagnosticError
                null
            }
            RootFailureWatcher.publish(
                if (report == null) explanation else explanation.copy(
                    deviceInfo = report.deviceInfo,
                    serviceLog = report.serviceLog,
                ),
                expectedAttempt = attempt,
            )
            throw IllegalStateException(
                context.getString(definition.startFailedErrorResId, error.message.orEmpty()),
                error,
            )
        }
    }

    private fun buildLocalProxyOptions(request: ProxyEngineStartRequest) =
        LocalProxyRuntime.current() ?: definition
            .buildConfig(context.prepareRootConfigBuildContext(request))
            .localProxyOptions

    override suspend fun stop(): ProxyEngineStatus {
        controller.stopOwn()
        LocalProxyRuntime.clear()
        return status()
    }

    suspend fun shutdown(): ProxyEngineStatus {
        controller.shutdownOwn()
        LocalProxyRuntime.clear()
        return status()
    }

    suspend fun reconfigureServiceControl(request: ProxyEngineStartRequest): Boolean {
        if (!rootAccess.hasRootAccess()) error(context.getString(definition.rootRequiredErrorResId))
        val config = definition.buildConfig(context.prepareRootConfigBuildContext(request))
        require(config.asteriskdConfig.mode == definition.daemonMode)
        val wasRunning = controller.reconfigureServiceControl(config.root, config.asteriskdConfig)
        if (wasRunning) {
            config.localProxyOptions?.let(LocalProxyRuntime::update) ?: LocalProxyRuntime.clear()
        } else {
            LocalProxyRuntime.clear()
        }
        return wasRunning
    }

    suspend fun ownsRuntime(): Boolean {
        return controller.ownsRuntime()
    }

    override suspend fun status(): ProxyEngineStatus {
        return controller.proxyStatus(runMode, definition.daemonMode)
    }

    fun observeStatus(): Flow<ProxyEngineStatus> = controller.observeStatus()
        .mapNotNull { snapshot ->
            snapshot.toStableProxyEngineStatus(runMode, definition.daemonMode)
        }
        .distinctUntilChanged()

    companion object {
        const val DefaultTproxyPort = ModeDefaultTproxyPort
        const val DefaultTun2SocksProxyPort = ModeDefaultTun2SocksProxyPort
        const val DefaultBpf2SocksBridgePort = ConfigRootBpf2SocksDefaultBridgePort

        fun createAll(context: Context, rootAccess: RootShellGateway): List<RootModeEngine> =
            RootModeCatalog.definitions.map { definition -> RootModeEngine(context, rootAccess, definition) }

        fun prepareConfig(context: Context, runMode: Int, request: ProxyEngineStartRequest): RootModeStartConfig {
            val definition = RootModeCatalog.require(runMode)
            return definition.buildConfig(context.prepareRootConfigBuildContext(request)).also { config ->
                require(config.asteriskdConfig.mode == definition.daemonMode)
            }
        }
    }
}

private const val LogTag = "RootModeEngine"
