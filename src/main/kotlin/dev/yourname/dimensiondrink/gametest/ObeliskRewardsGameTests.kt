package dev.yourname.dimensiondrink.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskRewardsGameTests {
    @GameTest(templateNamespace = "dimensiondrink", template = "bootstrap/empty", batch = "obelisk_rewards", timeoutTicks = 700)
    fun successful_run_buffers_rewards_and_shows_boss_bar(helper: GameTestHelper) {
        ObeliskGameTestSupport.successfulRunBuffersRewardsAndShowsBossBar(helper)
    }
}
