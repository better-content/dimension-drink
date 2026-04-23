package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskTemplateMappingGameTests {
    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_template", timeoutTicks = 400)
    fun legacy_instance_template_id_selects_target_dimension(helper: GameTestHelper) {
        ObeliskGameTestSupport.instanceTemplateIdSelectsRuntimeInstanceTemplate(helper)
    }
}
