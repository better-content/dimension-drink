package dev.yourname.obelisks.config

import dev.yourname.obelisks.ObelisksConstants

/**
 * Configuration settings for Obelisks mod.
 * This object delegates to ObelisksConstants for actual values (which loads from JSON).
 * Cannot use const val because values are loaded at runtime from JSON.
 */
object ObelisksConfig {

    // ===== Player Safety =====
    val VOID_FALL_Y_THRESHOLD: Double get() = ObelisksConstants.VOID_FALL_Y_THRESHOLD

    // ===== Run Management =====
    val RUN_CLEANUP_DELAY_TICKS: Int get() = ObelisksConstants.RUN_CLEANUP_DELAY_TICKS
    val MAX_CONCURRENT_RUNS_PER_OBELISK: Int get() = ObelisksConstants.MAX_CONCURRENT_RUNS_PER_OBELISK
    val ALLOW_BREAK_WHILE_ACTIVE: Boolean get() = ObelisksConstants.ALLOW_BREAK_WHILE_ACTIVE
    val BASE_TYPE_SELECTION_MODE: String get() = ObelisksConstants.BASE_TYPE_SELECTION_MODE
    val RESUME_RUNS_ON_LOGIN: Boolean get() = ObelisksConstants.RESUME_RUNS_ON_LOGIN

    // ===== Phase 3: FE System Configuration =====
    val MAX_FE_STORAGE: Int get() = ObelisksConstants.MAX_FE_STORAGE
    val BASE_FE_DRAIN_PER_TICK: Int get() = ObelisksConstants.BASE_FE_DRAIN_PER_TICK
    val PER_PLAYER_FE_DRAIN: Int get() = ObelisksConstants.PER_PLAYER_FE_DRAIN
    val FE_REGEN_PER_TICK: Int get() = ObelisksConstants.FE_REGEN_PER_TICK

    // ===== Collapse System Thresholds =====
    val COLLAPSE_START_THRESHOLD: Double get() = ObelisksConstants.DECAY_PHASE_THRESHOLD
    val COLLAPSE_CRITICAL_THRESHOLD: Double get() = ObelisksConstants.CRITICAL_COLLAPSE_THRESHOLD
    val BOSS_BAR_SHOW_THRESHOLD: Double get() = ObelisksConstants.BOSS_BAR_SHOW_THRESHOLD
    val TARGET_DELETION_AT_ZERO_FE: Double get() = ObelisksConstants.TARGET_DELETION_AT_ZERO_FE
    val DELETION_START_THRESHOLD: Double get() = ObelisksConstants.DECAY_PHASE_THRESHOLD

    // ===== Collapse System Intervals (Legacy - not used) =====
    val COLLAPSE_EFFECT_INTERVAL_TICKS: Int get() = ObelisksConstants.COLLAPSE_LOG_INTERVAL
    val COLLAPSE_CRITICAL_INTERVAL_TICKS: Int get() = 10 // Not used in current implementation
}
