// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CoreLogRedactionTest {
    private val secretUrl = "https://login.example.test/a/0123456789abcdef"

    private class Repository : InMemoryCoreLogRepository() {
        fun restore(entries: List<CoreLogEntry>) = replaceEntries(entries)
    }

    @Test
    fun appendedAndRestoredEntriesAreRedacted() {
        val repository = Repository()
        repository.append("info", "[Tailscale] To authenticate, visit: $secretUrl", null)
        repository.append("info", "plain line", null)
        assertFalse(repository.entries.value.first().message.contains("0123456789abcdef"))
        assertEquals("plain line", repository.entries.value.last().message)

        repository.restore(listOf(CoreLogEntry(1, null, "info", "auth-key: tskey-auth-fixture123 $secretUrl")))
        val restored = repository.entries.value.single().message
        assertFalse(restored.contains("0123456789abcdef"))
        assertFalse(restored.contains("fixture123"))
    }

    @Test
    fun exportRedactsAgain() {
        val exported = logEntriesForExport(listOf(CoreLogEntry(1, null, "info", "Authorization: Bearer abc.def.ghi")))
        assertFalse(exported.single().message.contains("abc.def.ghi"))
    }
}
