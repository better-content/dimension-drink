package com.bettercontent.dimensiondrink

object ObeliskConstants {
    const val MAX_CHARGE_STORAGE: Double = 15_000.0
    const val START_CHARGE_COST: Double = 600.0
    const val JOIN_CHARGE_COST: Double = 0.0
    const val PASSIVE_CHARGE_PER_TICK: Double = 0.25
    const val BASE_CHARGE_DRAIN_PER_SECOND: Double = 80.0
    const val PER_PLAYER_CHARGE_DRAIN_PER_SECOND: Double = 40.0
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
