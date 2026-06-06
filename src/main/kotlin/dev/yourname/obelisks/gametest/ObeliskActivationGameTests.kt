package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskActivationGameTests {
    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_activation_player", timeoutTicks = 3600)
    fun charged_obelisk_activates_run_and_returns_player(helper: GameTestHelper) {
        ObeliskGameTestSupport.chargedObeliskActivatesRunAndReturnsPlayer(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_activation_relocated", timeoutTicks = 3600)
    fun relocated_spawn_normalizes_canonical_target(helper: GameTestHelper) {
        ObeliskGameTestSupport.relocatedSpawnRetargetsTravelWarmup(helper)
    }
}
