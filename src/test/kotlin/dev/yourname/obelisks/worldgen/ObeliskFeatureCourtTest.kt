package dev.yourname.obelisks.worldgen

import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObeliskFeatureCourtTest {
    @Test
    fun courtDecorationUsesModdedPottedPlantsOutsideDryBiomes() {
        val blockId = pickCourtPotBlockId(false, BlockPos(0, 64, 0))
        assertTrue(
            !blockId.startsWith("minecraft:"),
            "Reliquary court dressing should prefer modded potted plants outside dry biomes"
        )
        assertTrue(blockId.substringAfter(':').startsWith("potted_"))
    }

    @Test
    fun dryCourtDecorationFallsBackToDeadBushes() {
        val variants = buildSet {
            for (index in 0..7) {
                add(pickCourtPotBlockId(true, BlockPos(index, 64, index * 3)))
            }
        }
        assertEquals(setOf("minecraft:potted_dead_bush"), variants)
    }

    @Test
    fun altarThresholdStairsOnlyAppearWhenCourtSideStepsUp() {
        assertFalse(
            shouldUseAltarThresholdStair(altarY = 64, outerGroundY = 64, isActiveEntry = true),
            "Flat altar court seams should stay blocky instead of placing same-level stairs"
        )
        assertTrue(
            shouldUseAltarThresholdStair(altarY = 64, outerGroundY = 63, isActiveEntry = true),
            "Lower entry ground should still use a stair at the altar threshold"
        )
        assertFalse(
            shouldUseAltarThresholdStair(altarY = 64, outerGroundY = 63, isActiveEntry = false),
            "Inactive altar sides should not place threshold stairs"
        )
    }
}
