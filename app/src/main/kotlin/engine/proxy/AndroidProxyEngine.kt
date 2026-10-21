// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Context
import app.modes.RunModeVpnService
import engine.proxy.mode.AndroidModeProxyEngine
import engine.root.RootModeEngine
import engine.root.logRootBinaryMismatch
import engine.root.runtime.RootLifecycleTrigger
import engine.root.runtime.RootRunningBinaryAction
import engine.root.runtime.rootRunningBinaryAction
import engine.root.runtime.shouldConsiderRootBinaryReplacement
import engine.root.keepalive.RootKeepAliveService
import engine.root.runtime.model.RootRuntimeMode
import engine.root.runtime.model.RootRuntimeOwner
import engine.stats.MihomoTrafficStatsNotificationService
import engine.stats.toMihomoTrafficStatsRuntime
import engine.mihomo.withResolvedMihomoControlPort
import engine.root.publication.rootRuntimeLayout
import engine.mihomo.MihomoProfileFactory
import engine.mihomo.raw.requireStartableRawConfig
import engine.mihomo.raw.usesRawMihomoConfig
import engine.vpn.AndroidMihomoRuntime
import engine.vpn.VpnMihomoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway

/**
 * Process-wide proxy engine shared by the UI, quick settings tile and broadcast control so
 * that every lifecycle operation is serialized by one mutex. VPN permission handling is
 * caller specific and is therefore supplied per start or restart request.
 */
internal class AndroidProxyEngine(
    context: Context,
    rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext
    private var requestVpnPermission: VpnPermissionRequester = DenyVpnPermission
    private val vpnMihomoEngine = VpnMihomoEngine(
        context = appContext,
        requestVpnPermission = { intent -> requestVpnPermission(intent) },
    )
    private val rootEngines = RootModeEngine.createAll(appContext, rootAccess)
    private val rootEnginesByRunMode = rootEngines.associateBy(RootModeEngine::runMode)
    private val operationMutex = Mutex()
    private val mutableRootStatusWatchGeneration = MutableStateFlow(0L)
    /** Set by an explicit stop so a binary-mismatch reconcile cannot start the core again. */
    private var explicitRootStop = false
    /** One upgrade replacement per process. A match also settles so cold starts do not probe forever. */
    private var rootBinaryIdentitySettled = false
    private var activeEngine: AndroidModeProxyEngine? = null
    internal val rootStatusWatchGeneration: StateFlow<Long> =
        mutableRootStatusWatchGeneration.asStateFlow()

    suspend fun start(
        request: ProxyEngineStartRequest,
        requestVpnPermission: VpnPermissionRequester = DenyVpnPermission,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus = operationMutex.withLock {
        explicitRootStop = false
        withVpnPermissionRequester(requestVpnPermission) {
            startUnlocked(request, trigger = trigger)
        }
    }

    suspend fun stop(
        preferredRunMode: Int? = null,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus = operationMutex.withLock {
        explicitRootStop = true
        stopUnlocked(preferredRunMode, trigger)
    }

    suspend fun stopCurrentRunMode(
        runMode: Int,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus = operationMutex.withLock {
        explicitRootStop = true
        stopRunModeUnlocked(runMode, trigger)
    }

    suspend fun shutdownCurrentRunMode(
        runMode: Int,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus = operationMutex.withLock {
        explicitRootStop = true
        shutdownRunModeUnlocked(runMode, trigger)
    }

    /**
     * When a ROOT core is already what the user wants running, compare its binaries with the
     * installed APK once. A mismatch performs one supervised restart. VPN mode never calls this.
     * An explicit stop is left stopped.
     */
    internal suspend fun reconcileInstalledRootBinaries(state: app.AppState) {
        if (!shouldConsiderRootBinaryReplacement(state.runMode)) return
        operationMutex.withLock {
            if (explicitRootStop || rootBinaryIdentitySettled) return@withLock
            withContext(Dispatchers.Default) {
                val engine = rootEnginesByRunMode[state.runMode] ?: return@withContext
                if (noteUnsettledRootBinaryAction(engine, RootLifecycleTrigger.InternalResume) != RootRunningBinaryAction.ReplaceOnce) {
                    return@withContext
                }
                startUnlocked(
                    ProxyEngineStartRequest(state.copy(runMode = engine.runMode)),
                    explicitRestart = true,
                    trigger = RootLifecycleTrigger.UpgradeMismatch,
                    forceSupervisedRestart = true,
                )
            }
        }
    }

    suspend fun <T> changeRootCore(
        runMode: Int,
        onRootStopped: () -> Unit,
        action: suspend () -> T,
    ): T = operationMutex.withLock {
        // A resident supervisor also retains the old executable path. Republish on next start.
        if (runMode in rootEnginesByRunMode) {
            shutdownRunModeUnlocked(runMode)
            onRootStopped()
        }
        action()
    }

    /**
     * Profile, subscription, and tailnet restarts land here and then in [RootModeEngine.restart],
     * which plans NoOp / HotReload / supervised Restart. Unchanged config is not a stop+start.
     */
    suspend fun restart(
        request: ProxyEngineStartRequest,
        requestVpnPermission: VpnPermissionRequester = DenyVpnPermission,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus {
        val status = operationMutex.withLock {
            explicitRootStop = false
            withVpnPermissionRequester(requestVpnPermission) {
                startUnlocked(request, explicitRestart = true, trigger = trigger)
            }
        }
        mutableRootStatusWatchGeneration.update { generation -> generation + 1L }
        return status
    }

    private suspend fun <T> withVpnPermissionRequester(
        requester: VpnPermissionRequester,
        block: suspend () -> T,
    ): T {
        requestVpnPermission = requester
        return try {
            block()
        } finally {
            requestVpnPermission = DenyVpnPermission
        }
    }

    suspend fun reconfigureServiceControl(
        nextState: app.AppState,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): app.AppState {
        val appliedState = operationMutex.withLock {
            val rootEngine = rootEnginesByRunMode[nextState.runMode] ?: return@withLock nextState
            val resolvedState = nextState.withResolvedDynamicLocalProxyPort()
            val wasRunning = withContext(Dispatchers.Default) {
                AndroidMihomoRuntime.releaseStandby()
                rootEngine.reconfigureServiceControl(ProxyEngineStartRequest(resolvedState), trigger)
            }
            activeEngine = rootEngine.takeIf { wasRunning }
            resolvedState.copy(proxyRunning = wasRunning)
        }
        mutableRootStatusWatchGeneration.update { generation -> generation + 1L }
        return appliedState
    }

    suspend fun status(
        preferredRunMode: Int? = null,
        appState: app.AppState? = null,
    ): ProxyEngineStatus = operationMutex.withLock {
        statusUnlocked(preferredRunMode, appState)
    }

    internal fun observeRootStatus(runMode: Int): Flow<ProxyEngineStatus> {
        val engine = rootEnginesByRunMode[runMode] ?: return emptyFlow()
        return engine.observeStatus().map { status ->
            normalizeRootRuntimeStatus(status, ::rootRunMode)
        }
    }

    private suspend fun startUnlocked(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean = false,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
        forceSupervisedRestart: Boolean = false,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val raw = appContext.requireStartableRawConfig(request.appState)
        val validatedRequest = request.copy(
            rawConfig = raw?.first,
            rawConfigCheck = raw?.second,
        )
        val requestedEngine = validatedRequest.appState.runMode.engine()
        val hasOtherActiveEngine = activeEngine.let { active -> active != null && active !== requestedEngine }
        var rootResumeChecked = false
        var forceSupervised = forceSupervisedRestart
        // A start that reaches an already running engine does not relaunch the core, so the
        // ports it is running with must be kept instead of being resolved again.
        if (!explicitRestart && requestedEngine is VpnMihomoEngine && activeEngine === requestedEngine) {
            val status = requestedEngine.status()
            if (status.running) {
                MihomoTrafficStatsNotificationService.reconcile(
                    appContext,
                    validatedRequest.appState.toMihomoTrafficStatsRuntime(
                        runMode = status.runMode ?: validatedRequest.appState.runMode,
                        rawConfig = validatedRequest.rawConfig,
                    ),
                )
                return@withContext status.copy(appState = request.appState)
            }
        }
        val rootEngine = requestedEngine as? RootModeEngine
        val canResumeRoot = shouldResumeRootBeforeResolvingPorts(
            explicitRestart,
            hasOtherActiveEngine,
            rootEngine != null,
        )
        if (rootEngine != null && shouldConsiderRootBinaryReplacement(validatedRequest.appState.runMode)) {
            when (noteUnsettledRootBinaryAction(rootEngine, trigger)) {
                RootRunningBinaryAction.ReplaceOnce -> forceSupervised = true
                RootRunningBinaryAction.Reattach -> if (canResumeRoot) {
                    rootEngine.resumeIfRunning(validatedRequest, trigger)?.let { status ->
                        return@withContext adoptResumedRoot(rootEngine, status, request, validatedRequest)
                    }
                    rootResumeChecked = true
                }
                RootRunningBinaryAction.Ignore -> if (canResumeRoot) rootResumeChecked = true
                null -> if (canResumeRoot) {
                    rootEngine.resumeIfRunning(validatedRequest, trigger)?.let { status ->
                        return@withContext adoptResumedRoot(rootEngine, status, request, validatedRequest)
                    }
                    rootResumeChecked = true
                }
            }
        } else if (canResumeRoot && rootEngine != null) {
            rootEngine.resumeIfRunning(validatedRequest, trigger)?.let { status ->
                return@withContext adoptResumedRoot(rootEngine, status, request, validatedRequest)
            }
            rootResumeChecked = true
        }
        // Resolve runtime ports, build, and validate the exact final profile before
        // notifications, engine replacement, VPN permission, Root commands, or routing changes.
        // A ROOT restart replaces our own running core: keep its controller port.
        val ownedControlPort = if (requestedEngine is RootModeEngine && activeEngine is RootModeEngine) {
            engine.root.publication.RootPublishedControllerReader.read(
                java.io.File(appContext.rootRuntimeLayout().configPath),
            )?.port
        } else {
            null
        }
        val resolvedRequest = validatedRequest.prepareNormalMihomoProfile(
            resolveAppState = { appState ->
                appState
                    .withResolvedDynamicLocalProxyPort()
                    .withResolvedMihomoControlPort(ownedPort = ownedControlPort)
            },
            buildProfileBytes = { appState ->
                MihomoProfileFactory.buildProfileBytes(appContext, appState)
            },
        )
        MihomoTrafficStatsNotificationService.reconcile(appContext, null)
        val nextEngine = resolvedRequest.appState.runMode.engine()
        val currentEngine = activeEngine ?: findEngineToStop(resolvedRequest.appState.runMode)
        val rootToRootRestart = explicitRestart &&
            currentEngine is RootModeEngine && nextEngine is RootModeEngine
        if (currentEngine != null && currentEngine !== nextEngine && !rootToRootRestart) {
            if (currentEngine is RootModeEngine) currentEngine.shutdown(trigger) else currentEngine.stop()
        }
        if (nextEngine is RootModeEngine) {
            AndroidMihomoRuntime.releaseStandby()
        }
        activeEngine = nextEngine
        try {
            val status = when {
                forceSupervised && nextEngine is RootModeEngine ->
                    nextEngine.restartSupervised(resolvedRequest, RootLifecycleTrigger.UpgradeMismatch)
                explicitRestart && nextEngine is RootModeEngine -> nextEngine.restart(resolvedRequest, trigger)
                explicitRestart && nextEngine is VpnMihomoEngine -> nextEngine.restart(resolvedRequest)
                shouldUsePreResolvedRootStart(
                    explicitRestart = explicitRestart,
                    resumeChecked = rootResumeChecked,
                    nextEngineIsRoot = nextEngine is RootModeEngine,
                ) -> (nextEngine as RootModeEngine).startAfterResumeCheck(resolvedRequest, trigger)
                nextEngine is RootModeEngine -> nextEngine.start(resolvedRequest, trigger)
                else -> nextEngine.start(resolvedRequest)
            }
                .copy(
                    appState = if (request.appState.usesRawMihomoConfig()) {
                        request.appState
                    } else {
                        resolvedRequest.appState
                    },
                )
            val runtime = if (status.running) {
                resolvedRequest.appState.toMihomoTrafficStatsRuntime(
                    runMode = status.runMode ?: resolvedRequest.appState.runMode,
                    rawConfig = resolvedRequest.rawConfig,
                )
            } else {
                null
            }
            MihomoTrafficStatsNotificationService.reconcile(appContext, runtime)
            reconcileRootKeepAlive(status.runMode ?: resolvedRequest.appState.runMode, status.running, runtime != null)
            status
        } catch (error: Throwable) {
            MihomoTrafficStatsNotificationService.reconcile(appContext, null)
            RootKeepAliveService.reconcile(appContext, active = false)
            throw error
        }
    }

    private suspend fun stopUnlocked(
        preferredRunMode: Int? = null,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val engine = findEngineToStop(preferredRunMode)
        val stoppedMode = engine?.runMode
        when (engine) {
            is RootModeEngine -> engine.stop(trigger)
            else -> engine?.stop()
        }
        activeEngine = null
        MihomoTrafficStatsNotificationService.reconcile(appContext, null)
        RootKeepAliveService.reconcile(appContext, active = false)
        ProxyEngineStatus(running = false, runMode = stoppedMode)
    }

    private suspend fun stopRunModeUnlocked(
        runMode: Int,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val engine = runMode.engine()
        activeEngine
            ?.takeIf { active -> active !== engine }
            ?.let { active -> if (active is RootModeEngine) active.stop(trigger) else active.stop() }
        val status = if (engine is RootModeEngine) engine.stop(trigger) else engine.stop()
        activeEngine = null
        MihomoTrafficStatsNotificationService.reconcile(appContext, null)
        RootKeepAliveService.reconcile(appContext, active = false)
        status
    }

    private suspend fun shutdownRunModeUnlocked(
        runMode: Int,
        trigger: RootLifecycleTrigger = RootLifecycleTrigger.InternalResume,
    ): ProxyEngineStatus =
        withContext(Dispatchers.Default) {
            val engine = runMode.engine()
            activeEngine
                ?.takeIf { active -> active !== engine }
                ?.let { active -> if (active is RootModeEngine) active.shutdown(trigger) else active.stop() }
            val status = if (engine is RootModeEngine) engine.shutdown(trigger) else engine.stop()
            activeEngine = null
            MihomoTrafficStatsNotificationService.reconcile(appContext, null)
            RootKeepAliveService.reconcile(appContext, active = false)
            status
        }

    private suspend fun findEngineToStop(preferredRunMode: Int?): AndroidModeProxyEngine? {
        val preferredEngine = preferredRunMode?.engine()
        return activeEngine
            ?: preferredEngine?.takeIf { it.status().running }
            ?: preferredEngine?.takeIf { it.ownsRootRuntime() }
            ?: withRootRuntimeProbe(preferredRunMode) {
                rootEngines.firstOrNull { engine -> engine.status().running }
            }
            ?: vpnMihomoEngine.takeIf { it.status().running }
            ?: withRootRuntimeProbe(preferredRunMode) {
                rootEngines.firstOrNull { engine -> engine.ownsRuntime() }
            }
    }

    private suspend fun statusUnlocked(
        preferredRunMode: Int? = null,
        appState: app.AppState? = null,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        var rootStatus: ProxyEngineStatus? = null

        suspend fun probeRoot(engine: RootModeEngine): ProxyEngineStatus {
            rootStatus?.let { return it }
            return normalizeRootRuntimeStatus(engine.status(), ::rootRunMode).also { rootStatus = it }
        }

        fun accept(status: ProxyEngineStatus, source: AndroidModeProxyEngine): ProxyEngineStatus {
            activeEngine = status.runMode?.let(rootEnginesByRunMode::get) ?: source
            return status.withTrafficStatsReconciled(appState)
        }

        val active = activeEngine
        val activeStatus = when (active) {
            is RootModeEngine -> probeRoot(active)
            else -> active?.status()
        }
        if (activeStatus?.running == true) {
            return@withContext accept(activeStatus, checkNotNull(active))
        }

        var fallbackStatus = activeStatus
        preferredRunMode?.engine()?.let { preferredEngine ->
            val preferredStatus = if (preferredEngine is RootModeEngine) {
                probeRoot(preferredEngine)
            } else {
                preferredEngine.status()
            }
            if (preferredStatus.running) {
                return@withContext accept(preferredStatus, preferredEngine)
            }
            if (preferredStatus.rootSnapshot != null || fallbackStatus?.rootSnapshot == null) {
                fallbackStatus = preferredStatus
            }
        }

        if (rootStatus == null) {
            withRootRuntimeProbe(preferredRunMode) {
                val probeEngine = rootEngines.first()
                probeEngine to probeRoot(probeEngine)
            }?.let { (probeEngine, status) ->
                if (status.running) return@withContext accept(status, probeEngine)
                if (status.rootSnapshot != null && fallbackStatus?.rootSnapshot == null) {
                    fallbackStatus = status
                }
            }
        }

        if (active !== vpnMihomoEngine && preferredRunMode != RunModeVpnService) {
            val status = vpnMihomoEngine.status()
            if (status.running) return@withContext accept(status, vpnMihomoEngine)
        }

        activeEngine = null
        (fallbackStatus ?: ProxyEngineStatus(running = false, runMode = preferredRunMode))
            .withTrafficStatsReconciled(appState)
    }

    private fun rootRunMode(mode: RootRuntimeMode): Int? = rootEngines
        .firstOrNull { engine -> engine.daemonMode.wireValue == mode.wireValue }
        ?.runMode

    private fun Int.engine(): AndroidModeProxyEngine {
        return rootEnginesByRunMode[this] ?: vpnMihomoEngine
    }

    private suspend fun AndroidModeProxyEngine.ownsRootRuntime(): Boolean {
        return this is RootModeEngine && ownsRuntime()
    }

    /**
     * Status reads only stop the traffic stats service. They must not start it: entry points
     * such as the quick settings tile read the status right before a stop request, and the
     * visible UI restores the notification through TrafficStatsNotificationSynchronizer.
     */
    private fun ProxyEngineStatus.withTrafficStatsReconciled(appState: app.AppState?): ProxyEngineStatus {
        if (!running || appState?.enableTrafficStatsNotification == false) {
            MihomoTrafficStatsNotificationService.reconcile(appContext, null)
        }
        // The ROOT keep-alive is not released by status reads: during an automatic supervisor
        // restart the core is briefly not running and the process must stay protected.
        return this
    }

    private fun adoptResumedRoot(
        engine: RootModeEngine,
        status: ProxyEngineStatus,
        request: ProxyEngineStartRequest,
        validatedRequest: ProxyEngineStartRequest,
    ): ProxyEngineStatus {
        activeEngine = engine
        val resumed = status.copy(appState = request.appState)
        val resumedStats = validatedRequest.appState.toMihomoTrafficStatsRuntime(
            runMode = status.runMode ?: validatedRequest.appState.runMode,
            rawConfig = validatedRequest.rawConfig,
        )
        MihomoTrafficStatsNotificationService.reconcile(appContext, resumedStats)
        reconcileRootKeepAlive(
            status.runMode ?: validatedRequest.appState.runMode,
            status.running,
            resumedStats != null,
        )
        return resumed
    }

    /**
     * Returns null when this process already decided, the user explicitly stopped, or the running
     * mode is not the requested one. VPN never reaches here.
     */
    private suspend fun noteUnsettledRootBinaryAction(
        engine: RootModeEngine,
        origin: RootLifecycleTrigger,
    ): RootRunningBinaryAction? {
        if (explicitRootStop || rootBinaryIdentitySettled) return null
        val snapshot = engine.ownRunningSnapshotOrNull() ?: return RootRunningBinaryAction.Ignore
        if (snapshot.mode != engine.daemonMode) return null
        val action = rootRunningBinaryAction(
            explicitStop = false,
            alreadyAttempted = false,
            classification = engine.classifyInstalledBinaries(snapshot),
        )
        if (action != RootRunningBinaryAction.Ignore) rootBinaryIdentitySettled = true
        if (action == RootRunningBinaryAction.ReplaceOnce) logRootBinaryMismatch(origin)
        return action
    }

    /** ROOT has no VPN service; keep a foreground service while its core runs (no ROOT IO). */
    private fun reconcileRootKeepAlive(runMode: Int, running: Boolean, trafficStatsActive: Boolean) {
        RootKeepAliveService.reconcile(
            appContext,
            RootKeepAliveService.shouldRun(runMode, running, trafficStatsActive),
        )
    }
}

internal fun normalizeRootRuntimeStatus(
    probed: ProxyEngineStatus,
    runModeFor: (RootRuntimeMode) -> Int?,
): ProxyEngineStatus {
    val snapshot = probed.rootSnapshot ?: return probed
    val activeRunMode = runModeFor(snapshot.mode) ?: probed.runMode ?: return probed
    return ProxyEngineStatus.fromRootSnapshot(
        localOwner = RootRuntimeOwner.AsteriskMeta,
        runMode = activeRunMode,
        snapshot = snapshot,
    )
}

internal suspend fun <T> withRootRuntimeProbe(
    preferredRunMode: Int?,
    probe: suspend () -> T,
): T? = if (preferredRunMode == RunModeVpnService) null else probe()

internal fun shouldResumeRootBeforeResolvingPorts(
    explicitRestart: Boolean,
    hasOtherActiveEngine: Boolean,
    requestedIsRoot: Boolean,
): Boolean = !explicitRestart && !hasOtherActiveEngine && requestedIsRoot

internal fun shouldUsePreResolvedRootStart(
    explicitRestart: Boolean,
    resumeChecked: Boolean,
    nextEngineIsRoot: Boolean,
): Boolean = !explicitRestart && resumeChecked && nextEngineIsRoot
