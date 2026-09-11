package com.bettercontent.dimensiondrink.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskRewardsGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_rewards", timeoutTicks = 700)
    fun successful_run_posts_return_event_and_clears_boss_bar(helper: GameTestHelper) {
        ObeliskGameTestSupport.successfulRunPostsReturnEventAndClearsBossBar(helper)
    }
}
