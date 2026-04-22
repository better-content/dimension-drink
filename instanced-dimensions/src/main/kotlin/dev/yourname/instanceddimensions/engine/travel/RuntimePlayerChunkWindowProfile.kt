package dev.yourname.instanceddimensions.engine.travel

import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos

/**
 * Chunk coverage required to keep runtime travel stable for mods that perform
 * synchronous chunk scans around players during level ticks.
 */
object RuntimePlayerChunkWindowProfile {

    private const val MOD_SCAN_RADIUS = 5
    private const val BUFFER_RADIUS = 1
    const val TICKET_RADIUS: Int = MOD_SCAN_RADIUS + BUFFER_RADIUS

    fun coveredChunks(center: BlockPos): Set<ChunkPos> = coveredChunks(ChunkPos(center))

    fun coveredChunks(center: ChunkPos): Set<ChunkPos> {
        val chunks = linkedSetOf<ChunkPos>()
        for (chunkX in (center.x - TICKET_RADIUS)..(center.x + TICKET_RADIUS)) {
            for (chunkZ in (center.z - TICKET_RADIUS)..(center.z + TICKET_RADIUS)) {
                chunks += ChunkPos(chunkX, chunkZ)
            }
        }
        return chunks
    }
}
