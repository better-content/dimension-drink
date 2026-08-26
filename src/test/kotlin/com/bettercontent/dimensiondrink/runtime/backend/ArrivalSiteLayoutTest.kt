package com.bettercontent.dimensiondrink.runtime.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrivalSiteLayoutTest {
    @Test
    fun arrivalFloorRemainsFiveByFive() {
        val offsets = ArrivalSiteLayout.floorOffsets()
        assertEquals(25, offsets.size, "Arrival floor should remain a 5x5 oxidized copper pad")
        assertTrue(offsets.any { it.x == 0 && it.y == 0 && it.z == 0 }, "Arrival floor should still center on the return font")
        assertTrue(offsets.all { it.y == 0 && it.x in -2..2 && it.z in -2..2 }, "Arrival floor offsets should stay within the 5x5 footprint")
    }

    @Test
    fun verdigrisParticlesOnlyTargetUnwaxedFullyOxidizedCopper() {
        val ids = ArrivalSiteLayout.verdigrisCopperIds()
        assertTrue("minecraft:oxidized_copper" in ids)
        assertTrue("minecraft:oxidized_cut_copper" in ids)
        assertTrue("minecraft:oxidized_cut_copper_stairs" in ids)
        assertTrue("minecraft:oxidized_cut_copper_slab" in ids)
        assertFalse("minecraft:waxed_oxidized_copper" in ids)
        assertFalse("minecraft:weathered_copper" in ids)
    }
}
