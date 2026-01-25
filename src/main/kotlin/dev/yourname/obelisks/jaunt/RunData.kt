package dev.yourname.obelisks.jaunt

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.*

/**
 * Stores information about an active obelisk run.
 */
data class RunData(
    val obeliskId: UUID,
    val runId: Long,
    val dimensionId: String,  // Now stores dimension ID directly (e.g., "minecraft:the_nether")
    val runDimensionKey: ResourceKey<Level>,
    val spawnPos: BlockPos,
    val originObeliskPos: BlockPos,  // Phase 3: Link back to origin obelisk for FE drain
    val originDimension: ResourceKey<Level>,  // Phase 3: Origin dimension for obelisk lookup
    val activePlayers: MutableSet<UUID> = mutableSetOf(),
    var ticksElapsed: Long = 0,  // Track how long the run has been active
    var drainMultiplier: Double = 1.0,  // Current exponential drain multiplier
    var monstersKilled: Int = 0,  // Track monster kills for emerald rewards
    var totalDamageDealt: Float = 0f  // Track total damage dealt for rewards
) {
    fun toNbt(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("ObeliskId", obeliskId)
        tag.putLong("RunId", runId)
        tag.putString("DimensionId", dimensionId)
        tag.putString("RunDimKey", runDimensionKey.location().toString())
        tag.putLong("SpawnPos", spawnPos.asLong())
        tag.putLong("OriginObeliskPos", originObeliskPos.asLong())
        tag.putString("OriginDimension", originDimension.location().toString())
        tag.putLong("TicksElapsed", ticksElapsed)
        tag.putDouble("DrainMultiplier", drainMultiplier)
        tag.putInt("MonstersKilled", monstersKilled)
        tag.putFloat("TotalDamageDealt", totalDamageDealt)

        val playersTag = CompoundTag()
        activePlayers.forEachIndexed { idx, playerId ->
            playersTag.putUUID("Player$idx", playerId)
        }
        playersTag.putInt("Count", activePlayers.size)
        tag.put("ActivePlayers", playersTag)

        return tag
    }

    companion object {
        fun fromNbt(tag: CompoundTag, dimKey: ResourceKey<Level>): RunData {
            val obeliskId = tag.getUUID("ObeliskId")
            val runId = tag.getLong("RunId")
            val dimensionId = tag.getString("DimensionId")
            val spawnPos = BlockPos.of(tag.getLong("SpawnPos"))
            val originObeliskPos = BlockPos.of(tag.getLong("OriginObeliskPos"))
            val originDimension = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation(tag.getString("OriginDimension"))
            )
            val ticksElapsed = if (tag.contains("TicksElapsed")) tag.getLong("TicksElapsed") else 0L
            val drainMultiplier = if (tag.contains("DrainMultiplier")) tag.getDouble("DrainMultiplier") else 1.0
            val monstersKilled = if (tag.contains("MonstersKilled")) tag.getInt("MonstersKilled") else 0
            val totalDamageDealt = if (tag.contains("TotalDamageDealt")) tag.getFloat("TotalDamageDealt") else 0f

            val activePlayers = mutableSetOf<UUID>()
            if (tag.contains("ActivePlayers")) {
                val playersTag = tag.getCompound("ActivePlayers")
                val count = playersTag.getInt("Count")
                for (i in 0 until count) {
                    activePlayers.add(playersTag.getUUID("Player$i"))
                }
            }

            return RunData(obeliskId, runId, dimensionId, dimKey, spawnPos, originObeliskPos, originDimension, activePlayers, ticksElapsed, drainMultiplier, monstersKilled, totalDamageDealt)
        }
    }
}
