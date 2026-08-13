package com.bettercontent.dimensiondrink.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskTemplateMappingGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_template", timeoutTicks = 400)
    fun explicit_target_dimension_selects_runtime_dimension(helper: GameTestHelper) {
        ObeliskGameTestSupport.instanceTemplateIdSelectsRuntimeInstanceTemplate(helper)
    }
}
