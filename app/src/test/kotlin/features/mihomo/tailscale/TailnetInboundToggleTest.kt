package features.mihomo.tailscale

import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.TailnetOutboundKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetInboundToggleTest {
    @Test
    fun switchEnabledOnlyWhenEffectiveProfileHasForwards() {
        assertTrue(tailnetInboundTogglePresentation(enabled = false, hasForwards = true).available)
        assertFalse(tailnetInboundTogglePresentation(enabled = false, hasForwards = false).available)
        assertTrue(tailnetInboundTogglePresentation(enabled = true, hasForwards = true).enabled)
    }

    @Test
    fun providerOnlyForwardsDoNotEnableToggle() {
        val provider = TailnetCardOutbound(
            MihomoTailscaleOutbound(
                name = "TS",
                hostname = null,
                stateDir = null,
                hasAuthKey = false,
                ephemeral = false,
                usesCustomControlUrl = false,
                provider = "subscription",
                inboundForwards = listOf(
                    engine.mihomo.tailscale.TailnetInboundForward("ssh", "tcp", 20022, "127.0.0.1:22"),
                ),
            ),
            TailnetOutboundSource.RunningCore,
        )

        assertFalse(tailnetInboundForwardsAvailable(TailnetCardOutbounds(listOf(provider), null)))
    }

    // L3: the switch shows the effective state, and an unsupported core disables it unless it is on.
    @Test
    fun switchReflectsAvailability() {
        assertFalse(tailnetInboundTogglePresentation(enabled = true, hasForwards = false).enabled)
        val unsupportedOff = tailnetInboundTogglePresentation(enabled = false, hasForwards = true, coreUnsupported = true)
        assertFalse(unsupportedOff.available)
        val unsupportedOn = tailnetInboundTogglePresentation(enabled = true, hasForwards = true, coreUnsupported = true)
        assertTrue("an enabled switch must stay operable so it can be turned off", unsupportedOn.available)
    }
}
