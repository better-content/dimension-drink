package dev.yourname.dimensiondrink.worldgen

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

    @Test
    fun altarLowerThresholdStairUsesOuterGroundHeight() {
        assertEquals(
            66,
            altarLowerThresholdY(altarY = 66, outerGroundY = 64, isActiveEntry = true),
            "Lower altar entry stairs should sit on the altar threshold Y so the final rung reaches the altar deck"
        )
        assertEquals(
            null,
            altarLowerThresholdY(altarY = 66, outerGroundY = 66, isActiveEntry = true),
            "Flat or raised outer ground should not emit a lower stair"
        )
    }

    @Test
    fun altarApproachStairsOnlyFillLowerRungsBelowThreshold() {
        assertEquals(
            null,
            altarApproachStairY(altarY = 66, groundY = 65, maxDrop = 2),
            "One-block drops are handled by the altar threshold stair and should not repeat outside it"
        )
        assertEquals(
            65,
            altarApproachStairY(altarY = 66, groundY = 64, maxDrop = 2),
            "Two-block drops should get one lower approach stair before the altar threshold stair"
        )
        assertEquals(
            null,
            altarApproachStairY(altarY = 66, groundY = 63, maxDrop = 2),
            "Drops beyond the supported foundation budget should not create disconnected stairs"
        )
    }

    @Test
    fun altarSconcesPreferWallMountedSupplementariesBlock() {
        assertEquals(
            listOf("supplementaries:sconce_wall", "supplementaries:sconce"),
            preferredAltarSconceBlockIds(),
            "Altar sconces should prefer the dedicated wall-mounted Supplementaries block before any floor fallback"
        )
    }
}
