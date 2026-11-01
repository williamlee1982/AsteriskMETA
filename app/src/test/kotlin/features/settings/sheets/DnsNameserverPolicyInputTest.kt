// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsNameserverPolicyInputTest {
    @Test
    fun starAndTailscaleServerAreAccepted() {
        assertNull(nameserverPolicyInputError("*=>tailscale://TS", InvalidPolicy, InvalidServer))
        assertNull(nameserverPolicyInputError("wbvm=>tailscale://TS", InvalidPolicy, InvalidServer))
        assertNull(nameserverPolicyInputError("+.ts.net=>tailscale://TS", InvalidPolicy, InvalidServer))
        assertNull(nameserverPolicyInputError("+.internal.crop.com=>10.0.0.1", InvalidPolicy, InvalidServer))
        assertNull(nameserverPolicyInputError("geosite:cn=>system", InvalidPolicy, InvalidServer))
        assertNull(nameserverPolicyInputError("www.baidu.com=>114.114.114.114", InvalidPolicy, InvalidServer))
    }

    @Test
    fun invalidWildcardIsAPolicyErrorNotANameserver() {
        assertEquals(InvalidPolicy, nameserverPolicyInputError("+=>system", InvalidPolicy, InvalidServer))
        assertEquals(InvalidPolicy, nameserverPolicyInputError("*a.example=>1.1.1.1", InvalidPolicy, InvalidServer))
        assertEquals(InvalidPolicy, nameserverPolicyInputError("=>tailscale://TS", InvalidPolicy, InvalidServer))
        assertEquals(InvalidServer, nameserverPolicyInputError("*=>not-a-scheme://TS", InvalidPolicy, InvalidServer))
    }

    private companion object {
        const val InvalidPolicy = "invalid-policy"
        const val InvalidServer = "invalid-server"
    }
}
