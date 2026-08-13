package com.bettercontent.dimensiondrink.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskRunLifecycleGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_run", timeoutTicks = 1600)
    fun run_creation_persists_canonical_site_metadata(helper: GameTestHelper) {
        ObeliskGameTestSupport.runCreationPersistsOwnedInstanceMetadata(helper)
    }
}
