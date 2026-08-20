package com.bettercontent.dimensiondrink.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskRuntimeGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 220)
    fun runtime_service_lists_and_finds_loaded_dimension_drink(helper: GameTestHelper) {
        ObeliskGameTestSupport.runtimeServiceListsAndFindsLoadedDimensionDrink(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun terrain_clearing_task_is_removed(helper: GameTestHelper) {
        ObeliskGameTestSupport.terrainClearingTaskIsRemoved(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 200)
    fun font_fluid_tank_accepts_only_blood_magic_life_essence(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontFluidTankAcceptsOnlyBloodMagicLifeEssence(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun font_regen_ignores_altar_copper_oxidation(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontRegenIgnoresAltarCopperOxidation(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 160)
    fun font_charges_while_loaded_and_catches_up_after_unload(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontChargesWhileLoadedAndCatchesUpAfterUnload(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun font_regeneration_clock_handles_legacy_active_and_future_state(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontRegenerationClockHandlesLegacyActiveAndFutureState(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun font_axe_scrapes_altar_copper_oxidation(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontAxeScrapesAltarCopperOxidation(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 260)
    fun font_passively_renews_nearby_copper_oxidation(helper: GameTestHelper) {
        ObeliskGameTestSupport.fontPassivelyRenewsNearbyCopperOxidation(helper)
    }
}
