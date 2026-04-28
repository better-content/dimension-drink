package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
class ObeliskRuntimeGameTests {
    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 220)
    fun runtime_service_lists_and_finds_loaded_obelisks(helper: GameTestHelper) {
        ObeliskGameTestSupport.runtimeServiceListsAndFindsLoadedObelisks(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_runtime", timeoutTicks = 120)
    fun scar_task_skips_unloaded_chunks(helper: GameTestHelper) {
        ObeliskGameTestSupport.scarTaskSkipsUnloadedChunks(helper)
    }
}
