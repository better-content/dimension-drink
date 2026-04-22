package dev.yourname.instanceddimensions.engine.instance

enum class InstanceState {
    ALLOCATED,
    LOADING,
    PREPARING,
    PREPARED,
    ACTIVE,
    DRAINING,
    UNLOADING,
    CLOSING,
    DESTROYED
}
