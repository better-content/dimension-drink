package com.bettercontent.dimensiondrink.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskVoidGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_void", timeoutTicks = 700)
    fun end_death_detaches_participant_and_preserves_survivor(helper: GameTestHelper) {
        ObeliskGameTestSupport.deathDisqualifiesParticipantWithoutClosingOtherPlayerRun(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_void_nether", timeoutTicks = 700)
    fun nether_death_detaches_participant_and_preserves_survivor(helper: GameTestHelper) {
        ObeliskGameTestSupport.deathDisqualifiesParticipantWithoutClosingOtherPlayerRun(helper, "nether")
    }
}
