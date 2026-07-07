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
    fun altarUpperThresholdStairsAppearOnActiveEntries() {
        assertTrue(
            shouldUseAltarUpperThresholdStair(isActiveEntry = true),
            "Active altar entries should always expose an upper stair onto the top tier"
        )
        assertFalse(
            shouldUseAltarUpperThresholdStair(isActiveEntry = false),
            "Inactive altar sides should stay blocky on the upper threshold"
        )
    }

    @Test
    fun altarLowerThresholdStairsOnlyAppearWhenCourtSideStepsUp() {
        assertFalse(
            shouldUseAltarLowerThresholdStair(altarY = 64, outerGroundY = 64, isActiveEntry = true),
            "Flat altar court seams should not add an extra outer stair row"
        )
        assertTrue(
            shouldUseAltarLowerThresholdStair(altarY = 64, outerGroundY = 63, isActiveEntry = true),
            "Lower entry ground should still use an outer stair when the court floor drops"
        )
        assertFalse(
            shouldUseAltarLowerThresholdStair(altarY = 64, outerGroundY = 63, isActiveEntry = false),
            "Inactive altar sides should not place lower threshold stairs"
        )
    }
}
