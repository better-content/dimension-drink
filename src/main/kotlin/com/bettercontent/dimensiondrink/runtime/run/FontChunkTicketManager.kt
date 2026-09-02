package com.bettercontent.dimensiondrink.runtime.run

import com.bettercontent.dimensiondrink.MOD_ID
import com.mojang.logging.LogUtils
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.common.world.ForgeChunkManager
import java.util.UUID

/** Owns every fully ticking chunk ticket used by a live dimensional-font run. */
object FontChunkTicketManager {
    private val logger = LogUtils.getLogger()
    private val activeOwners = linkedSetOf<UUID>()

    fun registerValidationCallback() {
        ForgeChunkManager.setForcedChunkLoadingCallback(MOD_ID) { level, tickets ->
            val blockOwners = tickets.blockTickets.keys.toList()
            val entityOwners = tickets.entityTickets.keys.toList()
            blockOwners.forEach(tickets::removeAllTickets)
            entityOwners.forEach(tickets::removeAllTickets)
            val removed = blockOwners.size + entityOwners.size
            if (removed > 0) {
                logger.warn(
                    "Removed {} stale dimensional-font chunk ticket owners while loading {}",
                    removed,
                    level.dimension().location()
                )
            }
        }
    }

    fun acquire(server: MinecraftServer, record: RunRecord): Boolean {
        if (record.id in activeOwners) return true
        val level = record.originLevelKey?.let(server::getLevel) ?: return false
        val pos = record.originObeliskPos ?: return false
        val chunk = ChunkPos(pos)
        val acquired = ForgeChunkManager.forceChunk(level, MOD_ID, record.id, chunk.x, chunk.z, true, true)
        if (acquired) activeOwners += record.id
        return acquired
    }

    fun release(server: MinecraftServer, record: RunRecord) {
        val wasTracked = activeOwners.remove(record.id)
        val level = record.originLevelKey?.let(server::getLevel) ?: return
        val pos = record.originObeliskPos ?: return
        val chunk = ChunkPos(pos)
        val removed = ForgeChunkManager.forceChunk(level, MOD_ID, record.id, chunk.x, chunk.z, false, true)
        if (wasTracked && !removed) {
            logger.warn("Could not remove dimensional-font chunk ticket for run {}", record.id)
        }
    }

    fun hasTicket(runId: UUID): Boolean = runId in activeOwners

    fun activeTicketCount(): Int = activeOwners.size

    fun clearRuntimeState() {
        activeOwners.clear()
    }
}
