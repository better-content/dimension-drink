package dev.yourname.obelisks.api

import dev.yourname.obelisks.runtime.ObeliskRuntimeService
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import java.util.UUID

object ObeliskApi {
    fun getState(server: MinecraftServer, obeliskId: UUID): ObeliskState? =
        ObeliskRuntimeService.getState(server, obeliskId)

    fun listLoaded(server: MinecraftServer): List<ObeliskState> =
        ObeliskRuntimeService.listLoaded(server)

    fun findNearest(level: ServerLevel, center: BlockPos, radiusChunks: Int = 8): ObeliskState? =
        ObeliskRuntimeService.findNearest(level, center, radiusChunks)

    fun getDefinition(definitionId: String): ObeliskDefinitionState? =
        ObeliskRuntimeService.getDefinition(definitionId)

    fun listDefinitions(): List<ObeliskDefinitionState> =
        ObeliskRuntimeService.listDefinitions()
}
