package dev.yourname.instanceddimensions.engine.instance

import net.minecraft.core.BlockPos

enum class InstanceArrivalPhase {
    IDLE,
    PREPARING,
    READY,
    FAILED
}

data class InstanceArrivalStatus(
    val phase: InstanceArrivalPhase,
    val center: BlockPos? = null,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val requestedGameTime: Long? = null,
    val readyGameTime: Long? = null,
    val failureReason: String? = null
)

enum class InstancePlatformBootstrapPhase {
    IDLE,
    PREPARING,
    READY,
    FAILED
}

data class InstancePlatformBootstrapStatus(
    val phase: InstancePlatformBootstrapPhase,
    val center: BlockPos? = null,
    val requestedGameTime: Long? = null,
    val readyGameTime: Long? = null,
    val failureReason: String? = null
)
