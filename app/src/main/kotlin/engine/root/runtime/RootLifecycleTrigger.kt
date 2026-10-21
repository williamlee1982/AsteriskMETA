// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

/** Who asked for a ROOT start, stop, or restart. Logged; never used as an authorization. */
internal enum class RootLifecycleTrigger(val wire: String) {
    /** Home power button, settings, profile/subscription restart, run-mode switch. */
    UiButton("ui_button"),

    /** `PROXY_START` / `PROXY_STOP` / `PROXY_TOGGLE`. */
    Broadcast("broadcast"),

    /** Quick settings tile. */
    Tile("tile"),

    /** Resume-if-running and other in-process orchestration. */
    InternalResume("internal_resume"),

    /** Installed native libraries do not match the running root binaries. */
    UpgradeMismatch("upgrade_mismatch"),
}
