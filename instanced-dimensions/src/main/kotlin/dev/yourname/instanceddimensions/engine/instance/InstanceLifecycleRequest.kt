package dev.yourname.instanceddimensions.engine.instance

import java.util.UUID

sealed interface InstanceLifecycleRequest {
    val instanceId: UUID

    data class Create(override val instanceId: UUID) : InstanceLifecycleRequest

    data class Destroy(override val instanceId: UUID) : InstanceLifecycleRequest
}
