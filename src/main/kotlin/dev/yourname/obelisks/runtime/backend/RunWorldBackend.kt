package dev.yourname.obelisks.runtime.backend

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import java.util.UUID

interface RunWorldBackend {
    fun validateTemplate(server: MinecraftServer, templateId: String): String?

    fun requestPreparedSite(
        server: MinecraftServer,
        templateId: String,
        originLevelKey: ResourceKey<Level>?,
        originObeliskPos: BlockPos?
    ): PreparedSiteResult

    fun pollPreparedSite(server: MinecraftServer, handle: PreparedSiteHandle): PreparedSiteStatus

    fun activateRun(server: MinecraftServer, handle: PreparedSiteHandle, runId: UUID, ownerId: UUID?): ActiveSiteResult

    fun enterPlayer(player: ServerPlayer, handle: ActiveSiteHandle): EnterRunResult

    fun returnPlayer(player: ServerPlayer): ReturnRunResult

    fun destroyRun(server: MinecraftServer, handle: ActiveSiteHandle, reason: String)

    fun tick(server: MinecraftServer)

    fun clearPlayer(playerId: UUID)

    fun isPlayerInRun(player: ServerPlayer, handle: ActiveSiteHandle): Boolean

    fun describeProgress(server: MinecraftServer, handle: PreparedSiteHandle): String

    fun findActiveHandle(server: MinecraftServer, siteId: UUID): ActiveSiteHandle?
}

data class PreparedSiteHandle(
    val siteId: UUID,
    val templateId: String,
    val backendLevelKey: ResourceKey<Level>,
    val siteCenter: BlockPos,
    val siteBounds: SiteBounds
)

data class ActiveSiteHandle(
    val siteId: UUID,
    val runId: UUID,
    val templateId: String,
    val backendLevelKey: ResourceKey<Level>,
    val siteCenter: BlockPos,
    val siteBounds: SiteBounds
)

data class SiteBounds(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    fun contains(pos: BlockPos): Boolean {
        return pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ
    }

    fun contains(chunk: ChunkPos): Boolean {
        return chunk.maxBlockX >= minX && chunk.minBlockX <= maxX && chunk.maxBlockZ >= minZ && chunk.minBlockZ <= maxZ
    }
}

sealed interface PreparedSiteResult {
    data class Accepted(val handle: PreparedSiteHandle) : PreparedSiteResult
    data class Rejected(val reason: String) : PreparedSiteResult
}

sealed interface PreparedSiteStatus {
    data class Preparing(val detail: String) : PreparedSiteStatus
    data class Ready(val spawnPos: BlockPos) : PreparedSiteStatus
    data class Failed(val reason: String) : PreparedSiteStatus
}

sealed interface ActiveSiteResult {
    data class Accepted(val handle: ActiveSiteHandle, val spawnPos: BlockPos) : ActiveSiteResult
    data class Rejected(val reason: String) : ActiveSiteResult
}

sealed interface EnterRunResult {
    data object Entered : EnterRunResult
    data class Rejected(val reason: String) : EnterRunResult
}

sealed interface ReturnRunResult {
    data object Returned : ReturnRunResult
    data object NotBound : ReturnRunResult
    data class Rejected(val reason: String) : ReturnRunResult
}
