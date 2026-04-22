package dev.yourname.instanceddimensions.api

import dev.yourname.instanceddimensions.engine.instance.InstanceHandle

sealed interface InstanceCreateResult {
    data class Accepted(val instance: InstanceHandle) : InstanceCreateResult
    data class Rejected(val reason: String) : InstanceCreateResult
}
