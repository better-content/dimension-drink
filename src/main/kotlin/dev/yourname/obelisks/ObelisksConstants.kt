package dev.yourname.obelisks

import dev.yourname.obelisks.config.ConfigManager

/**
 * Centralized constants for the Obelisks mod.
 * All values are loaded from JSON configuration files once and cached in memory.
 */
object ObelisksConstants {

    // Load configuration on initialization
    init {
        ConfigManager.load()
    }

    private val config get() = ConfigManager.getMainConfig()

    // ===== MOD IDENTITY =====
    /** The unique mod identifier used for registration and resource paths. */
    val MOD_ID: String get() = config.modIdentity.modId

    // ===== DIMENSION SPAWN PLATFORM =====
    /** Platform generation fixed Y level */
    val PLATFORM_Y_LEVEL: Int get() = config.dimensionSpawnPlatform.platformYLevel
    /** Platform radius (creates 7x7 platform with radius 3) */
    val PLATFORM_RADIUS: Int get() = config.dimensionSpawnPlatform.platformRadius
    /** Vertical clearance above platform (blocks of air) */
    val PLATFORM_AIR_CLEARANCE: Int get() = config.dimensionSpawnPlatform.platformAirClearance
    /** Chunk force-load radius around spawn platform */
    val PLATFORM_CHUNK_LOAD_RADIUS: Int get() = config.dimensionSpawnPlatform.platformChunkLoadRadius
    /** Position offset for return pad above platform ground */
    val RETURN_PAD_HEIGHT: Int get() = config.dimensionSpawnPlatform.returnPadHeight
    /** Air cube size above return pad for player spawn */
    val RETURN_PAD_AIR_CUBE_HEIGHT: Int get() = config.dimensionSpawnPlatform.returnPadAirCubeHeight
    /** Lighting placement height above platform */
    val PLATFORM_LIGHT_HEIGHT: Int get() = config.dimensionSpawnPlatform.platformLightHeight

    // ===== DIMENSION COLLAPSE SYSTEM =====
    /** Target percentage of blocks deleted when FE reaches 0% */
    val TARGET_DELETION_AT_ZERO_FE: Double get() = config.dimensionCollapseSystem.targetDeletionAtZeroFE
    /** FE threshold where boss bar becomes visible */
    val BOSS_BAR_SHOW_THRESHOLD: Double get() = config.dimensionCollapseSystem.bossBarShowThreshold
    /** FE threshold where decay phase begins (block deletion starts) */
    val DECAY_PHASE_THRESHOLD: Double get() = config.dimensionCollapseSystem.decayPhaseThreshold
    /** FE threshold where critical collapse begins (aggressive deletion) */
    val CRITICAL_COLLAPSE_THRESHOLD: Double get() = config.dimensionCollapseSystem.criticalCollapseThreshold

    // Deletion rate parameters (blocks per tick)
    /** Initial deletion rate when decay starts */
    val INITIAL_DELETION_RATE: Int get() = config.dimensionCollapseSystem.deletionRates.initialDeletionRate
    /** Initial deletion rate during critical collapse */
    val INITIAL_CRITICAL_DELETION_RATE: Int get() = config.dimensionCollapseSystem.deletionRates.initialCriticalDeletionRate
    /** Minimum deletion rate */
    val MIN_DELETION_RATE: Int get() = config.dimensionCollapseSystem.deletionRates.minDeletionRate
    /** Maximum deletion rate during decay */
    val MAX_DELETION_RATE: Int get() = config.dimensionCollapseSystem.deletionRates.maxDeletionRate
    /** Maximum deletion rate during critical collapse */
    val MAX_CRITICAL_DELETION_RATE: Int get() = config.dimensionCollapseSystem.deletionRates.maxCriticalDeletionRate
    /** Minimum deletion rate during critical collapse */
    val MIN_CRITICAL_DELETION_RATE: Int get() = config.dimensionCollapseSystem.deletionRates.minCriticalDeletionRate

    // Deletion rate adjustment thresholds (difference from target)
    val DELETION_DIFF_HUGE: Int get() = config.dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffHuge
    val DELETION_DIFF_LARGE: Int get() = config.dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffLarge
    val DELETION_DIFF_MEDIUM: Int get() = config.dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffMedium
    val DELETION_DIFF_SMALL: Int get() = config.dimensionCollapseSystem.deletionAdjustmentThresholds.deletionDiffSmall

    // Deletion rate multipliers
    val RATE_INCREASE_HUGE: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateIncreaseHuge
    val RATE_INCREASE_LARGE: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateIncreaseLarge
    val RATE_DECREASE_SMALL: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateDecreaseSmall
    val RATE_DECREASE_LARGE: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateDecreaseLarge
    val RATE_INCREASE_CRITICAL_HUGE: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateIncreaseCriticalHuge
    val RATE_INCREASE_CRITICAL_LARGE: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateIncreaseCriticalLarge
    val RATE_INCREASE_CRITICAL_MEDIUM: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateIncreaseCriticalMedium
    val RATE_DECREASE_CRITICAL: Double get() = config.dimensionCollapseSystem.rateMultipliers.rateDecreaseCritical

    // Column deletion parameters
    /** Random additional columns deleted (min) during decay */
    val DECAY_EXTRA_COLUMNS_MIN: Int get() = config.dimensionCollapseSystem.columnDeletion.decayExtraColumnsMin
    /** Random additional columns deleted (max) during decay */
    val DECAY_EXTRA_COLUMNS_MAX: Int get() = config.dimensionCollapseSystem.columnDeletion.decayExtraColumnsMax
    /** Guaranteed columns near each player per second during decay */
    val DECAY_GUARANTEED_COLUMNS_PER_PLAYER: Int get() = config.dimensionCollapseSystem.columnDeletion.decayGuaranteedColumnsPerPlayer
    /** Random additional columns deleted (min) during critical collapse */
    val CRITICAL_EXTRA_COLUMNS_MIN: Int get() = config.dimensionCollapseSystem.columnDeletion.criticalExtraColumnsMin
    /** Random additional columns deleted (max) during critical collapse */
    val CRITICAL_EXTRA_COLUMNS_MAX: Int get() = config.dimensionCollapseSystem.columnDeletion.criticalExtraColumnsMax
    /** Guaranteed columns near each player per second during critical collapse */
    val CRITICAL_GUARANTEED_COLUMNS_PER_PLAYER: Int get() = config.dimensionCollapseSystem.columnDeletion.criticalGuaranteedColumnsPerPlayer
    /** Column deletion width (creates 2x2 columns) */
    val COLUMN_WIDTH: Int get() = config.dimensionCollapseSystem.columnDeletion.columnWidth
    /** Maximum radius for column placement near players */
    val COLUMN_PLACEMENT_RADIUS: Int get() = config.dimensionCollapseSystem.columnDeletion.columnPlacementRadius
    /** Vertical step for particle spawning along columns */
    val COLUMN_PARTICLE_STEP: Int get() = config.dimensionCollapseSystem.columnDeletion.columnParticleStep

    // Block deletion ranges
    /** Radius for random block deletion around player during decay */
    val RANDOM_DELETION_RADIUS: Int get() = config.dimensionCollapseSystem.blockDeletion.randomDeletionRadius
    /** Y offset range for random block deletion (min) */
    val RANDOM_DELETION_Y_MIN: Int get() = config.dimensionCollapseSystem.blockDeletion.randomDeletionYMin
    /** Y offset range for random block deletion (max) */
    val RANDOM_DELETION_Y_MAX: Int get() = config.dimensionCollapseSystem.blockDeletion.randomDeletionYMax
    /** Particle spawn interval (every N blocks deleted) */
    val DELETION_PARTICLE_INTERVAL: Int get() = config.dimensionCollapseSystem.blockDeletion.deletionParticleInterval

    // Block count estimation
    /** Estimated blocks per chunk section (16x16x16 with 50% solid) */
    val ESTIMATED_BLOCKS_PER_SECTION: Int get() = config.dimensionCollapseSystem.blockCounting.estimatedBlocksPerSection
    /** Minimum estimated block count for dimension */
    val MIN_ESTIMATED_BLOCKS: Int get() = config.dimensionCollapseSystem.blockCounting.minEstimatedBlocks
    /** Chunk view distance for block counting */
    val BLOCK_COUNT_VIEW_DISTANCE: Int get() = config.dimensionCollapseSystem.blockCounting.blockCountViewDistance

    // Effect intervals (server ticks)
    /** How often to log collapse progress (20 ticks = 1 second) */
    val COLLAPSE_LOG_INTERVAL: Int get() = config.dimensionCollapseSystem.effectIntervals.collapseLogInterval

    // Void hole parameters
    val VOID_HOLE_RADIUS: Int get() = config.dimensionCollapseSystem.voidHole.voidHoleRadius
    val VOID_PORTAL_PARTICLE_COUNT: Int get() = config.dimensionCollapseSystem.voidHole.voidPortalParticleCount

    // Block removal effect radii (old system - kept for legacy support)
    val BLOCK_SWAP_RADIUS: Int get() = config.dimensionCollapseSystem.legacyEffects.blockSwapRadius
    val BLOCK_SWAP_Y_OFFSET: Int get() = config.dimensionCollapseSystem.legacyEffects.blockSwapYOffset
    val BLOCK_SWAP_ATTEMPTS: Int get() = config.dimensionCollapseSystem.legacyEffects.blockSwapAttempts
    val BLOCK_REMOVAL_RADIUS: Int get() = config.dimensionCollapseSystem.legacyEffects.blockRemovalRadius
    val BLOCK_REMOVAL_Y_OFFSET: Int get() = config.dimensionCollapseSystem.legacyEffects.blockRemovalYOffset
    val BLOCK_REMOVAL_ATTEMPTS: Int get() = config.dimensionCollapseSystem.legacyEffects.blockRemovalAttempts
    val INDIVIDUAL_DELETION_RADIUS: Int get() = config.dimensionCollapseSystem.legacyEffects.individualDeletionRadius
    val INDIVIDUAL_DELETION_Y_OFFSET: Int get() = config.dimensionCollapseSystem.legacyEffects.individualDeletionYOffset
    val INDIVIDUAL_DELETION_ATTEMPTS: Int get() = config.dimensionCollapseSystem.legacyEffects.individualDeletionAttempts
    val RANDOM_COLUMN_RADIUS: Int get() = config.dimensionCollapseSystem.legacyEffects.randomColumnRadius

    // Particle parameters
    val SMOKE_PARTICLE_COUNT: Int get() = config.dimensionCollapseSystem.particles.smokeParticleCount
    val LARGE_SMOKE_PARTICLE_COUNT: Int get() = config.dimensionCollapseSystem.particles.largeSmokeParticleCount
    val PORTAL_PARTICLE_COUNT: Int get() = config.dimensionCollapseSystem.particles.portalParticleCount
    val PARTICLE_SPEED: Double get() = config.dimensionCollapseSystem.particles.particleSpeed
    val PARTICLE_SPREAD: Double get() = config.dimensionCollapseSystem.particles.particleSpread
    val PORTAL_PARTICLE_SPEED: Double get() = config.dimensionCollapseSystem.particles.portalParticleSpeed

    // Sound parameters
    val PORTAL_TRAVEL_VOLUME: Float get() = config.dimensionCollapseSystem.sounds.portalTravelVolume
    val PORTAL_TRAVEL_PITCH_BASE: Float get() = config.dimensionCollapseSystem.sounds.portalTravelPitchBase
    val PORTAL_TRAVEL_PITCH_CRITICAL: Float get() = config.dimensionCollapseSystem.sounds.portalTravelPitchCritical
    val PORTAL_TRAVEL_PITCH_VARIATION: Float get() = config.dimensionCollapseSystem.sounds.portalTravelPitchVariation
    val VOID_HOLE_SOUND_VOLUME: Float get() = config.dimensionCollapseSystem.sounds.voidHoleSoundVolume
    val VOID_HOLE_SOUND_PITCH: Float get() = config.dimensionCollapseSystem.sounds.voidHoleSoundPitch
    val COLUMN_DELETION_SOUND_VOLUME: Float get() = config.dimensionCollapseSystem.sounds.columnDeletionSoundVolume

    // ===== FE (FORGE ENERGY) SYSTEM =====
    /** Maximum FE storage per obelisk (balanced for ~30 second runs with 1 player) */
    val MAX_FE_STORAGE: Int get() = config.feSystem.maxFEStorage
    /** Base FE drain per tick (20 ticks = 1 second) */
    val BASE_FE_DRAIN_PER_TICK: Int get() = config.feSystem.baseFEDrainPerTick
    /** Additional FE drain per player per tick */
    val PER_PLAYER_FE_DRAIN: Int get() = config.feSystem.perPlayerFEDrain
    /** FE regeneration per tick when idle (no active run) */
    val FE_REGEN_PER_TICK: Int get() = config.feSystem.feRegenPerTick

    // Exponential drain increase
    val DRAIN_EXPONENTIAL_FACTOR: Double get() = config.feSystem.drainExponentialFactor
    val DRAIN_EXPONENTIAL_INTERVAL_TICKS: Int get() = config.feSystem.drainExponentialIntervalTicks

    // ===== RUN MANAGEMENT =====
    /** Maximum concurrent runs per obelisk */
    val MAX_CONCURRENT_RUNS_PER_OBELISK: Int get() = config.runManagement.maxConcurrentRunsPerObelisk
    /** Delay before cleaning up empty run dimensions (ticks) */
    val RUN_CLEANUP_DELAY_TICKS: Int get() = config.runManagement.runCleanupDelayTicks
    /** Allow breaking obelisk while run is active */
    val ALLOW_BREAK_WHILE_ACTIVE: Boolean get() = config.runManagement.allowBreakWhileActive
    /** Resume runs on player login */
    val RESUME_RUNS_ON_LOGIN: Boolean get() = config.runManagement.resumeRunsOnLogin

    // ===== PLAYER SAFETY =====
    /** Y-level threshold for void fall detection */
    val VOID_FALL_Y_THRESHOLD: Double get() = config.playerSafety.voidFallYThreshold
    /** Safe Y minimum for player positions */
    val SAFE_Y_MIN: Int get() = config.playerSafety.safeYMin
    /** Safe Y maximum for player positions */
    val SAFE_Y_MAX: Int get() = config.playerSafety.safeYMax

    // ===== OBELISK PLACEMENT =====
    /** Maximum search distance downward when finding ground */
    val GROUND_SEARCH_DEPTH_PLACER: Int get() = config.obeliskPlacement.groundSearchDepthPlacer
    /** Maximum search distance downward during worldgen */
    val GROUND_SEARCH_DEPTH_WORLDGEN: Int get() = config.obeliskPlacement.groundSearchDepthWorldgen

    // ===== DIMENSION COMMAND SYSTEM =====
    /** Chunk radius to force-load around spawn for player entry */
    val ENTER_FORCE_LOAD_RADIUS: Int get() = config.dimensionCommandSystem.enterForceLoadRadius
    /** Ticket level for chunk loading */
    val CHUNK_TICKET_LEVEL: Int get() = config.dimensionCommandSystem.chunkTicketLevel

    // ===== BOSS BAR COLORS =====
    // These are semantic constants referencing Minecraft's BossBarColor enum values
    // Usage: BossEvent.BossBarColor.GREEN, .YELLOW, .RED
    val BOSS_BAR_GREEN_THRESHOLD: Double get() = config.bossBarColors.bossBarGreenThreshold
    val BOSS_BAR_YELLOW_THRESHOLD: Double get() = config.bossBarColors.bossBarYellowThreshold

    // ===== TELEPORT SYSTEM =====
    /** Position offset for entity centering (0.5 blocks) */
    val TELEPORT_CENTER_OFFSET: Double get() = config.teleportSystem.teleportCenterOffset
    /** Position offset for spawn above return pad */
    val TELEPORT_SPAWN_HEIGHT_OFFSET: Double get() = config.teleportSystem.teleportSpawnHeightOffset

    // ===== CONFIGURATION MODE =====
    /** Base type selection mode: "random" or "manual" */
    val BASE_TYPE_SELECTION_MODE: String get() = config.configurationMode.baseTypeSelectionMode

    // ===== GAME CONSTANTS =====
    /** Minecraft ticks per second */
    val TICKS_PER_SECOND: Int get() = config.gameConstants.ticksPerSecond

    // ===== DIMENSION SLOT SYSTEM =====
    /** Number of NETHER-type dimension slots available */
    val NETHER_SLOT_COUNT: Int get() = config.dimensionSlotSystem.netherSlotCount
    /** Number of END-type dimension slots available */
    val END_SLOT_COUNT: Int get() = config.dimensionSlotSystem.endSlotCount
    /** Minimum X coordinate for random spawn positions */
    val SPAWN_POS_X_MIN: Int get() = config.dimensionSlotSystem.spawnPositionRange.xMin
    /** Maximum X coordinate for random spawn positions */
    val SPAWN_POS_X_MAX: Int get() = config.dimensionSlotSystem.spawnPositionRange.xMax
    /** Minimum Z coordinate for random spawn positions */
    val SPAWN_POS_Z_MIN: Int get() = config.dimensionSlotSystem.spawnPositionRange.zMin
    /** Maximum Z coordinate for random spawn positions */
    val SPAWN_POS_Z_MAX: Int get() = config.dimensionSlotSystem.spawnPositionRange.zMax
    /** Y coordinate for NETHER spawn positions */
    val SPAWN_POS_NETHER_Y: Int get() = config.dimensionSlotSystem.spawnPositionRange.netherY
    /** Y coordinate for END spawn positions */
    val SPAWN_POS_END_Y: Int get() = config.dimensionSlotSystem.spawnPositionRange.endY

    // ===== EMERALD REWARD SYSTEM =====
    /** Minimum emeralds dropped per monster killed */
    val EMERALD_REWARD_MIN_PER_KILL: Int get() = config.emeraldRewardSystem.emeraldRewardMinPerKill
    /** Maximum emeralds dropped per monster killed */
    val EMERALD_REWARD_MAX_PER_KILL: Int get() = config.emeraldRewardSystem.emeraldRewardMaxPerKill
    /** Enable emerald rewards at obelisk after run ends */
    val EMERALD_REWARDS_ENABLED: Boolean get() = config.emeraldRewardSystem.emeraldRewardsEnabled

    // ===== VISUAL AND AUDIO EFFECTS =====
    /** Maximum particles that can spawn per tick globally */
    val MAX_PARTICLES_PER_TICK: Int get() = config.visualAndAudioEffects.maxParticlesPerTick
    /** Maximum sounds that can play per tick globally */
    val MAX_SOUNDS_PER_TICK: Int get() = config.visualAndAudioEffects.maxSoundsPerTick
    /** Enable beacon beam VFX for active obelisks */
    val OBELISK_BEAM_ENABLED: Boolean get() = config.visualAndAudioEffects.obeliskBeamEnabled
    /** Enable activation sound when obelisk run starts */
    val OBELISK_ACTIVATION_SOUND_ENABLED: Boolean get() = config.visualAndAudioEffects.obeliskActivationSoundEnabled
    /** Enable block break sounds during dimension collapse */
    val COLLAPSE_BLOCK_BREAK_SOUND_ENABLED: Boolean get() = config.visualAndAudioEffects.collapseBlockBreakSoundEnabled
    /** Chance (0.0-1.0) that a block deletion will play break sound */
    val COLLAPSE_BLOCK_BREAK_SOUND_CHANCE: Double get() = config.visualAndAudioEffects.collapseBlockBreakSoundChance
}
