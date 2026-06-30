package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskRuntimeGameTests {
    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 220)
    fun runtime_service_lists_and_finds_loaded_obelisks(helper: GameTestHelper) {
        ObeliskGameTestSupport.runtimeServiceListsAndFindsLoadedObelisks(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun terrain_clearing_task_is_removed(helper: GameTestHelper) {
        ObeliskGameTestSupport.terrainClearingTaskIsRemoved(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 200)
    fun font_fluid_tank_accepts_only_blood_magic_life_essence(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontFluidTankAcceptsOnlyBloodMagicLifeEssence(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun grave_soil_charging_state_follows_font_charging(helper: GameTestHelper) {
        ObeliskGameTestSupport.graveSoilChargingStateFollowsFontCharging(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun font_regen_responds_to_altar_copper_oxidation(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontRegenRespondsToAltarCopperOxidation(helper)
    }
}
