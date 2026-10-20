// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

/** Non-secret profile declaration of one TCP inbound forward. */
internal data class TailnetInboundForward(
    val name: String,
    val network: String,
    val listen: Int,
    val target: String,
    /** Invalid profile entries remain visible to the card instead of being silently dropped. */
    val error: String? = null,
) {
    val isValid: Boolean get() = error == null
}
