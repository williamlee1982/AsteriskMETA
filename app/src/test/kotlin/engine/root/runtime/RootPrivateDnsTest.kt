// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4: strict Private DNS (hostname) bypasses port-53 interception; warn about it. */
class RootPrivateDnsTest {
    @Test
    fun hostnameModeWarns() {
        val warning = rootPrivateDnsWarning("hostname", localDnsEnabled = true, blockEncryptedDns = false)
        assertTrue(warning!!.contains("Private DNS"))
        assertTrue(rootPrivateDnsWarning("hostname\n", localDnsEnabled = true, blockEncryptedDns = true)!!.contains("853"))
    }

    @Test
    fun offOrAutomaticOrNoLocalDnsDoesNotWarnUnlessBlocked() {
        assertNull(rootPrivateDnsWarning("off", localDnsEnabled = true, blockEncryptedDns = false))
        assertNull(rootPrivateDnsWarning("opportunistic", localDnsEnabled = true, blockEncryptedDns = true))
        assertNull(rootPrivateDnsWarning("hostname", localDnsEnabled = false, blockEncryptedDns = false))
        assertTrue(rootPrivateDnsWarning("opportunistic", localDnsEnabled = true, blockEncryptedDns = false)!!.contains("automatic"))
        assertNull(rootPrivateDnsWarning(null, localDnsEnabled = true, blockEncryptedDns = false))
    }
}
