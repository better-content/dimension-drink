package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskRunLifecycleGameTests {
    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_run", timeoutTicks = 1600)
    fun run_creation_persists_owned_instance_metadata(helper: GameTestHelper) {
        ObeliskGameTestSupport.runCreationPersistsOwnedInstanceMetadata(helper)
    }
}
