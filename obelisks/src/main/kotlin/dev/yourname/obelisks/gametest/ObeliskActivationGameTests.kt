package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskActivationGameTests {
    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_activation", timeoutTicks = 2000)
    fun charged_obelisk_activates_run_and_returns_player(helper: GameTestHelper) {
        ObeliskGameTestSupport.chargedObeliskActivatesRunAndReturnsPlayer(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_activation", timeoutTicks = 1600)
    fun relocated_spawn_retargets_travel_warmup(helper: GameTestHelper) {
        ObeliskGameTestSupport.relocatedSpawnRetargetsTravelWarmup(helper)
    }
}
