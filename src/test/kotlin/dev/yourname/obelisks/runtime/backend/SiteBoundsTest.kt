package dev.yourname.obelisks.runtime.backend

import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteBoundsTest {
    @Test
    fun containsBlockPositionUsesInclusiveBounds() {
        val bounds = SiteBounds(minX = 0, minY = 10, minZ = 0, maxX = 15, maxY = 20, maxZ = 15)
        assertTrue(bounds.contains(BlockPos(0, 10, 0)))
        assertTrue(bounds.contains(BlockPos(15, 20, 15)))
        assertFalse(bounds.contains(BlockPos(-1, 10, 0)))
        assertFalse(bounds.contains(BlockPos(16, 10, 0)))
    }

    @Test
    fun containsChunkChecksIntersection() {
        val bounds = SiteBounds(minX = 0, minY = -64, minZ = 0, maxX = 31, maxY = 320, maxZ = 31)
        assertTrue(bounds.contains(ChunkPos(0, 0)))
        assertTrue(bounds.contains(ChunkPos(1, 1)))
        assertFalse(bounds.contains(ChunkPos(3, 3)))
    }
}
