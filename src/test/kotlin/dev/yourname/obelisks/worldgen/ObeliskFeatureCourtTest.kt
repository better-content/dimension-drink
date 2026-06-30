package dev.yourname.obelisks.worldgen

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObeliskFeatureCourtTest {
    @Test
    fun dryCourtDecorationStaysDeadAndPotted() {
        val block = ObeliskFeature.courtPotBlockForTests(false, BlockPos(0, 64, 0))
        assertEquals(Blocks.POTTED_DEAD_BUSH, block, "Dry reliquary court dressing should stay in dead potted form")
    }

    @Test
    fun wetCourtDecorationUsesLivingPottedVariants() {
        val variants = buildSet {
            for (index in 0..7) {
                add(ObeliskFeature.courtPotBlockForTests(true, BlockPos(index, 64, index * 3)))
            }
        }
        assertTrue(variants.isNotEmpty(), "Wet reliquary courts should choose living potted decoration")
        assertTrue(variants.all { it == Blocks.POTTED_FERN || it == Blocks.POTTED_AZALEA || it == Blocks.POTTED_FLOWERING_AZALEA || it == Blocks.POTTED_MANGROVE_PROPAGULE })
    }
}
