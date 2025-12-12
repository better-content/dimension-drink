package dev.yourname.obelisks

import dev.yourname.obelisks.config.ConfigLoader

/**
 * Centralized constants for the Obelisks mod.
 * All values are loaded from JSON configuration files.
 */
object ObelisksConstants {

    // Load configuration on initialization
    init {
        ConfigLoader.load()
    }

    // ===== MOD IDENTITY =====
    /** The unique mod identifier used for registration and resource paths. */
    val MOD_ID: String get() = ConfigLoader.getString("modIdentity.modId")

    // ===== DIMENSION SPAWN PLATFORM =====
    /** Platform generation fixed Y level */
    val PLATFORM_Y_LEVEL: Int get() = ConfigLoader.getInt("dimensionSpawnPlatform.platformYLevel")
    /** Platform radius (creates 7x7 platform with radius 3) */
    val PLATFORM_RADIUS: Int get() = ConfigLoader.getInt("dimensionSpawnPlatform.platformRadius")
    /** Vertical clearance above platform (blocks of air) */
    val PLATFORM_AIR_CLEARANCE: Int get() = ConfigLoader.getInt("dimensionSpawnPlatform.platformAirClearance")
    /** Chunk force-load radius around spawn platform */
    val PLATFORM_CHUNK_LOAD_RADIUS: Int get() = ConfigLoader.getInt("dimensionSpawnPlatform.platformChunkLoadRadius")
    /** Position offset for return pad above platform ground */
    val RETURN_PAD_HEIGHT: Int get() = ConfigLoader.getInt("dimensionSpawnPlatform.returnPadHeight")
    /** Air cube size above return pad for player spawn */
    val RETURN_PAD_AIR_CUBE_HEIGHT: Int get() = ConfigLoader.getInt("dimensionSpawnPlatform.returnPadAirCubeHeight")
    /** Lighting placement height above platform */
    val PLATFORM_LIGHT_HEIGHT: Int get() = ConfigLoader.getInt("dimensionSpawnPlatform.platformLightHeight")

    // ===== DIMENSION COLLAPSE SYSTEM =====
    /** Target percentage of blocks deleted when FE reaches 0% */
    val TARGET_DELETION_AT_ZERO_FE: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.targetDeletionAtZeroFE")
    /** FE threshold where boss bar becomes visible */
    val BOSS_BAR_SHOW_THRESHOLD: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.bossBarShowThreshold")
    /** FE threshold where decay phase begins (block deletion starts) */
    val DECAY_PHASE_THRESHOLD: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.decayPhaseThreshold")
    /** FE threshold where critical collapse begins (aggressive deletion) */
    val CRITICAL_COLLAPSE_THRESHOLD: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.criticalCollapseThreshold")

    // Deletion rate parameters (blocks per tick)
    /** Initial deletion rate when decay starts */
    val INITIAL_DELETION_RATE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionRates.initialDeletionRate")
    /** Initial deletion rate during critical collapse */
    val INITIAL_CRITICAL_DELETION_RATE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionRates.initialCriticalDeletionRate")
    /** Minimum deletion rate */
    val MIN_DELETION_RATE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionRates.minDeletionRate")
    /** Maximum deletion rate during decay */
    val MAX_DELETION_RATE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionRates.maxDeletionRate")
    /** Maximum deletion rate during critical collapse */
    val MAX_CRITICAL_DELETION_RATE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionRates.maxCriticalDeletionRate")
    /** Minimum deletion rate during critical collapse */
    val MIN_CRITICAL_DELETION_RATE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionRates.minCriticalDeletionRate")

    // Deletion rate adjustment thresholds (difference from target)
    val DELETION_DIFF_HUGE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffHuge")
    val DELETION_DIFF_LARGE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffLarge")
    val DELETION_DIFF_MEDIUM: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffMedium")
    val DELETION_DIFF_SMALL: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffSmall")

    // Deletion rate multipliers
    val RATE_INCREASE_HUGE: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateIncreaseHuge")
    val RATE_INCREASE_LARGE: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateIncreaseLarge")
    val RATE_DECREASE_SMALL: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateDecreaseSmall")
    val RATE_DECREASE_LARGE: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateDecreaseLarge")
    val RATE_INCREASE_CRITICAL_HUGE: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateIncreaseCriticalHuge")
    val RATE_INCREASE_CRITICAL_LARGE: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateIncreaseCriticalLarge")
    val RATE_INCREASE_CRITICAL_MEDIUM: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateIncreaseCriticalMedium")
    val RATE_DECREASE_CRITICAL: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.rateMultipliers.rateDecreaseCritical")

    // Column deletion parameters
    /** Random additional columns deleted (min) during decay */
    val DECAY_EXTRA_COLUMNS_MIN: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.decayExtraColumnsMin")
    /** Random additional columns deleted (max) during decay */
    val DECAY_EXTRA_COLUMNS_MAX: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.decayExtraColumnsMax")
    /** Guaranteed columns near each player per second during decay */
    val DECAY_GUARANTEED_COLUMNS_PER_PLAYER: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.decayGuaranteedColumnsPerPlayer")
    /** Random additional columns deleted (min) during critical collapse */
    val CRITICAL_EXTRA_COLUMNS_MIN: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.criticalExtraColumnsMin")
    /** Random additional columns deleted (max) during critical collapse */
    val CRITICAL_EXTRA_COLUMNS_MAX: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.criticalExtraColumnsMax")
    /** Guaranteed columns near each player per second during critical collapse */
    val CRITICAL_GUARANTEED_COLUMNS_PER_PLAYER: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.criticalGuaranteedColumnsPerPlayer")
    /** Column deletion width (creates 2x2 columns) */
    val COLUMN_WIDTH: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.columnWidth")
    /** Maximum radius for column placement near players */
    val COLUMN_PLACEMENT_RADIUS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.columnPlacementRadius")
    /** Vertical step for particle spawning along columns */
    val COLUMN_PARTICLE_STEP: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.columnDeletion.columnParticleStep")

    // Block deletion ranges
    /** Radius for random block deletion around player during decay */
    val RANDOM_DELETION_RADIUS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.blockDeletion.randomDeletionRadius")
    /** Y offset range for random block deletion (min) */
    val RANDOM_DELETION_Y_MIN: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.blockDeletion.randomDeletionYMin")
    /** Y offset range for random block deletion (max) */
    val RANDOM_DELETION_Y_MAX: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.blockDeletion.randomDeletionYMax")
    /** Particle spawn interval (every N blocks deleted) */
    val DELETION_PARTICLE_INTERVAL: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.blockDeletion.deletionParticleInterval")

    // Block count estimation
    /** Estimated blocks per chunk section (16x16x16 with 50% solid) */
    val ESTIMATED_BLOCKS_PER_SECTION: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.blockCounting.estimatedBlocksPerSection")
    /** Minimum estimated block count for dimension */
    val MIN_ESTIMATED_BLOCKS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.blockCounting.minEstimatedBlocks")
    /** Chunk view distance for block counting */
    val BLOCK_COUNT_VIEW_DISTANCE: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.blockCounting.blockCountViewDistance")

    // Effect intervals (server ticks)
    /** How often to log collapse progress (20 ticks = 1 second) */
    val COLLAPSE_LOG_INTERVAL: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.effectIntervals.collapseLogInterval")

    // Void hole parameters
    val VOID_HOLE_RADIUS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.voidHole.voidHoleRadius")
    val VOID_PORTAL_PARTICLE_COUNT: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.voidHole.voidPortalParticleCount")

    // Block removal effect radii (old system - kept for legacy support)
    val BLOCK_SWAP_RADIUS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.blockSwapRadius")
    val BLOCK_SWAP_Y_OFFSET: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.blockSwapYOffset")
    val BLOCK_SWAP_ATTEMPTS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.blockSwapAttempts")
    val BLOCK_REMOVAL_RADIUS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.blockRemovalRadius")
    val BLOCK_REMOVAL_Y_OFFSET: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.blockRemovalYOffset")
    val BLOCK_REMOVAL_ATTEMPTS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.blockRemovalAttempts")
    val INDIVIDUAL_DELETION_RADIUS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.individualDeletionRadius")
    val INDIVIDUAL_DELETION_Y_OFFSET: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.individualDeletionYOffset")
    val INDIVIDUAL_DELETION_ATTEMPTS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.individualDeletionAttempts")
    val RANDOM_COLUMN_RADIUS: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.legacyEffects.randomColumnRadius")

    // Particle parameters
    val SMOKE_PARTICLE_COUNT: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.particles.smokeParticleCount")
    val LARGE_SMOKE_PARTICLE_COUNT: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.particles.largeSmokeParticleCount")
    val PORTAL_PARTICLE_COUNT: Int get() = ConfigLoader.getInt("dimensionCollapseSystem.particles.portalParticleCount")
    val PARTICLE_SPEED: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.particles.particleSpeed")
    val PARTICLE_SPREAD: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.particles.particleSpread")
    val PORTAL_PARTICLE_SPEED: Double get() = ConfigLoader.getDouble("dimensionCollapseSystem.particles.portalParticleSpeed")

    // Sound parameters
    val PORTAL_TRAVEL_VOLUME: Float get() = ConfigLoader.getDouble("dimensionCollapseSystem.sounds.portalTravelVolume").toFloat()
    val PORTAL_TRAVEL_PITCH_BASE: Float get() = ConfigLoader.getDouble("dimensionCollapseSystem.sounds.portalTravelPitchBase").toFloat()
    val PORTAL_TRAVEL_PITCH_CRITICAL: Float get() = ConfigLoader.getDouble("dimensionCollapseSystem.sounds.portalTravelPitchCritical").toFloat()
    val PORTAL_TRAVEL_PITCH_VARIATION: Float get() = ConfigLoader.getDouble("dimensionCollapseSystem.sounds.portalTravelPitchVariation").toFloat()
    val VOID_HOLE_SOUND_VOLUME: Float get() = ConfigLoader.getDouble("dimensionCollapseSystem.sounds.voidHoleSoundVolume").toFloat()
    val VOID_HOLE_SOUND_PITCH: Float get() = ConfigLoader.getDouble("dimensionCollapseSystem.sounds.voidHoleSoundPitch").toFloat()
    val COLUMN_DELETION_SOUND_VOLUME: Float get() = ConfigLoader.getDouble("dimensionCollapseSystem.sounds.columnDeletionSoundVolume").toFloat()

    // ===== FE (FORGE ENERGY) SYSTEM =====
    /** Maximum FE storage per obelisk (balanced for ~30 second runs with 1 player) */
    val MAX_FE_STORAGE: Int get() = ConfigLoader.getInt("feSystem.maxFEStorage")
    /** Base FE drain per tick (20 ticks = 1 second) */
    val BASE_FE_DRAIN_PER_TICK: Int get() = ConfigLoader.getInt("feSystem.baseFEDrainPerTick")
    /** Additional FE drain per player per tick */
    val PER_PLAYER_FE_DRAIN: Int get() = ConfigLoader.getInt("feSystem.perPlayerFEDrain")
    /** FE regeneration per tick when idle (no active run) */
    val FE_REGEN_PER_TICK: Int get() = ConfigLoader.getInt("feSystem.feRegenPerTick")

    // Exponential drain increase
    val DRAIN_EXPONENTIAL_FACTOR: Double get() = ConfigLoader.getDouble("feSystem.drainExponentialFactor")
    val DRAIN_EXPONENTIAL_INTERVAL_TICKS: Int get() = ConfigLoader.getInt("feSystem.drainExponentialIntervalTicks")

    // ===== RUN MANAGEMENT =====
    /** Maximum concurrent runs per obelisk */
    val MAX_CONCURRENT_RUNS_PER_OBELISK: Int get() = ConfigLoader.getInt("runManagement.maxConcurrentRunsPerObelisk")
    /** Delay before cleaning up empty run dimensions (ticks) */
    val RUN_CLEANUP_DELAY_TICKS: Int get() = ConfigLoader.getInt("runManagement.runCleanupDelayTicks")
    /** Allow breaking obelisk while run is active */
    val ALLOW_BREAK_WHILE_ACTIVE: Boolean get() = ConfigLoader.getBoolean("runManagement.allowBreakWhileActive")
    /** Resume runs on player login */
    val RESUME_RUNS_ON_LOGIN: Boolean get() = ConfigLoader.getBoolean("runManagement.resumeRunsOnLogin")

    // ===== PLAYER SAFETY =====
    /** Y-level threshold for void fall detection */
    val VOID_FALL_Y_THRESHOLD: Double get() = ConfigLoader.getDouble("playerSafety.voidFallYThreshold")
    /** Safe Y minimum for player positions */
    val SAFE_Y_MIN: Int get() = ConfigLoader.getInt("playerSafety.safeYMin")
    /** Safe Y maximum for player positions */
    val SAFE_Y_MAX: Int get() = ConfigLoader.getInt("playerSafety.safeYMax")

    // ===== OBELISK PLACEMENT =====
    /** Maximum search distance downward when finding ground */
    val GROUND_SEARCH_DEPTH_PLACER: Int get() = ConfigLoader.getInt("obeliskPlacement.groundSearchDepthPlacer")
    /** Maximum search distance downward during worldgen */
    val GROUND_SEARCH_DEPTH_WORLDGEN: Int get() = ConfigLoader.getInt("obeliskPlacement.groundSearchDepthWorldgen")

    // ===== DIMENSION COMMAND SYSTEM =====
    /** Chunk radius to force-load around spawn for player entry */
    val ENTER_FORCE_LOAD_RADIUS: Int get() = ConfigLoader.getInt("dimensionCommandSystem.enterForceLoadRadius")
    /** Ticket level for chunk loading */
    val CHUNK_TICKET_LEVEL: Int get() = ConfigLoader.getInt("dimensionCommandSystem.chunkTicketLevel")

    // ===== BOSS BAR COLORS =====
    // These are semantic constants referencing Minecraft's BossBarColor enum values
    // Usage: BossEvent.BossBarColor.GREEN, .YELLOW, .RED
    val BOSS_BAR_GREEN_THRESHOLD: Double get() = ConfigLoader.getDouble("bossBarColors.bossBarGreenThreshold")
    val BOSS_BAR_YELLOW_THRESHOLD: Double get() = ConfigLoader.getDouble("bossBarColors.bossBarYellowThreshold")

    // ===== TELEPORT SYSTEM =====
    /** Position offset for entity centering (0.5 blocks) */
    val TELEPORT_CENTER_OFFSET: Double get() = ConfigLoader.getDouble("teleportSystem.teleportCenterOffset")
    /** Position offset for spawn above return pad */
    val TELEPORT_SPAWN_HEIGHT_OFFSET: Double get() = ConfigLoader.getDouble("teleportSystem.teleportSpawnHeightOffset")

    // ===== CONFIGURATION MODE =====
    /** Base type selection mode: "random" or "manual" */
    val BASE_TYPE_SELECTION_MODE: String get() = ConfigLoader.getString("configurationMode.baseTypeSelectionMode")

    // ===== GAME CONSTANTS =====
    /** Minecraft ticks per second */
    val TICKS_PER_SECOND: Int get() = ConfigLoader.getInt("gameConstants.ticksPerSecond")

    // ===== DIMENSION SLOT SYSTEM =====
    /** Number of NETHER-type dimension slots available */
    val NETHER_SLOT_COUNT: Int get() = ConfigLoader.getInt("dimensionSlotSystem.netherSlotCount")
    /** Number of END-type dimension slots available */
    val END_SLOT_COUNT: Int get() = ConfigLoader.getInt("dimensionSlotSystem.endSlotCount")
    /** Minimum X coordinate for random spawn positions */
    val SPAWN_POS_X_MIN: Int get() = ConfigLoader.getInt("dimensionSlotSystem.spawnPositionRange.xMin")
    /** Maximum X coordinate for random spawn positions */
    val SPAWN_POS_X_MAX: Int get() = ConfigLoader.getInt("dimensionSlotSystem.spawnPositionRange.xMax")
    /** Minimum Z coordinate for random spawn positions */
    val SPAWN_POS_Z_MIN: Int get() = ConfigLoader.getInt("dimensionSlotSystem.spawnPositionRange.zMin")
    /** Maximum Z coordinate for random spawn positions */
    val SPAWN_POS_Z_MAX: Int get() = ConfigLoader.getInt("dimensionSlotSystem.spawnPositionRange.zMax")
    /** Y coordinate for NETHER spawn positions */
    val SPAWN_POS_NETHER_Y: Int get() = ConfigLoader.getInt("dimensionSlotSystem.spawnPositionRange.netherY")
    /** Y coordinate for END spawn positions */
    val SPAWN_POS_END_Y: Int get() = ConfigLoader.getInt("dimensionSlotSystem.spawnPositionRange.endY")

    // ===== EMERALD REWARD SYSTEM =====
    /** Minimum emeralds dropped per monster killed */
    val EMERALD_REWARD_MIN_PER_KILL: Int get() = ConfigLoader.getInt("emeraldRewardSystem.emeraldRewardMinPerKill")
    /** Maximum emeralds dropped per monster killed */
    val EMERALD_REWARD_MAX_PER_KILL: Int get() = ConfigLoader.getInt("emeraldRewardSystem.emeraldRewardMaxPerKill")
    /** Enable emerald rewards at obelisk after run ends */
    val EMERALD_REWARDS_ENABLED: Boolean get() = ConfigLoader.getBoolean("emeraldRewardSystem.emeraldRewardsEnabled")
}
