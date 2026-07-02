package dev.yourname.obelisks.worldgen

import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObeliskFeatureCourtTest {
    @Test
    fun courtDecorationStaysContainedAndPotted() {
        val blockId = pickCourtPotBlockId(false, BlockPos(0, 64, 0))
        assertTrue(
            blockId == "minecraft:potted_fern" ||
                blockId == "minecraft:potted_azalea_bush" ||
                blockId == "minecraft:potted_flowering_azalea_bush",
            "Reliquary court dressing should stay in restrained contained growth variants"
        )
    }

    @Test
    fun wetCourtDecorationUsesLivingPottedVariants() {
        val variants = buildSet {
            for (index in 0..7) {
                add(pickCourtPotBlockId(true, BlockPos(index, 64, index * 3)))
            }
        }
        assertTrue(variants.isNotEmpty(), "Wet reliquary courts should choose living potted decoration")
        assertTrue(variants.all {
            it == "minecraft:potted_fern" ||
                it == "minecraft:potted_azalea_bush" ||
                it == "minecraft:potted_flowering_azalea_bush"
        })
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
