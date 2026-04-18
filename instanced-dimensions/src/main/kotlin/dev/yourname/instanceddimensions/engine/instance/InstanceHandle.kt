package dev.yourname.instanceddimensions.engine.instance

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

data class InstanceHandle(
    val id: UUID,
    val levelKey: ResourceKey<Level>,
    val templateId: String,
    val state: InstanceState,
    val ownerId: UUID? = null,
    val createdGameTime: Long = 0L,
    val updatedGameTime: Long = 0L
)
