package dev.yourname.instanceddimensions.engine.travel

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

data class PlayerReturnAnchor(
    val levelKey: ResourceKey<Level>,
    val x: Double,
    val y: Double,
    val z: Double,
    val yRot: Float,
    val xRot: Float
)
