package dev.yourname.instanceddimensions.engine.instance

enum class InstanceState {
    ALLOCATED,
    LOADING,
    ACTIVE,
    DRAINING,
    UNLOADING,
    CLOSING,
    DESTROYED
}
