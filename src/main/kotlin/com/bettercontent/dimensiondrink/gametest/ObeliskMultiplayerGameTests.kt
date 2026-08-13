package com.bettercontent.dimensiondrink.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskMultiplayerGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_multiplayer", timeoutTicks = 800)
    fun second_player_joins_existing_run_and_both_return(helper: GameTestHelper) {
        ObeliskGameTestSupport.secondPlayerJoinsExistingRunAndBothReturn(helper)
    }
}
