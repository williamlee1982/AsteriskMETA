package app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetInboundSettingTest {
    @Test
    fun defaultIsOffAndStateRoundTrips() {
        assertFalse(AppState().tailnetInboundForwardsEnabled)
        val restored = AppState().copy(tailnetInboundForwardsEnabled = true)
        assertTrue(restored.tailnetInboundForwardsEnabled)
    }
}
