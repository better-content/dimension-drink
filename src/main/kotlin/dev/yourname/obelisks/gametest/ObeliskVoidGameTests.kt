package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskVoidGameTests {
    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_void", timeoutTicks = 700)
    fun void_fall_returns_player_and_cleans_up_run(helper: GameTestHelper) {
        ObeliskGameTestSupport.voidFallReturnsPlayerAndCleansUpRun(helper)
    }
}
