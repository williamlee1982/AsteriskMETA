// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

/**
 * Redacts Tailscale secrets from diagnostic text before it is displayed, logged or shared.
 *
 * Covered: interactive login/authorization URLs of any control server (`/a/<token>` paths),
 * Tailscale auth and API keys (`tskey-...`), node/machine/disco key material, bearer tokens,
 * cookie headers and `auth-key` assignments in YAML/JSON. The function is pure and idempotent.
 */
internal fun redactTailnetSecrets(text: String): String {
    // Every log line passes through here; skip the regexes when no rule can match.
    if (RedactionMarkers.none { text.contains(it, ignoreCase = true) }) return text
    var result = text
    RedactionRules.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
    return result
}

internal const val RedactedPlaceholder = "<redacted>"

private val RedactionMarkers = listOf("/a/", "tskey-", "key:", "nlpriv:", "bearer", "cookie", "auth")

private val RedactionRules: List<Pair<Regex, String>> = listOf(
    Regex("""\bhttps?://[^\s/'"<>]+/a/[^\s'"<>)]+""", RegexOption.IGNORE_CASE) to "<redacted-login-url>",
    Regex("""\btskey-[A-Za-z0-9_-]+""") to "tskey-$RedactedPlaceholder",
    Regex("""\b(privkey|nodekey|mkey|discokey|nlpriv):[0-9a-fA-F]{16,}""") to "$1:$RedactedPlaceholder",
    Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]+""") to "$1$RedactedPlaceholder",
    Regex("""(?im)^(\s*(?:set-)?cookie\s*:\s*).+$""") to "$1$RedactedPlaceholder",
    Regex("""(?i)(["']?auth[-_]?key["']?\s*[:=]\s*)(?!<redacted>|tskey-<redacted>)(["']?)[^\s,'"}]+(["']?)""") to
        "$1$2$RedactedPlaceholder$3",
)
