package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskVoidGameTests {
    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_void", timeoutTicks = 700)
    fun death_disqualifies_player_and_respawn_returns_to_font(helper: GameTestHelper) {
        ObeliskGameTestSupport.deathDisqualifiesPlayerAndRespawnReturnsToFont(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_void_nether", timeoutTicks = 700)
    fun death_disqualifies_player_and_respawn_returns_to_nether_font(helper: GameTestHelper) {
        ObeliskGameTestSupport.deathDisqualifiesPlayerAndRespawnReturnsToFont(helper, "nether")
    }
}
