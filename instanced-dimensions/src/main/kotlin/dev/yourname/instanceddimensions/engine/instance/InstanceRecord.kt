package dev.yourname.instanceddimensions.engine.instance

import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.UUID

data class InstanceRecord(
    val id: UUID,
    val templateId: String,
    val levelKey: ResourceKey<Level>,
    var state: InstanceState,
    val ownerId: UUID? = null,
    val createdGameTime: Long = 0L,
    var updatedGameTime: Long = createdGameTime,
    var levelState: InstanceLevelState
) {
    fun toHandle(): InstanceHandle = InstanceHandle(
        id = id,
        levelKey = levelKey,
        templateId = templateId,
        state = state,
        ownerId = ownerId,
        createdGameTime = createdGameTime,
        updatedGameTime = updatedGameTime
    )

    fun toTag(): CompoundTag = CompoundTag().apply {
        putString("id", id.toString())
        putString("template_id", templateId)
        putString("level_key", levelKey.location().toString())
        putString("state", state.name)
        ownerId?.let { putString("owner_id", it.toString()) }
        putLong("created_game_time", createdGameTime)
        putLong("updated_game_time", updatedGameTime)
        put("level_state", levelState.toTag())
    }

    fun deepCopy(): InstanceRecord = copy(levelState = levelState.deepCopy())

    companion object {
        fun fromTag(tag: CompoundTag): InstanceRecord? {
            val id = parseUuid(tag.getString("id")) ?: return null
            val levelLocation = ResourceLocation.tryParse(tag.getString("level_key")) ?: return null
            val state = runCatching { InstanceState.valueOf(tag.getString("state")) }.getOrDefault(InstanceState.ALLOCATED)
            val ownerId = parseUuid(tag.getString("owner_id")) ?: parseUuid(tag.getString("owner_run_id"))

            return InstanceRecord(
                id = id,
                templateId = tag.getString("template_id"),
                levelKey = ResourceKey.create(Registries.DIMENSION, levelLocation),
                state = state,
                ownerId = ownerId,
                createdGameTime = tag.getLong("created_game_time"),
                updatedGameTime = tag.getLong("updated_game_time"),
                levelState = InstanceLevelState.fromTag(tag.getCompound("level_state"))
            )
        }

        private fun parseUuid(raw: String): UUID? {
            if (raw.isBlank()) {
                return null
            }
            return runCatching { UUID.fromString(raw) }.getOrNull()
        }
    }
}
