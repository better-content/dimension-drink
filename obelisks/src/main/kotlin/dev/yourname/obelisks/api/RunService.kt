package dev.yourname.obelisks.api

import net.minecraft.server.MinecraftServer
import java.util.UUID

interface RunService {
    fun getRun(playerId: UUID): RunHandle?
    fun getRunById(runId: UUID): RunHandle?
    fun beginRun(server: MinecraftServer, obeliskId: UUID, definitionId: String): RunHandle
    fun finishRun(server: MinecraftServer, runId: UUID): Boolean
}

data class RunHandle(
    val runId: UUID,
    val instanceId: UUID,
    val obeliskId: UUID,
    val definitionId: String,
    val instanceTemplateId: String,
    val state: String
)
