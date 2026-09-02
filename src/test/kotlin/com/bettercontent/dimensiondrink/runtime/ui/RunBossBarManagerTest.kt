package com.bettercontent.dimensiondrink.runtime.ui

import net.minecraft.world.BossEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunBossBarManagerTest {
    @Test
    fun `bar is hidden until charge falls below ninety percent`() {
        assertNull(RunBossBarManager.presentation(1.0))
        assertNull(RunBossBarManager.presentation(0.90))
        assertEquals(0.899f, RunBossBarManager.presentation(0.899)?.progress)
    }

    @Test
    fun `bar reports remaining charge with configured warning colors`() {
        assertEquals(
            FontBossBarPresentation(0.75f, "Font Charge: 75%", BossEvent.BossBarColor.GREEN),
            RunBossBarManager.presentation(0.75)
        )
        assertEquals(BossEvent.BossBarColor.YELLOW, RunBossBarManager.presentation(0.40)?.color)
        assertEquals(BossEvent.BossBarColor.RED, RunBossBarManager.presentation(0.20)?.color)
    }
}
