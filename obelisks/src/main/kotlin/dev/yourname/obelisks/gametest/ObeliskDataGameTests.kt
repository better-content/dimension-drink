package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskDataGameTests {
    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 400)
    fun reward_tables_follow_definition_with_shared_instance_template(helper: GameTestHelper) {
        ObeliskGameTestSupport.rewardTablesFollowDefinitionWithSharedTemplate(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_command_refreshes_definition_data(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadCommandRefreshesDefinitionData(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun worldgen_families_produce_distinct_site_shapes(helper: GameTestHelper) {
        ObeliskGameTestSupport.worldgenFamiliesProduceDistinctSiteShapes(helper)
    }
}
