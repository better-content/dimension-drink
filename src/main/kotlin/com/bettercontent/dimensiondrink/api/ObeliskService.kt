package com.bettercontent.dimensiondrink.api

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.UUID

interface ObeliskService {
    fun getState(server: MinecraftServer, obeliskId: UUID): ObeliskState?
    fun listLoaded(server: MinecraftServer): List<ObeliskState>
    fun findNearest(level: ServerLevel, center: BlockPos, radiusChunks: Int = 8): ObeliskState?
    fun getDefinition(definitionId: String): ObeliskDefinitionState?
    fun listDefinitions(): List<ObeliskDefinitionState>
}

data class ObeliskState(
    val obeliskId: UUID,
    val definitionId: String,
    val targetTemplateId: String?,
    val activeRunId: UUID?,
    val levelKey: ResourceKey<Level>,
    val blockPos: BlockPos,
    val chargeStored: Int,
    val maxChargeStored: Int,
    val cooldownTicksRemaining: Int,
    val beamVisible: Boolean,
    val modifiers: List<ObeliskModifierState>
)

data class ObeliskModifierState(
    val stat: String,
    val bonusPercent: Int
)

data class ObeliskDefinitionState(
    val id: String,
    val displayName: String,
    val targetDimension: String,
    val instanceTemplateId: String,
    val rewardTableId: String,
    val enabled: Boolean
)
