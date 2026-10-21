// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import data.AndroidAppStateStore
import engine.root.keepalive.RootKeepAliveService
import engine.stats.MihomoTrafficStatsNotificationService
import engine.stats.MihomoTrafficStatsRuntime
import engine.stats.toMihomoTrafficStatsRuntime
import engine.mihomo.raw.loadSelectedRawConfig
import engine.mihomo.raw.usesRawMihomoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TrafficStatsNotificationSynchronizer(
    stateStore: AndroidAppStateStore,
) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(appContext, stateStore) {
        var activeRuntime: MihomoTrafficStatsRuntime? = null
        var rootKeepAlive: Boolean? = null
        stateStore.state.collect { appState ->
            val runtime = if (appState.proxyRunning) {
                val rawConfig = if (appState.usesRawMihomoConfig()) {
                    withContext(Dispatchers.IO) {
                        appContext.loadSelectedRawConfig(appState)?.snapshot
                    }
                } else {
                    null
                }
                appState.toMihomoTrafficStatsRuntime(rawConfig = rawConfig)
            } else {
                null
            }
            val keepAlive = RootKeepAliveService.shouldRun(appState.runMode, appState.proxyRunning, runtime != null)
            if (keepAlive != rootKeepAlive) {
                rootKeepAlive = keepAlive
                RootKeepAliveService.reconcile(appContext, keepAlive)
            }
            if (runtime == activeRuntime) {
                return@collect
            }
            activeRuntime = runtime
            MihomoTrafficStatsNotificationService.reconcile(appContext, runtime)
        }
    }
}
