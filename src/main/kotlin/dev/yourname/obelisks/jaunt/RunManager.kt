package dev.yourname.obelisks.jaunt

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.storage.DimensionDataStorage
import java.util.*

/**
 * Manages all active obelisk runs and tracks player participation.
 * This is persisted as SavedData in the overworld dimension.
 */
class RunManager private constructor() : SavedData() {

    private var nextRunId: Long = 1L
    private val activeRuns = mutableMapOf<RunKey, RunData>()
    private val playerToRun = mutableMapOf<UUID, RunKey>()

    /**
     * Gets the next available run ID without incrementing the counter.
     */
    fun getNextRunId(): Long = nextRunId

    data class RunKey(val obeliskId: UUID, val runId: Long) {
        override fun toString(): String = "${obeliskId}_${runId}"

        companion object {
            fun fromString(str: String): RunKey {
                val parts = str.split("_")
                return RunKey(UUID.fromString(parts[0]), parts[1].toLong())
            }
        }
    }

    /**
     * Creates or retrieves an existing run for the given obelisk.
     * Now uses dimensionId directly instead of baseType.
     */
    fun getOrCreateRunWithDimension(
        obeliskId: UUID,
        dimensionId: String,
        dimensionKey: ResourceKey<Level>,
        spawnPos: BlockPos,
        originObeliskPos: BlockPos,
        originDimension: ResourceKey<Level>,
        existingRunId: Long?
    ): RunData {
        // If there's an existing active run, return it
        if (existingRunId != null) {
            val key = RunKey(obeliskId, existingRunId)
            activeRuns[key]?.let { return it }
        }

        // Create new run with custom dimension key
        val runId = nextRunId++
        val key = RunKey(obeliskId, runId)
        val runData = RunData(obeliskId, runId, dimensionId, dimensionKey, spawnPos, originObeliskPos, originDimension)

        activeRuns[key] = runData
        setDirty()

        return runData
    }

    /**
     * Gets an existing run by obeliskId and runId.
     */
    fun getRun(obeliskId: UUID, runId: Long): RunData? {
        return activeRuns[RunKey(obeliskId, runId)]
    }

    /**
     * Gets a run by dimension key.
     */
    fun getRunByDimension(dimensionKey: ResourceKey<Level>): RunData? {
        return activeRuns.values.find { it.runDimensionKey == dimensionKey }
    }

    /**
     * Gets the active run for a specific obelisk.
     */
    fun getRunByObelisk(obeliskId: UUID): RunData? {
        return activeRuns.values.firstOrNull { it.obeliskId == obeliskId }
    }

    /**
     * Adds a player to a run.
     */
    fun addPlayerToRun(playerId: UUID, obeliskId: UUID, runId: Long) {
        val key = RunKey(obeliskId, runId)
        activeRuns[key]?.let { runData ->
            runData.activePlayers.add(playerId)
            playerToRun[playerId] = key
            setDirty()
        }
    }

    /**
     * Removes a player from their current run.
     */
    fun removePlayerFromRun(playerId: UUID) {
        playerToRun.remove(playerId)?.let { key ->
            activeRuns[key]?.let { runData ->
                runData.activePlayers.remove(playerId)
                setDirty()
            }
        }
    }

    /**
     * Gets the run a player is currently in.
     */
    fun getPlayerRun(playerId: UUID): RunData? {
        return playerToRun[playerId]?.let { activeRuns[it] }
    }

    /**
     * Marks a run as finished and removes it from active runs.
     */
    fun endRun(obeliskId: UUID, runId: Long) {
        val key = RunKey(obeliskId, runId)
        activeRuns.remove(key)?.let { runData ->
            // Remove all players from this run
            runData.activePlayers.forEach { playerId ->
                playerToRun.remove(playerId)
            }
            setDirty()
        }
    }

    /**
     * Gets all active runs.
     */
    fun getAllRuns(): Collection<RunData> = activeRuns.values

    /**
     * Gets runs that have no active players (candidates for cleanup).
     */
    fun getEmptyRuns(): List<RunData> {
        return activeRuns.values.filter { it.activePlayers.isEmpty() }
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putLong("NextRunId", nextRunId)

        val runsList = ListTag()
        activeRuns.forEach { (key, runData) ->
            val runTag = CompoundTag()
            runTag.putString("Key", key.toString())
            runTag.put("Data", runData.toNbt())
            runsList.add(runTag)
        }
        tag.put("ActiveRuns", runsList)

        val playerMapTag = CompoundTag()
        playerToRun.forEach { (playerId, key) ->
            playerMapTag.putString(playerId.toString(), key.toString())
        }
        tag.put("PlayerToRun", playerMapTag)

        return tag
    }

    companion object {
        private const val DATA_NAME = "obelisks_run_manager"

        fun get(server: MinecraftServer): RunManager {
            val storage: DimensionDataStorage = server.overworld().dataStorage
            return storage.computeIfAbsent(
                { tag -> load(tag) },
                { RunManager() },
                DATA_NAME
            )
        }

        private fun load(tag: CompoundTag): RunManager {
            val manager = RunManager()
            manager.nextRunId = tag.getLong("NextRunId")

            // Load active runs
            if (tag.contains("ActiveRuns")) {
                val runsList = tag.getList("ActiveRuns", 10) // 10 = CompoundTag type
                for (i in 0 until runsList.size) {
                    val runTag = runsList.getCompound(i)
                    val key = RunKey.fromString(runTag.getString("Key"))
                    val dataTag = runTag.getCompound("Data")

                    // Reconstruct dimension key
                    val dimLoc = ResourceLocation(dataTag.getString("RunDimKey"))
                    val dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc)

                    val runData = RunData.fromNbt(dataTag, dimKey)
                    manager.activeRuns[key] = runData
                }
            }

            // Load player-to-run mapping
            if (tag.contains("PlayerToRun")) {
                val playerMapTag = tag.getCompound("PlayerToRun")
                for (playerIdStr in playerMapTag.allKeys) {
                    val playerId = UUID.fromString(playerIdStr)
                    val key = RunKey.fromString(playerMapTag.getString(playerIdStr))
                    manager.playerToRun[playerId] = key
                }
            }

            return manager
        }

    }
}
