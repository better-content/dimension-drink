package com.bettercontent.dimensiondrink.runtime.run

import com.bettercontent.dimensiondrink.api.RunHandle
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.UUID

data class RunRecord(
    val id: UUID,
    val instanceId: UUID,
    val obeliskId: UUID,
    val definitionId: String,
    val instanceTemplateId: String,
    val originLevelKey: ResourceKey<Level>? = null,
    val originObeliskPos: BlockPos? = null,
    var backendLevelKey: ResourceKey<Level>? = null,
    var backendSiteCenter: BlockPos? = null,
    var backendSiteBounds: com.bettercontent.dimensiondrink.runtime.backend.SiteBounds? = null,
    var spawnPos: BlockPos? = null,
    val createdGameTime: Long = 0L,
    var updatedGameTime: Long = createdGameTime,
    val activePlayers: MutableSet<UUID> = linkedSetOf(),
    val pendingPlayers: MutableSet<UUID> = linkedSetOf(),
    val participants: MutableSet<UUID> = linkedSetOf(),
    val survivors: MutableSet<UUID> = linkedSetOf(),
    val disqualifiedPlayers: MutableSet<UUID> = linkedSetOf(),
    var monstersKilled: Int = 0,
    var totalDamageDealt: Float = 0f,
    var ticksElapsed: Long = 0L,
    var rewardsGranted: Boolean = false,
    var state: RunState = RunState.ALLOCATED
) {
    fun toHandle(): RunHandle = RunHandle(
        runId = id,
        instanceId = instanceId,
        obeliskId = obeliskId,
        definitionId = definitionId,
        instanceTemplateId = instanceTemplateId,
        state = state.name
    )

    fun deepCopy(): RunRecord = copy(
        activePlayers = LinkedHashSet(activePlayers),
        pendingPlayers = LinkedHashSet(pendingPlayers),
        participants = LinkedHashSet(participants),
        survivors = LinkedHashSet(survivors),
        disqualifiedPlayers = LinkedHashSet(disqualifiedPlayers)
    )

    fun toTag(): CompoundTag = CompoundTag().apply {
        putString("id", this@RunRecord.id.toString())
        putString("instance_id", instanceId.toString())
        putString("obelisk_id", obeliskId.toString())
        putString("definition_id", definitionId)
        putString("instance_template_id", instanceTemplateId)
        putString("state", state.name)
        putLong("created_game_time", createdGameTime)
        putLong("updated_game_time", updatedGameTime)
        putInt("monsters_killed", monstersKilled)
        putFloat("total_damage_dealt", totalDamageDealt)
        putLong("ticks_elapsed", ticksElapsed)
        putBoolean("rewards_granted", rewardsGranted)
        originLevelKey?.location()?.toString()?.let { putString("origin_level_key", it) }
        originObeliskPos?.let { put("origin_obelisk_pos", encodeBlockPos(it)) }
        backendLevelKey?.location()?.toString()?.let { putString("backend_level_key", it) }
        backendSiteCenter?.let { put("backend_site_center", encodeBlockPos(it)) }
        backendSiteBounds?.let { put("backend_site_bounds", encodeBounds(it)) }
        spawnPos?.let { put("spawn_pos", encodeBlockPos(it)) }
        put("active_players", encodeUuids(activePlayers))
        put("pending_players", encodeUuids(pendingPlayers))
        put("participants", encodeUuids(participants))
        put("survivors", encodeUuids(survivors))
        put("disqualified_players", encodeUuids(disqualifiedPlayers))
    }

    companion object {
        fun fromTag(tag: CompoundTag): RunRecord? {
            val id = parseUuid(tag.getString("id")) ?: return null
            val instanceId = parseUuid(tag.getString("instance_id")) ?: return null
            val obeliskId = parseUuid(tag.getString("obelisk_id")) ?: return null
            val definitionId = when {
                tag.contains("definition_id") -> tag.getString("definition_id")
                tag.contains("template_id") -> tag.getString("template_id")
                else -> return null
            }
            val originLevelKey = parseLevelKey(tag.getString("origin_level_key"))
            val players = decodeUuids(tag.getCompound("active_players"))
            val pendingPlayers = decodeUuids(tag.getCompound("pending_players"))
            val participants = decodeUuids(tag.getCompound("participants")).ifEmpty {
                LinkedHashSet<UUID>().also { it.addAll(players); it.addAll(pendingPlayers) }
            }
            val survivors = decodeUuids(tag.getCompound("survivors")).ifEmpty {
                LinkedHashSet<UUID>().also { it.addAll(participants) }
            }
            val disqualifiedPlayers = decodeUuids(tag.getCompound("disqualified_players"))

            return RunRecord(
                id = id,
                instanceId = instanceId,
                obeliskId = obeliskId,
                definitionId = definitionId,
                instanceTemplateId = if (tag.contains("instance_template_id")) tag.getString("instance_template_id") else definitionId,
                originLevelKey = originLevelKey,
                originObeliskPos = if (tag.contains("origin_obelisk_pos")) decodeBlockPos(tag.getCompound("origin_obelisk_pos")) else null,
                backendLevelKey = parseLevelKey(tag.getString("backend_level_key")),
                backendSiteCenter = if (tag.contains("backend_site_center")) decodeBlockPos(tag.getCompound("backend_site_center")) else null,
                backendSiteBounds = if (tag.contains("backend_site_bounds")) decodeBounds(tag.getCompound("backend_site_bounds")) else null,
                spawnPos = if (tag.contains("spawn_pos")) decodeBlockPos(tag.getCompound("spawn_pos")) else null,
                createdGameTime = tag.getLong("created_game_time"),
                updatedGameTime = tag.getLong("updated_game_time"),
                activePlayers = players,
                pendingPlayers = pendingPlayers,
                participants = participants,
                survivors = survivors,
                disqualifiedPlayers = disqualifiedPlayers,
                monstersKilled = if (tag.contains("monsters_killed")) tag.getInt("monsters_killed") else 0,
                totalDamageDealt = if (tag.contains("total_damage_dealt")) tag.getFloat("total_damage_dealt") else 0f,
                ticksElapsed = tag.getLong("ticks_elapsed"),
                rewardsGranted = tag.contains("rewards_granted") && tag.getBoolean("rewards_granted"),
                state = runCatching { RunState.valueOf(tag.getString("state")) }.getOrDefault(RunState.ALLOCATED)
            )
        }

        private fun encodeUuids(values: Set<UUID>): CompoundTag = CompoundTag().also { tag ->
            values.forEachIndexed { index, playerId ->
                tag.putString(index.toString(), playerId.toString())
            }
        }

        private fun encodeBlockPos(pos: BlockPos): CompoundTag = CompoundTag().apply {
            putInt("x", pos.x)
            putInt("y", pos.y)
            putInt("z", pos.z)
        }

        private fun decodeBlockPos(tag: CompoundTag): BlockPos = BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"))

        private fun encodeBounds(bounds: com.bettercontent.dimensiondrink.runtime.backend.SiteBounds): CompoundTag = CompoundTag().apply {
            putInt("min_x", bounds.minX)
            putInt("min_y", bounds.minY)
            putInt("min_z", bounds.minZ)
            putInt("max_x", bounds.maxX)
            putInt("max_y", bounds.maxY)
            putInt("max_z", bounds.maxZ)
        }

        private fun decodeBounds(tag: CompoundTag): com.bettercontent.dimensiondrink.runtime.backend.SiteBounds {
            return com.bettercontent.dimensiondrink.runtime.backend.SiteBounds(
                minX = tag.getInt("min_x"),
                minY = tag.getInt("min_y"),
                minZ = tag.getInt("min_z"),
                maxX = tag.getInt("max_x"),
                maxY = tag.getInt("max_y"),
                maxZ = tag.getInt("max_z")
            )
        }

        private fun decodeUuids(tag: CompoundTag): LinkedHashSet<UUID> {
            val values = linkedSetOf<UUID>()
            tag.allKeys
                .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNullTo(values) { key -> parseUuid(tag.getString(key)) }
            return values
        }

        private fun parseLevelKey(raw: String): ResourceKey<Level>? {
            if (raw.isBlank()) {
                return null
            }
            val location = runCatching { ResourceLocation(raw) }.getOrNull() ?: return null
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location)
        }

        private fun parseUuid(raw: String): UUID? {
            if (raw.isBlank()) {
                return null
            }
            return runCatching { UUID.fromString(raw) }.getOrNull()
        }
    }
}
