package dev.yourname.obelisks.config

/**
 * Main configuration data class that matches obelisks.json structure.
 * All fields are read from JSON once and kept in memory.
 */
data class MainConfig(
    val modIdentity: ModIdentity,
    val dimensionSpawnPlatform: DimensionSpawnPlatform,
    val dimensionCollapseSystem: DimensionCollapseSystem,
    val feSystem: FESystem,
    val runManagement: RunManagement,
    val playerSafety: PlayerSafety,
    val obeliskPlacement: ObeliskPlacement,
    val dimensionCommandSystem: DimensionCommandSystem,
    val bossBarColors: BossBarColors,
    val teleportSystem: TeleportSystem,
    val configurationMode: ConfigurationMode,
    val gameConstants: GameConstants,
    val dimensionSlotSystem: DimensionSlotSystem,
    val emeraldRewardSystem: EmeraldRewardSystem,
    val visualAndAudioEffects: VisualAndAudioEffects
)

data class ModIdentity(
    val modId: String
)

data class DimensionSpawnPlatform(
    val platformYLevel: Int,
    val platformRadius: Int,
    val platformAirClearance: Int,
    val platformChunkLoadRadius: Int,
    val returnPadHeight: Int,
    val returnPadAirCubeHeight: Int,
    val platformLightHeight: Int
)

data class DimensionCollapseSystem(
    val targetDeletionAtZeroFE: Double,
    val bossBarShowThreshold: Double,
    val decayPhaseThreshold: Double,
    val criticalCollapseThreshold: Double,
    val deletionRates: DeletionRates,
    val deletionAdjustmentThresholds: DeletionAdjustmentThresholds,
    val rateMultipliers: RateMultipliers,
    val columnDeletion: ColumnDeletion,
    val blockDeletion: BlockDeletion,
    val blockCounting: BlockCounting,
    val effectIntervals: EffectIntervals,
    val voidHole: VoidHole,
    val legacyEffects: LegacyEffects,
    val particles: Particles,
    val sounds: Sounds
)

data class DeletionRates(
    val initialDeletionRate: Int,
    val initialCriticalDeletionRate: Int,
    val minDeletionRate: Int,
    val maxDeletionRate: Int,
    val maxCriticalDeletionRate: Int,
    val minCriticalDeletionRate: Int
)

data class DeletionAdjustmentThresholds(
    val deletionDiffHuge: Int,
    val deletionDiffLarge: Int,
    val deletionDiffMedium: Int,
    val deletionDiffSmall: Int
)

data class RateMultipliers(
    val rateIncreaseHuge: Double,
    val rateIncreaseLarge: Double,
    val rateDecreaseSmall: Double,
    val rateDecreaseLarge: Double,
    val rateIncreaseCriticalHuge: Double,
    val rateIncreaseCriticalLarge: Double,
    val rateIncreaseCriticalMedium: Double,
    val rateDecreaseCritical: Double
)

data class ColumnDeletion(
    val decayExtraColumnsMin: Int,
    val decayExtraColumnsMax: Int,
    val decayGuaranteedColumnsPerPlayer: Int,
    val criticalExtraColumnsMin: Int,
    val criticalExtraColumnsMax: Int,
    val criticalGuaranteedColumnsPerPlayer: Int,
    val columnWidth: Int,
    val columnPlacementRadius: Int,
    val columnParticleStep: Int
)

data class BlockDeletion(
    val randomDeletionRadius: Int,
    val randomDeletionYMin: Int,
    val randomDeletionYMax: Int,
    val deletionParticleInterval: Int
)

data class BlockCounting(
    val estimatedBlocksPerSection: Int,
    val minEstimatedBlocks: Int,
    val blockCountViewDistance: Int
)

data class EffectIntervals(
    val collapseLogInterval: Int
)

data class VoidHole(
    val voidHoleRadius: Int,
    val voidPortalParticleCount: Int
)

data class LegacyEffects(
    val blockSwapRadius: Int,
    val blockSwapYOffset: Int,
    val blockSwapAttempts: Int,
    val blockRemovalRadius: Int,
    val blockRemovalYOffset: Int,
    val blockRemovalAttempts: Int,
    val individualDeletionRadius: Int,
    val individualDeletionYOffset: Int,
    val individualDeletionAttempts: Int,
    val randomColumnRadius: Int
)

data class Particles(
    val smokeParticleCount: Int,
    val largeSmokeParticleCount: Int,
    val portalParticleCount: Int,
    val particleSpeed: Double,
    val particleSpread: Double,
    val portalParticleSpeed: Double
)

data class Sounds(
    val portalTravelVolume: Float,
    val portalTravelPitchBase: Float,
    val portalTravelPitchCritical: Float,
    val portalTravelPitchVariation: Float,
    val voidHoleSoundVolume: Float,
    val voidHoleSoundPitch: Float,
    val columnDeletionSoundVolume: Float
)

data class FESystem(
    val maxFEStorage: Int,
    val baseFEDrainPerTick: Int,
    val perPlayerFEDrain: Int,
    val feRegenPerTick: Int,
    val drainExponentialFactor: Double,
    val drainExponentialIntervalTicks: Int
)

data class RunManagement(
    val maxConcurrentRunsPerObelisk: Int,
    val runCleanupDelayTicks: Int,
    val allowBreakWhileActive: Boolean,
    val resumeRunsOnLogin: Boolean
)

data class PlayerSafety(
    val voidFallYThreshold: Double,
    val safeYMin: Int,
    val safeYMax: Int
)

data class ObeliskPlacement(
    val groundSearchDepthPlacer: Int,
    val groundSearchDepthWorldgen: Int
)

data class DimensionCommandSystem(
    val enterForceLoadRadius: Int,
    val chunkTicketLevel: Int
)

data class BossBarColors(
    val bossBarGreenThreshold: Double,
    val bossBarYellowThreshold: Double
)

data class TeleportSystem(
    val teleportCenterOffset: Double,
    val teleportSpawnHeightOffset: Double
)

data class ConfigurationMode(
    val baseTypeSelectionMode: String
)

data class GameConstants(
    val ticksPerSecond: Int
)

data class DimensionSlotSystem(
    val netherSlotCount: Int,
    val endSlotCount: Int,
    val spawnPositionRange: SpawnPositionRange
)

data class SpawnPositionRange(
    val xMin: Int,
    val xMax: Int,
    val zMin: Int,
    val zMax: Int,
    val netherY: Int,
    val endY: Int
)

data class EmeraldRewardSystem(
    val emeraldRewardMinPerKill: Int,
    val emeraldRewardMaxPerKill: Int,
    val emeraldRewardsEnabled: Boolean
)

data class VisualAndAudioEffects(
    val maxParticlesPerTick: Int,
    val maxSoundsPerTick: Int,
    val obeliskBeamEnabled: Boolean,
    val obeliskActivationSoundEnabled: Boolean,
    val collapseBlockBreakSoundEnabled: Boolean,
    val collapseBlockBreakSoundChance: Double
)
