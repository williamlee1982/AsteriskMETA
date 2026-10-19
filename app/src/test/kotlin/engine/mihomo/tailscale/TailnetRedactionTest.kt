// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TailnetRedactionTest {
    // Fixtures are synthetic and assembled at runtime; no real login URL or key is stored.
    private val officialLoginUrl = "https://" + "login.tailscale.com" + "/a/" + "0fixture0token0"
    private val selfHostedLoginUrl = "https://control.example.net/a/fixture-token-123"
    private val authKey = "tskey-" + "auth-kFIXTURE-0000"

    @Test
    fun loginUrlsOfAnyControlServerAreRedacted() {
        val text = "To authenticate, visit: $officialLoginUrl and $selfHostedLoginUrl)."
        val redacted = redactTailnetSecrets(text)
        assertEquals("To authenticate, visit: <redacted-login-url> and <redacted-login-url>).", redacted)
    }

    @Test
    fun keysTokensAndCookiesAreRedacted() {
        val text = """
            auth-key: $authKey
            "authKey": "fixture-auth-value"
            Authorization: Bearer abc.def-ghi
            Cookie: session=fixture; other=1
            nodekey:${"0123456789abcdef".repeat(4)}
        """.trimIndent()
        val redacted = redactTailnetSecrets(text)

        for (secret in listOf(authKey, "fixture-auth-value", "abc.def-ghi", "session=fixture", "0123456789abcdef")) {
            assertFalse("leaked $secret in $redacted", redacted.contains(secret))
        }
        assertEquals(redacted, redactTailnetSecrets(redacted))
    }

    @Test
    fun ordinaryDiagnosticsStayReadable() {
        val text = "TS bypass 100.64.0.0/10; peer 100.101.102.103:22 via 127.0.0.1:23456 https://example.com/about"
        assertEquals(text, redactTailnetSecrets(text))
    }

    @Test
    fun backendErrorsAreRedactedAndBounded() {
        val error = tailscaleBackendError("login failed: $officialLoginUrl " + "x".repeat(500))
        assertFalse(error.redactedMessage.contains("0fixture0token0"))
        assertEquals(240, error.redactedMessage.length)
    }
}
