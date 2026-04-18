package dev.yourname.instanceddimensions.engine.instance

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.dimension.LevelStem

data class InstanceTemplate(
    val id: String,
    val stem: ResourceKey<LevelStem>,
    val ephemeral: Boolean = true,
    val description: String = ""
)
