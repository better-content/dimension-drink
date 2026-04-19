package dev.yourname.obelisks.runtime.run

enum class RunState {
    ALLOCATED,
    WARMING_UP,
    ACTIVE,
    COLLAPSING,
    FINISHING,
    FINISHED,
    FAILED
}
