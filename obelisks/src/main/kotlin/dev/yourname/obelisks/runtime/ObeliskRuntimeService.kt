package dev.yourname.obelisks.runtime

import dev.yourname.obelisks.api.ObeliskModifierState
import dev.yourname.obelisks.api.ObeliskDefinitionState
import dev.yourname.obelisks.api.ObeliskService
import dev.yourname.obelisks.api.ObeliskState
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.LevelChunk
import java.util.UUID

object ObeliskRuntimeService : ObeliskService {
    private val loadedObelisks = linkedMapOf<UUID, ObeliskBlockEntity>()

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
        val indexed = loadedObelisks[obeliskId]
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
                instanceTemplateId = definition.instanceTemplateId,
                rewardTableId = definition.rewardTableId,
                enabled = definition.enabled
            )
        }
    }

    override fun listDefinitions(): List<ObeliskDefinitionState> {
        return ObeliskDataManager.allObelisks().map { definition ->
            ObeliskDefinitionState(
                id = definition.id,
                displayName = definition.displayName,
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
        loadedObelisks[obelisk.obeliskId] = obelisk
    }

    fun unregisterLoaded(obelisk: ObeliskBlockEntity) {
        loadedObelisks.remove(obelisk.obeliskId, obelisk)
    }

    internal fun ObeliskBlockEntity.toState(level: ServerLevel = this.level as ServerLevel): ObeliskState {
        return ObeliskState(
            obeliskId = obeliskId,
            definitionId = definitionId,
            targetTemplateId = targetTemplateId,
            activeRunId = activeRunId,
            levelKey = level.dimension(),
            blockPos = blockPos.immutable(),
            energyStored = getEnergyStored(),
            maxEnergyStored = getMaxEnergyStored(),
            cooldownTicksRemaining = getCooldownRemainingTicks().toInt(),
            beamVisible = shouldShowBeam(),
            modifiers = modifiers.map { ObeliskModifierState(it.stat.name, it.bonusPercent) }
        )
    }
}
