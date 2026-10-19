// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import java.io.File

/**
 * Mihomo home directory shared by the embedded VPN core (`Libclash.init(home)`) and the ROOT
 * core started by asteriskd (`mihomo -d <workingDirectory>`). Relative paths in profiles, such
 * as a tailscale outbound `state-dir`, resolve against this directory in both run modes, so a
 * persisted tsnet identity is the same identity regardless of the selected mode.
 */
internal const val MihomoHomeDirectoryName = "clash"

internal fun mihomoHomeDirectory(filesDirectory: File): File = File(filesDirectory, MihomoHomeDirectoryName)
