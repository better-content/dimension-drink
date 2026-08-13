package com.bettercontent.dimensiondrink

object ObeliskConstants {
    const val MAX_BLOOD_STORAGE: Double = 15_000.0
    const val BASE_BLOOD_DRAIN_PER_TICK: Double = 20.0
    const val PER_PLAYER_BLOOD_DRAIN: Double = 10.0
    const val BLOOD_REGEN_PER_TICK: Double = 0.25
    const val HEART_BLOOD_MULTIPLIER: Double = 0.08
    const val MAX_FE_STORAGE: Int = 15_000
    const val BASE_FE_DRAIN_PER_TICK: Int = 20
    const val PER_PLAYER_FE_DRAIN: Int = 10
    const val FE_REGEN_PER_TICK: Int = 1
    const val DRAIN_EXPONENTIAL_FACTOR: Double = 0.00005
    const val DRAIN_EXPONENTIAL_INTERVAL_TICKS: Int = 20
    const val RUN_EMPTY_CLEANUP_DELAY_TICKS: Long = 40L
    const val BOSS_BAR_SHOW_THRESHOLD: Double = 0.90
    const val BOSS_BAR_GREEN_THRESHOLD: Double = 0.50
    const val BOSS_BAR_YELLOW_THRESHOLD: Double = 0.25
    const val TICKS_PER_SECOND: Int = 20
    const val VOID_FALL_Y_THRESHOLD: Double = -64.0
    const val PLATFORM_Y_LEVEL: Int = 80
    const val PLATFORM_RADIUS: Int = 3
    const val RETURN_PAD_HEIGHT: Int = 1
    const val EMERALDS_PER_REWARD_ROLL: Int = 1
    const val DAMAGE_PER_REWARD_ROLL: Float = 20.0F
    val DEFAULT_TEMPLATES: List<String> = listOf("nether", "end")
}
