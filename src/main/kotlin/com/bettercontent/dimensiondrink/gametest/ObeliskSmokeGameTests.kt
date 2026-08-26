package com.bettercontent.dimensiondrink.gametest

import com.bettercontent.dimensiondrink.runtime.run.RunRegistry
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskSmokeGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 1200)
    fun headless_player_round_trip(helper: GameTestHelper) {
        ObeliskGameTestSupport.smokeHeadlessRoundTrip(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 1200)
    fun entry_cost_and_active_drain_close_dry_run(helper: GameTestHelper) {
        ObeliskGameTestSupport.smokeEntryCostAndActiveDrainCloseDryRun(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 2400)
    fun repeated_round_trips_reuse_site_without_session_leaks(helper: GameTestHelper) {
        ObeliskGameTestSupport.smokeRepeatedRoundTrips(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 1200)
    fun external_dimension_change_cleans_session(helper: GameTestHelper) {
        ObeliskGameTestSupport.smokeExternalDimensionCleanup(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 1200)
    fun shared_run_closes_only_after_final_player_returns(helper: GameTestHelper) {
        ObeliskGameTestSupport.secondPlayerJoinsExistingRunAndBothReturn(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 1200)
    fun final_player_logout_cleans_session(helper: GameTestHelper) {
        ObeliskGameTestSupport.smokeLogoutCleanup(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 1200)
    fun final_player_death_cleans_session(helper: GameTestHelper) {
        ObeliskGameTestSupport.smokeDeathCleanup(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "font_smoke", timeoutTicks = 600)
    fun server_console_smoke_command(helper: GameTestHelper) {
        val server = helper.level.server
        val result = server.commands.performPrefixedCommand(server.createCommandSourceStack(), "font smoke run end 12")
        helper.assertTrue(result == 1, "Expected console smoke command to pass")
        val statusResult = server.commands.performPrefixedCommand(server.createCommandSourceStack(), "font smoke status")
        helper.assertTrue(statusResult == 1, "Expected console smoke status command to pass")
        val cleanupResult = server.commands.performPrefixedCommand(server.createCommandSourceStack(), "font smoke cleanup")
        helper.assertTrue(cleanupResult >= 1, "Expected console smoke cleanup command to pass")
        helper.assertTrue(
            RunRegistry.snapshot().none { it.originLevelKey == null && it.definitionId == "end" },
            "Expected console smoke command to leave no synthetic run"
        )
        helper.succeed()
    }
}
