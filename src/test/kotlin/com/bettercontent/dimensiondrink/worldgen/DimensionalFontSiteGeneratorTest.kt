package com.bettercontent.dimensiondrink.worldgen

import com.bettercontent.dimensiondrink.worldgen.structure.DimensionalFontSiteGenerator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DimensionalFontSiteGeneratorTest {
    @Test
    fun currentLayoutAddsTwoStairRungsOnEveryCardinalApproach() {
        assertEquals(3, DimensionalFontSiteGenerator.LAYOUT_VERSION)
        val center = BlockPos(8, 70, 8)
        Direction.Plane.HORIZONTAL.forEach { direction ->
            assertEquals(
                listOf(center.relative(direction, 2).above(), center.relative(direction).above(2)),
                DimensionalFontSiteGenerator.altarApproachStairPositions(center, direction)
            )
        }
    }

    @Test
    fun centerCourtMustFitItsStartChunk() {
        assertTrue(DimensionalFontSiteGenerator.centerFitsStartChunk(BlockPos(5, 70, 5)))
        assertTrue(DimensionalFontSiteGenerator.centerFitsStartChunk(BlockPos(10, 70, 10)))
        assertFalse(DimensionalFontSiteGenerator.centerFitsStartChunk(BlockPos(4, 70, 8)))
        assertFalse(DimensionalFontSiteGenerator.centerFitsStartChunk(BlockPos(8, 70, 11)))
    }

    @Test
    fun pathColumnsAreBoundedAndCardinal() {
        assertFalse(DimensionalFontSiteGenerator.isPathColumn(5, 0))
        assertTrue(DimensionalFontSiteGenerator.isPathColumn(6, 0))
        assertTrue(DimensionalFontSiteGenerator.isPathColumn(-1, 24))
        assertFalse(DimensionalFontSiteGenerator.isPathColumn(2, 12))
        assertFalse(DimensionalFontSiteGenerator.isPathColumn(0, 25))
    }

    @Test
    fun coordinateChoicesDoNotDependOnIterationOrder() {
        val positions = listOf(10 to 12, -4 to 90, 128 to -33, 0 to 0)
        val forward = positions.associateWith { (x, z) ->
            DimensionalFontSiteGenerator.coordinateHash(9012L, x, z)
        }
        val reverse = positions.reversed().associateWith { (x, z) ->
            DimensionalFontSiteGenerator.coordinateHash(9012L, x, z)
        }
        assertEquals(forward, reverse)
        assertNotEquals(forward[10 to 12], forward[-4 to 90])
    }
}
