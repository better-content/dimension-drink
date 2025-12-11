package dev.yourname.obelisks.config

/**
 * Configuration settings for Obelisks mod.
 * TODO: Integrate with Forge Config API when needed.
 * For now, these are compile-time constants.
 */
object ObelisksConfig {

    /**
     * Y-level threshold for void fall return trigger.
     */
    const val VOID_FALL_Y_THRESHOLD = -64.0

    /**
     * Delay in server ticks before cleaning up empty run dimensions.
     * Default: 100 ticks = 5 seconds
     */
    const val RUN_CLEANUP_DELAY_TICKS = 100

    /**
     * Maximum concurrent runs per obelisk.
     * Set to 1 for Phase 2 (one run at a time per obelisk).
     */
    const val MAX_CONCURRENT_RUNS_PER_OBELISK = 1

    /**
     * Whether to allow players to break obelisks while a run is active.
     * For Phase 2 safety: false (prevent breaking).
     */
    const val ALLOW_BREAK_WHILE_ACTIVE = false

    /**
     * Base type selection mode.
     * Options: "random" or "manual" (future: item-based attunement)
     */
    const val BASE_TYPE_SELECTION_MODE = "random"

    /**
     * Whether to resume runs on player login (Phase 3 feature).
     * For Phase 2: false (return to origin instead).
     */
    const val RESUME_RUNS_ON_LOGIN = false

    // ===== Phase 3: FE System Configuration =====

    /**
     * Maximum FE storage capacity for each obelisk.
     * Balanced for ~60 second runs with 1 player.
     * Default: 3,600 FE (60 seconds × 20 ticks/sec × 3 FE/tick)
     */
    const val MAX_FE_STORAGE = 1_800

    /**
     * Base FE drain per server tick (regardless of player count).
     * Default: 1 FE/tick = 20 FE/second
     */
    const val BASE_FE_DRAIN_PER_TICK = 1

    /**
     * Additional FE drain per player per server tick.
     * With 1 player: 1 base + 2 per player = 3 FE/tick = 60 FE/second
     * 3,600 FE / 60 FE per second = 60 seconds runtime
     * Default: 2 FE/tick/player = 40 FE/second/player
     */
    const val PER_PLAYER_FE_DRAIN = 2

    /**
     * FE percentage threshold where collapse effects start (cosmetic).
     * Default: 0.30 (30%)
     */
    const val COLLAPSE_START_THRESHOLD = 0.30

    /**
     * FE percentage threshold where critical collapse begins (destructive).
     * Default: 0.10 (10%)
     */
    const val COLLAPSE_CRITICAL_THRESHOLD = 0.10

    /**
     * FE percentage threshold below which the boss bar is shown.
     * Default: 0.90 (90%)
     */
    const val BOSS_BAR_SHOW_THRESHOLD = 0.90

    /**
     * How often (in ticks) to apply collapse effects during decay phase.
     * Default: 20 ticks = 1 second
     */
    const val COLLAPSE_EFFECT_INTERVAL_TICKS = 20

    /**
     * How often (in ticks) to apply critical collapse effects.
     * Default: 10 ticks = 0.5 seconds
     */
    const val COLLAPSE_CRITICAL_INTERVAL_TICKS = 10

    /**
     * FE regeneration rate per server tick when obelisk is idle (no active run).
     * Default: 1 FE/tick = 20 FE/second = 90 seconds to fully recharge from empty
     */
    const val FE_REGEN_PER_TICK = 1
}
