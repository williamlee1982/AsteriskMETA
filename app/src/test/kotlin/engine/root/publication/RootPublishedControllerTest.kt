// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import engine.mihomo.MihomoControlConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RootPublishedControllerTest {
    @Test
    fun readsTopLevelControllerAndSecretOnly() {
        val published = RootPublishedControllerReader.parse(
            sequenceOf(
                "# external-controller: 127.0.0.1:1",
                "listeners:",
                "  - external-controller: 127.0.0.1:2",
                "external-controller: '127.0.0.1:35277'",
                "secret: \"s3\"",
            ),
        )
        assertEquals(RootPublishedController(35277, "s3"), published)
        assertFalse(published.toString().contains("s3"))
    }

    @Test
    fun missingOrInvalidControllerIsIgnored() {
        assertNull(RootPublishedControllerReader.parse(sequenceOf("secret: x")))
        assertNull(RootPublishedControllerReader.parse(sequenceOf("external-controller: 127.0.0.1:99999")))
    }

    @Test
    fun publishedControllerOverridesStaleAppPort() {
        val stale = MihomoControlConfig(port = 33279, secret = "s3")
        assertEquals(35277, stale.withPublishedRootController(RootPublishedController(35277, null)).port)
        assertEquals("s3", stale.withPublishedRootController(RootPublishedController(35277, null)).secret)
        assertSame(stale, stale.withPublishedRootController(null))
    }
}
