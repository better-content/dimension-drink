package com.bettercontent.dimensiondrink.runtime.run

import com.bettercontent.dimensiondrink.api.RunBeginResult
import com.bettercontent.dimensiondrink.api.RunHandle
import com.bettercontent.dimensiondrink.api.RunService
import com.bettercontent.dimensiondrink.api.event.FontAggregateReturnEvent
import com.bettercontent.dimensiondrink.api.event.FontEnterEvent
import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.data.CanonicalTargetResolver
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.runtime.backend.ActiveSiteHandle
import com.bettercontent.dimensiondrink.runtime.backend.ActiveSiteResult
import com.bettercontent.dimensiondrink.runtime.backend.EnterRunResult
import com.bettercontent.dimensiondrink.runtime.backend.PreparedSiteResult
import com.bettercontent.dimensiondrink.runtime.backend.PreparedSiteStatus
import com.bettercontent.dimensiondrink.runtime.backend.ReturnRunResult
import com.bettercontent.dimensiondrink.runtime.backend.RunBackendManager
import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

/**
 * Event-driven ownership for dimensional-font teleports.
 *
 * There is deliberately no tick path here. World generation and teleportation are delegated to
 * the backend; this object only owns the small amount of state needed to return a player safely.
 */
object RunRegistry : RunService {
    private const val MAX_ACTIVE_SESSIONS = 128

    private val logger = LogUtils.getLogger()
    private val backend = RunBackendManager.backend
    private val runs = linkedMapOf<UUID, RunRecord>()
    private val playerRunIds = linkedMapOf<UUID, UUID>()
    private val returningPlayers = linkedSetOf<UUID>()
    private var totalEntries = 0L
    private var totalReturns = 0L
    private var forcedCleanups = 0L

    override fun getRun(playerId: UUID): RunHandle? = mutableRunForPlayer(playerId)?.toHandle()

    override fun getRunById(runId: UUID): RunHandle? = runs[runId]?.toHandle()

    fun get(runId: UUID): RunRecord? = runs[runId]?.deepCopy()

    fun snapshot(): List<RunRecord> = runs.values.map(RunRecord::deepCopy)

    fun currentRuns(): Collection<RunRecord> = runs.values.map(RunRecord::deepCopy)

    fun isPreparedInstanceReady(templateId: String): Boolean {
        val targetId = CanonicalTargetResolver.targetId(templateId)
        return ObeliskDataManager.allDimensionDrinks().any {
            it.id == templateId || it.instanceTemplateId == templateId || it.targetDimension == templateId ||
                it.instanceTemplateId == targetId || it.targetDimension == targetId
        }
    }

    fun describePreparedInstances(): String =
        "backend=${backend.javaClass.simpleName} sessions=${runs.size} players=${playerRunIds.size} " +
            "entries=$totalEntries returns=$totalReturns forcedCleanups=$forcedCleanups"

    fun returnPlayer(player: ServerPlayer, disqualify: Boolean = true): Boolean {
        val record = mutableRunForPlayer(player.uuid)
        val returnContext = record
            ?.takeIf { !disqualify && player.uuid in it.survivors && player.uuid !in it.disqualifiedPlayers }
            ?.let(FontEventContextResolver::resolve)

        returningPlayers += player.uuid
        val result = try {
            backend.returnPlayer(player)
        } finally {
            returningPlayers -= player.uuid
        }

        detachPlayer(record, player.uuid, disqualify)
        backend.clearPlayer(player.uuid)
        when (result) {
            ReturnRunResult.Returned -> {
                totalReturns++
                player.fallDistance = 0.0f
                if (record != null && returnContext != null) {
                    MinecraftForge.EVENT_BUS.post(
                        FontAggregateReturnEvent(
                            player = player,
                            runId = record.id,
                            definitionId = returnContext.definitionId,
                            targetDimension = returnContext.targetDimension,
                            aggregateId = returnContext.aggregateId
                        )
                    )
                }
            }
            ReturnRunResult.NotBound,
            is ReturnRunResult.Rejected -> forcedCleanups++
        }
        persistOrClose(player.server, record, "player-return")
        return result == ReturnRunResult.Returned
    }

    fun drinkReturnFont(player: ServerPlayer): String = if (returnPlayer(player)) {
        "Drinking from return font..."
    } else {
        "The return font has nowhere to send you."
    }

    fun clearPlayerAssignment(server: MinecraftServer, playerId: UUID): Boolean {
        val record = mutableRunForPlayer(playerId) ?: return false
        detachPlayer(record, playerId, disqualify = true)
        backend.clearPlayer(playerId)
        forcedCleanups++
        persistOrClose(server, record, "assignment-cleared")
        return true
    }

    override fun beginRun(server: MinecraftServer, obeliskId: UUID, definitionId: String): RunBeginResult {
        val created = createRun(server, obeliskId, definitionId, null, null, null)
            ?: return RunBeginResult.Rejected("Run is unavailable for ${displayName(definitionId)} right now")
        return RunBeginResult.Accepted(created.toHandle())
    }

    override fun finishRun(server: MinecraftServer, runId: UUID): Boolean = closeRun(server, runId, "finished")

    internal fun recordDamage(playerId: UUID, levelKey: ResourceKey<Level>, amount: Float): Boolean = false

    internal fun recordKill(server: MinecraftServer, playerId: UUID, levelKey: ResourceKey<Level>): Boolean = false

    internal fun recordMonsterDeath(server: MinecraftServer, levelKey: ResourceKey<Level>, pos: BlockPos): Boolean = false

    fun activateObelisk(player: ServerPlayer, obelisk: ObeliskBlockEntity, pos: BlockPos): String? {
        val current = mutableRunForPlayer(player.uuid)
        if (current != null) {
            return if (current.obeliskId == obelisk.obeliskId) {
                "You are already bound to this font."
            } else {
                "You are already bound to another font."
            }
        }

        val existing = obelisk.activeRunId?.let(runs::get)
        if (existing != null) {
            return enterPlayer(player.server, existing, player)
        }
        if (obelisk.activeRunId != null) {
            obelisk.setActiveRun(null)
        }

        if (runs.size >= MAX_ACTIVE_SESSIONS) {
            logger.warn("Rejecting font teleport: active session limit reached sessions={}", runs.size)
            return "Too many dimensional fonts are active right now."
        }
        val templateId = templateIdForDefinition(obelisk.definitionId)
        backend.validateTemplate(player.server, templateId)?.let {
            return "Cannot open ${displayName(obelisk.definitionId)}: $it"
        }
        if (obelisk.bloodStored < obelisk.getBloodStartCost()) {
            return "The font needs ${obelisk.getBloodStartCost().toInt()} mB of trip juice to open."
        }

        val created = createRun(
            server = player.server,
            obeliskId = obelisk.obeliskId,
            definitionId = obelisk.definitionId,
            originLevelKey = player.serverLevel().dimension(),
            originObeliskPos = pos,
            ownerId = player.uuid
        ) ?: return "Run is unavailable for ${displayName(obelisk.definitionId)} right now"
        obelisk.setActiveRun(created.id)
        val message = enterPlayer(player.server, created, player)
        if (playerRunIds[player.uuid] != created.id) {
            closeRun(player.server, created.id, "initial-entry-rejected")
        }
        return message
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        runs.clear()
        playerRunIds.clear()
        returningPlayers.clear()
        val restored = RunSavedData.get(event.server).values()
            .filter { it.state != RunState.FINISHED && it.state != RunState.FAILED }
            .sortedByDescending { it.updatedGameTime }
            .take(MAX_ACTIVE_SESSIONS)
        restored.forEach { saved ->
            val record = saved.deepCopy()
            record.pendingPlayers.clear()
            runs[record.id] = record
            record.activePlayers.forEach { playerRunIds[it] = record.id }
        }
        RunSavedData.get(event.server).replaceAll(runs.values)
        if (restored.isNotEmpty()) {
            logger.info("Restored {} dimensional font sessions with no tick tasks", restored.size)
        }
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val record = mutableRunForPlayer(player.uuid) ?: return
        val handle = activeHandle(player.server, record)
        if (handle == null || !backend.isPlayerInRun(player, handle)) {
            clearPlayerAssignment(player.server, player.uuid)
        }
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (mutableRunForPlayer(player.uuid) == null) return
        if (!returnPlayer(player)) {
            clearPlayerAssignment(player.server, player.uuid)
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onLivingDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val record = mutableRunForPlayer(player.uuid) ?: return
        detachPlayer(record, player.uuid, disqualify = true)
        backend.clearPlayer(player.uuid)
        forcedCleanups++
        persistOrClose(player.server, record, "player-death")
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.uuid in returningPlayers) return
        val record = mutableRunForPlayer(player.uuid) ?: return
        if (event.to != record.backendLevelKey) {
            detachPlayer(record, player.uuid, disqualify = true)
            backend.clearPlayer(player.uuid)
            forcedCleanups++
            persistOrClose(player.server, record, "external-dimension-change")
        }
    }

    // Kept as a source-compatible no-op for older GameTests; death cleanup is immediate now.
    fun onPlayerRespawn(@Suppress("UNUSED_PARAMETER") event: PlayerEvent.PlayerRespawnEvent) = Unit

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        runs.keys.toList().forEach { closeRun(event.server, it, "server-stopping") }
        RunSavedData.get(event.server).replaceAll(emptyList())
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        runs.clear()
        playerRunIds.clear()
        returningPlayers.clear()
    }

    fun restoreFromSavedData(server: MinecraftServer) {
        onServerStarted(ServerStartedEvent(server))
    }

    private fun createRun(
        server: MinecraftServer,
        obeliskId: UUID,
        definitionId: String,
        originLevelKey: ResourceKey<Level>?,
        originObeliskPos: BlockPos?,
        ownerId: UUID?
    ): RunRecord? {
        if (runs.size >= MAX_ACTIVE_SESSIONS) return null
        val runId = UUID.randomUUID()
        val templateId = templateIdForDefinition(definitionId)
        val prepared = when (val result = backend.requestPreparedSite(server, templateId, originLevelKey, originObeliskPos)) {
            is PreparedSiteResult.Accepted -> result.handle
            is PreparedSiteResult.Rejected -> {
                logger.warn("Font backend rejected template={} reason={}", templateId, result.reason)
                return null
            }
        }
        val ready = backend.pollPreparedSite(server, prepared) as? PreparedSiteStatus.Ready ?: return null
        val active = when (val result = backend.activateRun(server, prepared, runId, ownerId)) {
            is ActiveSiteResult.Accepted -> result
            is ActiveSiteResult.Rejected -> {
                logger.warn("Font backend activation rejected run={} reason={}", runId, result.reason)
                return null
            }
        }
        val now = currentGameTime(server)
        return RunRecord(
            id = runId,
            instanceId = prepared.siteId,
            obeliskId = obeliskId,
            definitionId = definitionId,
            instanceTemplateId = templateId,
            originLevelKey = originLevelKey,
            originObeliskPos = originObeliskPos,
            backendLevelKey = active.handle.backendLevelKey,
            backendSiteCenter = active.handle.siteCenter,
            backendSiteBounds = active.handle.siteBounds,
            spawnPos = active.spawnPos.takeIf { it != BlockPos.ZERO }
                ?: ready.spawnPos.takeIf { it != BlockPos.ZERO },
            createdGameTime = now,
            updatedGameTime = now,
            state = RunState.ALLOCATED
        ).also {
            runs[it.id] = it
            RunSavedData.get(server).upsert(it)
        }
    }

    private fun enterPlayer(server: MinecraftServer, record: RunRecord, player: ServerPlayer): String {
        val handle = activeHandle(server, record)
            ?: return "The dimensional font destination is unavailable."
        return when (val result = backend.enterPlayer(player, handle)) {
            EnterRunResult.Entered -> {
                record.activePlayers += player.uuid
                record.participants += player.uuid
                record.survivors += player.uuid
                record.disqualifiedPlayers -= player.uuid
                record.state = RunState.ACTIVE
                record.updatedGameTime = currentGameTime(server)
                playerRunIds[player.uuid] = record.id
                refreshSpawnPos(server, record)
                RunSavedData.get(server).upsert(record)
                totalEntries++
                postFontEnterEvent(player, record)
                "Drinking from ${displayName(record.definitionId)}..."
            }
            is EnterRunResult.Rejected -> {
                logger.warn("Rejected player {} entry run={} reason={}", player.gameProfile.name, record.id, result.reason)
                "The dimensional font destination is unavailable: ${result.reason}"
            }
        }
    }

    private fun closeRun(server: MinecraftServer, runId: UUID, reason: String): Boolean {
        val record = runs[runId] ?: return false
        if (record.state == RunState.FINISHING || record.state == RunState.FINISHED) return false
        record.state = RunState.FINISHING
        (record.activePlayers + record.pendingPlayers).toList().forEach { playerId ->
            val player = server.playerList.getPlayer(playerId)
            if (player != null) {
                returningPlayers += playerId
                try {
                    backend.returnPlayer(player)
                } finally {
                    returningPlayers -= playerId
                }
            }
            backend.clearPlayer(playerId)
            playerRunIds.remove(playerId)
        }
        record.activePlayers.clear()
        record.pendingPlayers.clear()
        clearOriginObelisk(server, record)
        activeHandle(server, record)?.let { backend.destroyRun(server, it, reason) }
        runs.remove(runId)
        RunSavedData.get(server).remove(runId)
        return true
    }

    private fun persistOrClose(server: MinecraftServer, record: RunRecord?, reason: String) {
        if (record == null) return
        if (record.activePlayers.isEmpty() && record.pendingPlayers.isEmpty()) {
            closeRun(server, record.id, reason)
        } else {
            record.updatedGameTime = currentGameTime(server)
            RunSavedData.get(server).upsert(record)
        }
    }

    private fun detachPlayer(record: RunRecord?, playerId: UUID, disqualify: Boolean) {
        playerRunIds.remove(playerId)
        if (record == null) return
        record.activePlayers.remove(playerId)
        record.pendingPlayers.remove(playerId)
        if (disqualify) {
            record.survivors.remove(playerId)
            record.disqualifiedPlayers += playerId
        }
    }

    private fun mutableRunForPlayer(playerId: UUID): RunRecord? {
        val runId = playerRunIds[playerId] ?: return null
        return runs[runId] ?: run {
            playerRunIds.remove(playerId)
            null
        }
    }

    private fun activeHandle(server: MinecraftServer, record: RunRecord): ActiveSiteHandle? {
        return backend.findActiveHandle(server, record.instanceId) ?: run {
            val levelKey = record.backendLevelKey ?: return null
            val center = record.backendSiteCenter ?: return null
            val bounds = record.backendSiteBounds ?: return null
            ActiveSiteHandle(record.instanceId, record.id, record.instanceTemplateId, levelKey, center, bounds)
        }
    }

    private fun refreshSpawnPos(server: MinecraftServer, record: RunRecord) {
        val status = backend.pollPreparedSite(
            server,
            com.bettercontent.dimensiondrink.runtime.backend.PreparedSiteHandle(
                record.instanceId,
                record.instanceTemplateId,
                record.backendLevelKey ?: return,
                record.backendSiteCenter ?: return,
                record.backendSiteBounds ?: return
            )
        ) as? PreparedSiteStatus.Ready ?: return
        status.spawnPos.takeIf { it != BlockPos.ZERO }?.let { record.spawnPos = it.immutable() }
    }

    private fun postFontEnterEvent(player: ServerPlayer, record: RunRecord) {
        val context = FontEventContextResolver.resolve(record) ?: return
        MinecraftForge.EVENT_BUS.post(
            FontEnterEvent(
                player = player,
                runId = record.id,
                definitionId = context.definitionId,
                targetDimension = context.targetDimension,
                aggregateId = context.aggregateId
            )
        )
    }

    private fun clearOriginObelisk(server: MinecraftServer, record: RunRecord) {
        val level = record.originLevelKey?.let(server::getLevel) ?: return
        val obelisk = record.originObeliskPos?.let(level::getBlockEntity) as? ObeliskBlockEntity ?: return
        if (obelisk.activeRunId == record.id) obelisk.setActiveRun(null)
    }

    private fun templateIdForDefinition(definitionId: String): String =
        ObeliskDataManager.getObelisk(definitionId)?.instanceTemplateId ?: definitionId

    private fun displayName(definitionId: String): String =
        ObeliskDataManager.getObelisk(definitionId)?.displayName ?: definitionId

    private fun currentGameTime(server: MinecraftServer): Long = server.overworld().gameTime
}
