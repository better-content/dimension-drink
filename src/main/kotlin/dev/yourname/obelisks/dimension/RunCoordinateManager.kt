package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.config.ConfigManager
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.*
import kotlin.random.Random

/**
 * Manages random coordinate assignment for obelisk runs in actual modded dimensions.
 * Each run gets spatially isolated coordinates far from spawn to prevent overlap.
 */
object RunCoordinateManager {

    // Maps runId -> assigned spawn coordinates and dimension
    private data class RunLocation(val dimensionKey: ResourceKey<Level>, val spawnPos: BlockPos)
    private val runLocations = mutableMapOf<Long, RunLocation>()

    // Maps obelisk UUID -> active run info
    private val obeliskRuns = mutableMapOf<UUID, Long>()

    /**
     * Assigns random coordinates for a run in the specified dimension.
     * Returns the dimension and spawn position, or null if dimension doesn't exist.
     */
    fun assignRunLocation(
        server: MinecraftServer,
        obeliskId: UUID,
        runId: Long,
        dimensionId: String
    ): Pair<ServerLevel, BlockPos>? {
        // Check if this run already has assigned coordinates
        runLocations[runId]?.let { location ->
            val dimension = server.getLevel(location.dimensionKey)
            if (dimension != null) {
                return Pair(dimension, location.spawnPos)
            }
        }

        // Get the actual modded dimension
        val dimensionKey = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation(dimensionId)
        )
        val dimension = server.getLevel(dimensionKey)
            ?: run {
                println("[Obelisks] WARNING: Dimension $dimensionId not found on server!")
                return null
            }

        // Generate random coordinates far from spawn
        val spawnPos = generateRandomCoordinates(dimensionId)

        // Store the location
        runLocations[runId] = RunLocation(dimensionKey, spawnPos)
        obeliskRuns[obeliskId] = runId

        println("[Obelisks] Assigned run #$runId to $dimensionId at $spawnPos")

        // Generate the spawn platform
        val actualSpawnPos = dev.yourname.obelisks.dimension.SpawnPlatformGenerator.generateSpawnPlatform(
            dimension,
            dimensionId,
            spawnPos
        )

        return Pair(dimension, actualSpawnPos)
    }

    /**
     * Generates random coordinates in a dimension, far from spawn to ensure isolation.
     * Returns a BlockPos with random X/Z and configured Y height.
     */
    private fun generateRandomCoordinates(dimensionId: String): BlockPos {
        // Use a large coordinate range to ensure runs don't overlap
        val x = Random.nextInt(ObelisksConstants.SPAWN_POS_X_MIN, ObelisksConstants.SPAWN_POS_X_MAX)
        val z = Random.nextInt(ObelisksConstants.SPAWN_POS_Z_MIN, ObelisksConstants.SPAWN_POS_Z_MAX)

        // Y coordinate from config
        val config = ConfigManager.getDimensionConfig(dimensionId)
        val y = config?.spawnY ?: 64

        return BlockPos(x, y, z)
    }

    /**
     * Releases a run's coordinates when it ends.
     */
    fun releaseRun(obeliskId: UUID, runId: Long) {
        runLocations.remove(runId)
        obeliskRuns.remove(obeliskId)
        println("[Obelisks] Released run #$runId coordinates")
    }

    /**
     * Gets the location for an existing run, or null if not found.
     */
    fun getRunLocation(server: MinecraftServer, runId: Long): Pair<ServerLevel, BlockPos>? {
        val location = runLocations[runId] ?: return null
        val dimension = server.getLevel(location.dimensionKey) ?: return null
        return Pair(dimension, location.spawnPos)
    }

    /**
     * Gets the active run ID for an obelisk, or null if no active run.
     */
    fun getObeliskRunId(obeliskId: UUID): Long? {
        return obeliskRuns[obeliskId]
    }

    /**
     * Gets total number of active runs.
     */
    fun getActiveRunCount(): Int = runLocations.size

    /**
     * Debug: prints current run assignments.
     */
    fun debugPrintRuns() {
        println("[Obelisks] Active runs: ${runLocations.size}")
        runLocations.forEach { (runId, location) ->
            println("  Run #$runId -> ${location.dimensionKey.location()} at ${location.spawnPos}")
        }
    }
}
