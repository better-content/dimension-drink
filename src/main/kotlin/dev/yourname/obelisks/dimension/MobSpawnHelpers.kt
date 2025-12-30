package dev.yourname.obelisks.dimension

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.level.block.Blocks
import kotlin.random.Random

/**
 * Helper utilities for mob spawning logic.
 */
object MobSpawnHelpers {

    /**
     * Checks if a mob is a flying mob type.
     */
    fun isFlyingMob(mob: Mob): Boolean {
        val typeName = BuiltInRegistries.ENTITY_TYPE.getKey(mob.type).toString()
        return typeName.contains("ghast") ||
               typeName.contains("phantom") ||
               typeName.contains("wither") ||
               mob is net.minecraft.world.entity.FlyingMob
    }

    /**
     * Finds a valid ground spawn position near a base position.
     * Returns null if no valid position found.
     */
    fun findValidGroundSpawn(level: ServerLevel, basePos: BlockPos, searchRadius: Int = 10): BlockPos? {
        for (yCheck in -searchRadius..searchRadius) {
            val checkPos = basePos.offset(0, yCheck, 0)
            val blockBelow = level.getBlockState(checkPos.below())
            val blockAt = level.getBlockState(checkPos)
            val blockAbove = level.getBlockState(checkPos.above())

            if (blockBelow.isSolidRender(level, checkPos.below()) &&
                blockAt.isAir &&
                blockAbove.isAir) {
                return checkPos
            }
        }
        return null
    }

    /**
     * Spawns a ground mob at the given position.
     * Returns true if successful.
     */
    fun spawnGroundMob(mob: Mob, level: ServerLevel, pos: BlockPos): Boolean {
        mob.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, Random.nextFloat() * 360, 0f)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null, null)
        mob.isSilent = true
        level.addFreshEntity(mob)
        mob.isSilent = false
        return true
    }

    /**
     * Spawns a flying mob at the given position if there's air.
     * Returns true if successful.
     */
    fun spawnFlyingMob(mob: Mob, level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.getBlockState(pos).isAir) return false

        mob.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, Random.nextFloat() * 360, 0f)
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null, null)
        mob.isSilent = true
        level.addFreshEntity(mob)
        mob.isSilent = false
        return true
    }

    /**
     * Checks if a spawn position is valid relative to player position.
     */
    fun isValidSpawnDistance(spawnPos: BlockPos, playerPos: BlockPos, maxVerticalDiff: Int = 30): Boolean {
        val yDiff = Math.abs(spawnPos.y - playerPos.y)
        return yDiff <= maxVerticalDiff
    }
}
