package dev.yourname.obelisks.runtime.run

import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import kotlin.math.cos
import kotlin.math.sin

object SpawnPlatformGenerator {
    private const val PLATFORM_SET_FLAGS = 2
    private const val MAX_SEARCH_RADIUS = 32
    private const val SEARCH_STEP = 8
    private const val ANGLE_STEP_DEGREES = 45
    private const val MIN_CANDIDATES = 3
    private const val PREFERRED_CANDIDATES = 10

    fun ensurePlatform(level: ServerLevel): BlockPos? {
        val floorCenter = findSuitableLocation(level, level.sharedSpawnPos)
            ?: fallbackLoadedLocation(level, level.sharedSpawnPos)
            ?: return null
        val chunk = level.chunkSource.getChunkNow(floorCenter.x shr 4, floorCenter.z shr 4) ?: return null
        buildPlatform(level, floorCenter)
        chunk.isUnsaved = true
        val spawnPos = floorCenter.above()
        level.setDefaultSpawnPos(spawnPos.above(), 0.0F)
        return spawnPos.above()
    }

    private fun findSuitableLocation(level: ServerLevel, searchCenter: BlockPos): BlockPos? {
        val minY = level.minBuildHeight + 10
        val maxY = level.maxBuildHeight - 15
        val candidates = mutableListOf<Pair<BlockPos, Int>>()

        for (radius in 0..MAX_SEARCH_RADIUS step SEARCH_STEP) {
            for (angle in 0 until 360 step ANGLE_STEP_DEGREES) {
                val radians = Math.toRadians(angle.toDouble())
                val x = searchCenter.x + (radius * cos(radians)).toInt()
                val z = searchCenter.z + (radius * sin(radians)).toInt()
                if (!isFootprintLoaded(level, x, z)) {
                    continue
                }

                for (y in minY until maxY) {
                    val candidate = BlockPos(x, y, z)
                    if (!isSuitableForPlatform(level, candidate)) {
                        continue
                    }

                    candidates += candidate to calculateSolidGroundScore(level, candidate)
                    if (candidates.size >= MIN_CANDIDATES && (radius > 16 || candidates.size >= PREFERRED_CANDIDATES)) {
                        return candidates.maxByOrNull { it.second }?.first
                    }
                }
            }
        }

        return candidates.maxByOrNull { it.second }?.first
    }

    private fun fallbackLoadedLocation(level: ServerLevel, searchCenter: BlockPos): BlockPos? {
        val chunkX = searchCenter.x shr 4
        val chunkZ = searchCenter.z shr 4
        if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) {
            return null
        }
        return BlockPos((chunkX shl 4) + 8, ObeliskConstants.PLATFORM_Y_LEVEL, (chunkZ shl 4) + 8)
    }

    private fun isSuitableForPlatform(level: ServerLevel, floorCenter: BlockPos): Boolean {
        val radius = ObeliskConstants.PLATFORM_RADIUS
        if (!isFootprintLoaded(level, floorCenter.x, floorCenter.z)) {
            return false
        }

        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val groundPos = floorCenter.offset(x, 0, z)
                if (level.getBlockState(groundPos).block == Blocks.BEDROCK) {
                    return false
                }
            }
        }

        for (y in 1..3) {
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    if (!level.getBlockState(floorCenter.offset(x, y, z)).isAir) {
                        return false
                    }
                }
            }
        }

        for (y in 0 downTo -5) {
            val checkPos = floorCenter.offset(0, y, 0)
            val state = level.getBlockState(checkPos)
            if (state.block == Blocks.BEDROCK) {
                return false
            }
            if (state.isSolidRender(level, checkPos)) {
                return true
            }
        }

        return false
    }

    private fun calculateSolidGroundScore(level: ServerLevel, floorCenter: BlockPos): Int {
        val radius = ObeliskConstants.PLATFORM_RADIUS
        var score = 0
        for (y in 0 downTo -5) {
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val checkPos = floorCenter.offset(x, y, z)
                    if (level.getBlockState(checkPos).isSolidRender(level, checkPos)) {
                        score += 6 + y
                    }
                }
            }
        }
        return score
    }

    private fun isFootprintLoaded(level: ServerLevel, centerX: Int, centerZ: Int): Boolean {
        val radius = ObeliskConstants.PLATFORM_RADIUS
        val minChunkX = (centerX - radius) shr 4
        val maxChunkX = (centerX + radius) shr 4
        val minChunkZ = (centerZ - radius) shr 4
        val maxChunkZ = (centerZ + radius) shr 4
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) {
                    return false
                }
            }
        }
        return true
    }

    private fun buildPlatform(level: ServerLevel, floorCenter: BlockPos) {
        val radius = ObeliskConstants.PLATFORM_RADIUS

        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val floorPos = floorCenter.offset(x, 0, z)
                level.setBlock(floorPos, Blocks.BEDROCK.defaultBlockState(), PLATFORM_SET_FLAGS)
                extendBedrockSupport(level, floorPos)
            }
        }

        val padPos = floorCenter.above()
        for (x in -1..1) {
            for (z in -1..1) {
                if (x == 0 && z == 0) {
                    level.setBlock(padPos.offset(x, 0, z), ModBlocks.RETURN_FONT.get().defaultBlockState(), PLATFORM_SET_FLAGS)
                } else {
                    level.setBlock(padPos.offset(x, 0, z), Blocks.OBSIDIAN.defaultBlockState(), PLATFORM_SET_FLAGS)
                }
            }
        }

        for (y in 1..3) {
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    level.setBlock(padPos.offset(x, y, z), Blocks.AIR.defaultBlockState(), PLATFORM_SET_FLAGS)
                }
            }
        }
    }

    private fun extendBedrockSupport(level: ServerLevel, floorPos: BlockPos) {
        var supportPos = floorPos.below()
        while (supportPos.y >= level.minBuildHeight && !level.getBlockState(supportPos).isSolidRender(level, supportPos)) {
            level.setBlock(supportPos, Blocks.BEDROCK.defaultBlockState(), PLATFORM_SET_FLAGS)
            supportPos = supportPos.below()
        }
    }
}
