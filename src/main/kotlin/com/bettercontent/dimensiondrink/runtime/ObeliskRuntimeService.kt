package com.bettercontent.dimensiondrink.runtime

import com.bettercontent.dimensiondrink.api.ObeliskModifierState
import com.bettercontent.dimensiondrink.api.ObeliskDefinitionState
import com.bettercontent.dimensiondrink.api.ObeliskService
import com.bettercontent.dimensiondrink.api.ObeliskState
import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.LevelChunk
import java.util.UUID

object ObeliskRuntimeService : ObeliskService {
    private val loadedDimensionDrinks = linkedMapOf<UUID, ObeliskBlockEntity>()

    override fun getState(server: MinecraftServer, obeliskId: UUID): ObeliskState? {
        return findObelisk(server, obeliskId)?.toState()
    }

    override fun listLoaded(server: MinecraftServer): List<ObeliskState> {
        return server.allLevels
            .asSequence()
            .filterIsInstance<ServerLevel>()
            .flatMap { level ->
                loadedChunks(level).asSequence()
                    .flatMap { chunk -> chunk.blockEntities.values.asSequence() }
                    .filterIsInstance<ObeliskBlockEntity>()
                    .map { it.toState() }
            }
            .toList()
    }

    fun findObelisk(server: MinecraftServer, obeliskId: UUID): ObeliskBlockEntity? {
        val indexed = loadedDimensionDrinks[obeliskId]
        if (indexed != null && indexed.level?.server === server && !indexed.isRemoved) {
            return indexed
        }
        return null
    }

    override fun findNearest(level: ServerLevel, center: BlockPos, radiusChunks: Int): ObeliskState? {
        return findNearestObelisk(level, center, radiusChunks)?.toState()
    }

    override fun getDefinition(definitionId: String): ObeliskDefinitionState? {
        return ObeliskDataManager.getObelisk(definitionId)?.let { definition ->
            ObeliskDefinitionState(
                id = definition.id,
                displayName = definition.displayName,
                targetDimension = definition.targetDimension ?: definition.instanceTemplateId,
                instanceTemplateId = definition.instanceTemplateId,
                rewardTableId = definition.rewardTableId,
                enabled = definition.enabled
            )
        }
    }

    override fun listDefinitions(): List<ObeliskDefinitionState> {
        return ObeliskDataManager.allDimensionDrinks().map { definition ->
            ObeliskDefinitionState(
                id = definition.id,
                displayName = definition.displayName,
                targetDimension = definition.targetDimension ?: definition.instanceTemplateId,
                instanceTemplateId = definition.instanceTemplateId,
                rewardTableId = definition.rewardTableId,
                enabled = definition.enabled
            )
        }
    }

    fun findNearestObelisk(level: ServerLevel, center: BlockPos, radiusChunks: Int): ObeliskBlockEntity? {
        val centerChunkX = center.x shr 4
        val centerChunkZ = center.z shr 4
        var nearest: ObeliskBlockEntity? = null
        var nearestDistance = Double.MAX_VALUE

        for (chunkX in (centerChunkX - radiusChunks)..(centerChunkX + radiusChunks)) {
            for (chunkZ in (centerChunkZ - radiusChunks)..(centerChunkZ + radiusChunks)) {
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                chunk.blockEntities.values
                    .asSequence()
                    .filterIsInstance<ObeliskBlockEntity>()
                    .forEach { obelisk ->
                        val distance = obelisk.blockPos.distSqr(center)
                        if (distance < nearestDistance) {
                            nearestDistance = distance
                            nearest = obelisk
                        }
                    }
            }
        }

        return nearest
    }

    private fun loadedChunks(level: ServerLevel): Sequence<LevelChunk> {
        val chunkSource = level.chunkSource
        val center = level.sharedSpawnPos
        val centerChunkX = center.x shr 4
        val centerChunkZ = center.z shr 4
        val radiusChunks = 16
        return sequence {
            for (chunkX in (centerChunkX - radiusChunks)..(centerChunkX + radiusChunks)) {
                for (chunkZ in (centerChunkZ - radiusChunks)..(centerChunkZ + radiusChunks)) {
                    val chunk = chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                    yield(chunk)
                }
            }
        }
    }

    fun registerLoaded(obelisk: ObeliskBlockEntity) {
        loadedDimensionDrinks[obelisk.obeliskId] = obelisk
    }

    fun unregisterLoaded(obelisk: ObeliskBlockEntity) {
        loadedDimensionDrinks.remove(obelisk.obeliskId, obelisk)
    }

    internal fun ObeliskBlockEntity.toState(level: ServerLevel = this.level as ServerLevel): ObeliskState {
        return ObeliskState(
            obeliskId = obeliskId,
            definitionId = definitionId,
            targetTemplateId = targetTemplateId,
            activeRunId = activeRunId,
            levelKey = level.dimension(),
            blockPos = blockPos.immutable(),
            chargeStored = getChargeStored(),
            maxChargeStored = getMaxChargeStored(),
            cooldownTicksRemaining = getCooldownRemainingTicks().toInt(),
            beamVisible = shouldShowBeam(),
            modifiers = modifiers.map { ObeliskModifierState(it.stat.name, it.bonusPercent) }
        )
    }
}
