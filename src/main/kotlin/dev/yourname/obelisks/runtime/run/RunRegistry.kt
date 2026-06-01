package dev.yourname.obelisks.runtime.run

import com.mojang.logging.LogUtils
import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.api.RunBeginResult
import dev.yourname.obelisks.api.RunHandle
import dev.yourname.obelisks.api.RunService
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.CanonicalTargetResolver
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.runtime.combat.RunMonsterSpawner
import dev.yourname.obelisks.runtime.backend.ActiveSiteHandle
import dev.yourname.obelisks.runtime.backend.ActiveSiteResult
import dev.yourname.obelisks.runtime.backend.EnterRunResult
import dev.yourname.obelisks.runtime.backend.PreparedSiteHandle
import dev.yourname.obelisks.runtime.backend.PreparedSiteResult
import dev.yourname.obelisks.runtime.backend.PreparedSiteStatus
import dev.yourname.obelisks.runtime.backend.ReturnRunResult
import dev.yourname.obelisks.runtime.backend.RunBackendManager
import dev.yourname.obelisks.runtime.backend.SiteBounds
import dev.yourname.obelisks.runtime.reward.RewardSystem
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingAttackEvent
import net.minecraftforge.event.entity.living.LivingFallEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import kotlin.math.exp

object RunRegistry : RunService {

    private const val VOID_RETURN_MARGIN = 8.0
    private const val RUN_SAVE_INTERVAL_TICKS = 100L

    private val logger = LogUtils.getLogger()
    private val backend = RunBackendManager.backend
    private val runs = linkedMapOf<UUID, RunRecord>()
    private val dirtyRunIds = linkedSetOf<UUID>()
    private val fallDamageSuppressedPlayers = linkedSetOf<UUID>()
    private var shuttingDown = false

    private sealed interface RunEntryAttempt {
        data object Entered : RunEntryAttempt
        data class Waiting(val message: String) : RunEntryAttempt
        data class Rejected(val message: String) : RunEntryAttempt
    }

    override fun getRun(playerId: UUID): RunHandle? {
        return runs.values.firstOrNull { playerId in it.activePlayers || playerId in it.pendingPlayers }?.toHandle()
    }

    override fun getRunById(runId: UUID): RunHandle? = runs[runId]?.toHandle()

    fun get(runId: UUID): RunRecord? = runs[runId]?.deepCopy()

    fun snapshot(): List<RunRecord> = runs.values.map { it.deepCopy() }

    fun currentRuns(): Collection<RunRecord> = runs.values

    fun isPreparedInstanceReady(templateId: String): Boolean {
        val targetId = CanonicalTargetResolver.targetId(templateId)
        return ObeliskDataManager.enabledObelisks().any {
            it.id == templateId || it.instanceTemplateId == templateId || it.targetDimension == templateId ||
                it.instanceTemplateId == targetId || it.targetDimension == targetId
        }
    }

    fun describePreparedInstances(): String = "backend=${backend.javaClass.simpleName}"

    fun returnPlayer(player: ServerPlayer): Boolean {
        return when (backend.returnPlayer(player)) {
            ReturnRunResult.Returned -> {
                player.fallDistance = 0.0f
                val record = mutableRunForPlayer(player.uuid)
                if (record != null) {
                    removePlayer(record, player.uuid)
                    persistRunNow(player.server, record)
                }
                true
            }
            ReturnRunResult.NotBound -> false
            is ReturnRunResult.Rejected -> false
        }
    }

    fun clearPlayerAssignment(server: MinecraftServer, playerId: UUID): Boolean {
        val record = mutableRunForPlayer(playerId) ?: return false
        removePlayer(record, playerId)
        backend.clearPlayer(playerId)
        persistRunNow(server, record)
        return true
    }

    override fun beginRun(server: MinecraftServer, obeliskId: UUID, definitionId: String): RunBeginResult {
        val created = createRun(
            server = server,
            obeliskId = obeliskId,
            definitionId = definitionId,
            originLevelKey = null,
            originObeliskPos = null,
            queuedPlayerId = null
        ) ?: return RunBeginResult.Rejected("Run is unavailable for ${displayName(definitionId)} right now")
        return RunBeginResult.Accepted(created.toHandle())
    }

    override fun finishRun(server: MinecraftServer, runId: UUID): Boolean = finishRun(server, runId, "finished")

    private fun finishRun(server: MinecraftServer, runId: UUID, destroyReason: String): Boolean {
        val record = runs[runId] ?: return false
        if (record.state == RunState.FINISHED || record.state == RunState.FINISHING) {
            return false
        }

        record.state = RunState.FINISHING
        record.updatedGameTime = currentGameTime(server)
        returnPlayers(server, record, null)
        if (RewardSystem.spawnRewards(server, record)) {
            record.rewardsGranted = true
        }
        clearOriginObelisk(server, record, startCooldown = true)
        activeHandle(server, record)?.let { backend.destroyRun(server, it, destroyReason) }
        runs.remove(runId)
        deleteRunNow(server, runId)
        return true
    }

    internal fun recordDamage(playerId: UUID, levelKey: ResourceKey<Level>, amount: Float): Boolean {
        if (amount <= 0f) return false
        val record = mutableRunForPlayer(playerId) ?: return false
        if (record.backendLevelKey != levelKey || record.state != RunState.ACTIVE) {
            return false
        }

        record.totalDamageDealt += amount
        record.updatedGameTime = record.updatedGameTime.coerceAtLeast(record.createdGameTime)
        markRunDirty(record.id)
        return true
    }

    internal fun recordKill(server: MinecraftServer, playerId: UUID, levelKey: ResourceKey<Level>): Boolean {
        val record = mutableRunForPlayer(playerId) ?: return false
        if (record.backendLevelKey != levelKey || record.state != RunState.ACTIVE) {
            return false
        }

        return recordMonsterKill(server, record)
    }

    internal fun recordMonsterDeath(server: MinecraftServer, levelKey: ResourceKey<Level>, pos: BlockPos): Boolean {
        val record = runs.values.firstOrNull { candidate ->
            candidate.state == RunState.ACTIVE &&
                candidate.backendLevelKey == levelKey &&
                candidate.backendSiteBounds?.contains(pos) == true
        } ?: return false

        return recordMonsterKill(server, record)
    }

    fun activateObelisk(player: ServerPlayer, obelisk: ObeliskBlockEntity, pos: BlockPos): String? {
        val server = player.server
        val existingPlayerRun = getRun(player.uuid)
        if (existingPlayerRun != null) {
            return if (existingPlayerRun.obeliskId == obelisk.obeliskId) {
                "You are already assigned to this rift anchor run."
            } else {
                "You are already assigned to another rift anchor run."
            }
        }

        val existingRun = obelisk.activeRunId?.let { runs[it] }
        if (existingRun != null && existingRun.state != RunState.FINISHED && existingRun.state != RunState.FAILED) {
            if (existingRun.pendingPlayers.add(player.uuid)) {
                existingRun.updatedGameTime = currentGameTime(server)
                persistRunNow(server, existingRun)
            }
            return when (val entry = tryQueueEntry(server, existingRun, player)) {
                RunEntryAttempt.Entered -> "Joining active ${displayName(existingRun.definitionId)} run..."
                is RunEntryAttempt.Waiting -> entry.message
                is RunEntryAttempt.Rejected -> entry.message
            }
        }

        if (obelisk.isOnCooldown()) {
            val seconds = (obelisk.getCooldownRemainingTicks() / ObeliskConstants.TICKS_PER_SECOND).coerceAtLeast(1L)
            return "Rift anchor on cooldown (${seconds}s remaining)"
        }

        val currentFe = obelisk.getEnergyStored()
        val maxFe = obelisk.getMaxEnergyStored()
        if (currentFe < maxFe) {
            val percent = ((currentFe.toDouble() / maxFe.toDouble()) * 100.0).toInt()
            return "Rift anchor not fully charged ($percent% - wait for 100%)"
        }

        val instanceTemplateId = templateIdForDefinition(obelisk.definitionId)
        val validationError = backend.validateTemplate(server, instanceTemplateId)
        if (validationError != null) {
            return "Cannot initialize ${displayName(obelisk.definitionId)} run: $validationError"
        }

        val created = createRun(
            server = server,
            obeliskId = obelisk.obeliskId,
            definitionId = obelisk.definitionId,
            originLevelKey = player.serverLevel().dimension(),
            originObeliskPos = pos,
            queuedPlayerId = player.uuid
        ) ?: return "Run is unavailable for ${displayName(obelisk.definitionId)} right now"
        obelisk.setActiveRun(created.id)
        return when (val entry = tryQueueEntry(server, created, player)) {
            RunEntryAttempt.Entered -> "Entering ${displayName(created.definitionId)} run..."
            is RunEntryAttempt.Waiting -> entry.message
            is RunEntryAttempt.Rejected -> {
                discardEmptyRun(server, created, "initial-entry-rejected")
                entry.message
            }
        }
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        shuttingDown = false
        restoreFromSavedData(event.server)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || shuttingDown) {
            return
        }

        val server = event.server
        backend.tick(server)
        if (runs.isEmpty()) {
            flushDirtyRuns(server, force = false)
            return
        }
        runs.values.toList().forEach { record ->
            tickRun(server, record)
        }
        flushDirtyRuns(server, force = false)
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val run = runs.values.firstOrNull { player.uuid in it.activePlayers || player.uuid in it.pendingPlayers } ?: return
        if (!returnPlayer(player)) {
            removePlayer(run, player.uuid)
            backend.clearPlayer(player.uuid)
            persistRunNow(player.server, run)
        }
        fallDamageSuppressedPlayers.remove(player.uuid)
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.START || shuttingDown) {
            return
        }
        val player = event.player as? ServerPlayer ?: return
        if (player.y < voidReturnY(player)) {
            returnVoidFallenPlayer(player)
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onLivingFall(event: LivingFallEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (fallDamageSuppressedPlayers.remove(player.uuid)) {
            player.fallDistance = 0.0f
            event.isCanceled = true
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onLivingAttack(event: LivingAttackEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!event.source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
            return
        }
        if (returnVoidFallenPlayer(player)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onLivingHurt(event: LivingHurtEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!event.source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
            return
        }
        if (returnVoidFallenPlayer(player)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onLivingDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!event.source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
            return
        }
        if (returnVoidFallenPlayer(player)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        shuttingDown = true
        if (runs.isEmpty()) {
            return
        }
        logger.info("Server stopping; clearing run registry runs={}", runs.size)
        runs.values.toList().forEach { record ->
            clearOriginObelisk(event.server, record, startCooldown = false)
        }
        runs.clear()
        dirtyRunIds.clear()
        RunSavedData.get(event.server).replaceAll(emptyList())
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        shuttingDown = false
        runs.clear()
        dirtyRunIds.clear()
    }

    fun restoreFromSavedData(server: MinecraftServer) {
        runs.clear()
        dirtyRunIds.clear()
        RunSavedData.get(server).values().forEach { record ->
            runs[record.id] = record.deepCopy()
        }
    }

    private fun createRun(
        server: MinecraftServer,
        obeliskId: UUID,
        definitionId: String,
        originLevelKey: ResourceKey<Level>?,
        originObeliskPos: BlockPos?,
        queuedPlayerId: UUID?
    ): RunRecord? {
        val runId = UUID.randomUUID()
        val templateId = templateIdForDefinition(definitionId)
        val prepared = when (val requested = backend.requestPreparedSite(server, templateId, originLevelKey, originObeliskPos)) {
            is PreparedSiteResult.Accepted -> requested.handle
            is PreparedSiteResult.Rejected -> {
                logger.warn("Obelisk backend rejected site request definition={} template={} reason={}", definitionId, templateId, requested.reason)
                return null
            }
        }

        var spawnPos: BlockPos? = null
        var backendLevelKey: ResourceKey<Level>? = prepared.backendLevelKey
        var backendCenter: BlockPos? = prepared.siteCenter
        var backendBounds: SiteBounds? = prepared.siteBounds
        var initialState = RunState.ALLOCATED

        when (val status = backend.pollPreparedSite(server, prepared)) {
            is PreparedSiteStatus.Ready -> {
                val active = when (val activated = backend.activateRun(server, prepared, runId, queuedPlayerId)) {
                    is ActiveSiteResult.Accepted -> activated
                    is ActiveSiteResult.Rejected -> {
                        logger.warn("Obelisk backend activation rejected run={} template={} reason={}", runId, templateId, activated.reason)
                        return null
                    }
                }
                backendLevelKey = active.handle.backendLevelKey
                backendCenter = active.handle.siteCenter
                backendBounds = active.handle.siteBounds
                spawnPos = active.spawnPos.takeIf { it != BlockPos.ZERO } ?: status.spawnPos.takeIf { it != BlockPos.ZERO }
                initialState = RunState.ALLOCATED
            }
            is PreparedSiteStatus.Preparing -> {
                logger.warn("Obelisk backend unexpectedly returned preparing template={} detail={}", templateId, status.detail)
                return null
            }
            is PreparedSiteStatus.Failed -> {
                logger.warn("Obelisk backend site failed template={} reason={}", templateId, status.reason)
                return null
            }
        }

        val record = RunRecord(
            id = runId,
            instanceId = prepared.siteId,
            obeliskId = obeliskId,
            definitionId = definitionId,
            instanceTemplateId = templateId,
            originLevelKey = originLevelKey,
            originObeliskPos = originObeliskPos,
            backendLevelKey = backendLevelKey,
            backendSiteCenter = backendCenter,
            backendSiteBounds = backendBounds,
            spawnPos = spawnPos,
            createdGameTime = currentGameTime(server),
            updatedGameTime = currentGameTime(server),
            state = initialState
        )
        if (queuedPlayerId != null) {
            record.pendingPlayers += queuedPlayerId
        }
        runs[record.id] = record
        persistRunNow(server, record)
        logger.info(
            "Created obelisk run {} definition={} template={} site={} level={} center={} spawnReady={}",
            runId,
            definitionId,
            templateId,
            prepared.siteId,
            prepared.backendLevelKey.location(),
            prepared.siteCenter,
            spawnPos != null
        )
        return record
    }

    private fun tickRun(server: MinecraftServer, record: RunRecord) {
        when (record.state) {
            RunState.ALLOCATED,
            RunState.WARMING_UP -> warmUpRun(server, record)
            RunState.ACTIVE -> {
                warmUpRun(server, record)
                tickActiveRun(server, record)
            }
            RunState.COLLAPSING,
            RunState.FINISHING,
            RunState.FINISHED,
            RunState.FAILED -> Unit
        }
    }

    private fun warmUpRun(server: MinecraftServer, record: RunRecord) {
        record.pendingPlayers.toList().forEach { playerId ->
            val player = server.playerList.getPlayer(playerId) ?: return@forEach
            tryQueueEntry(server, record, player)
        }
    }

    private fun tickActiveRun(server: MinecraftServer, record: RunRecord) {
        val obelisk = getOriginObelisk(server, record)
        if (obelisk == null) {
            collapseRun(server, record, "Origin rift anchor was destroyed!")
            return
        }

        val handle = activeHandle(server, record)
        var playerCount = 0
        record.activePlayers.toList().forEach { playerId ->
            val player = server.playerList.getPlayer(playerId) ?: return@forEach
            if (handle == null || !backend.isPlayerInRun(player, handle)) {
                return@forEach
            }
            if (player.y < voidReturnY(player)) {
                if (!returnVoidFallenPlayer(player)) {
                    removePlayer(record, player.uuid)
                    persistRunNow(server, record)
                }
                return@forEach
            }
            playerCount++
        }

        if (playerCount <= 0) {
            record.emptyTicks++
            if (record.emptyTicks >= ObeliskConstants.RUN_EMPTY_CLEANUP_DELAY_TICKS) {
                finishRun(server, record.id)
                return
            }
            record.updatedGameTime = currentGameTime(server)
            markRunDirty(record.id)
            return
        }

        record.emptyTicks = 0L
        RunMonsterSpawner.tick(server, record, playerCount)
        record.ticksElapsed++
        if (record.ticksElapsed % ObeliskConstants.DRAIN_EXPONENTIAL_INTERVAL_TICKS.toLong() == 0L) {
            record.drainMultiplier = exp(obelisk.getModifiedDrainFactor() * record.ticksElapsed.toDouble())
        }
        val baseDrain = obelisk.getModifiedBaseDrain() + (playerCount * obelisk.getModifiedPlayerDrain())
        val drainAmount = (baseDrain * record.drainMultiplier).toInt().coerceAtLeast(1)
        val drained = obelisk.drainEnergy(drainAmount)
        record.updatedGameTime = currentGameTime(server)
        if (!drained) {
            collapseRun(server, record, "Dimension collapsed - FE depleted to 0%!", "power-depleted")
            return
        }
        markRunDirty(record.id)
    }

    private fun collapseRun(server: MinecraftServer, record: RunRecord, message: String, destroyReason: String = "collapsed") {
        if (record.state == RunState.COLLAPSING || record.state == RunState.FINISHING) {
            return
        }
        record.state = RunState.COLLAPSING
        returnPlayers(server, record, message)
        finishRun(server, record.id, destroyReason)
    }

    private fun returnPlayers(server: MinecraftServer, record: RunRecord, message: String?) {
        val onlinePlayers = (record.activePlayers + record.pendingPlayers)
            .mapNotNull { server.playerList.getPlayer(it) }
        onlinePlayers.forEach { player ->
            val returned = returnPlayer(player)
            if (message != null) {
                player.sendSystemMessage(Component.literal(message))
            }
            if (!returned && message != null) {
                logger.warn("Could not return player {} for obelisk run {}", player.gameProfile.name, record.id)
            }
        }
        record.pendingPlayers.clear()
        record.activePlayers.clear()
    }

    private fun returnVoidFallenPlayer(player: ServerPlayer): Boolean {
        val record = mutableRunForPlayer(player.uuid) ?: return false
        val levelKey = record.backendLevelKey ?: return false
        if (player.serverLevel().dimension() != levelKey) {
            return false
        }
        player.fallDistance = 0.0f
        return if (returnPlayer(player)) {
            player.fallDistance = 0.0f
            fallDamageSuppressedPlayers += player.uuid
            player.sendSystemMessage(Component.literal("Fell into the void"))
            true
        } else {
            false
        }
    }

    private fun voidReturnY(player: ServerPlayer): Double {
        return player.serverLevel().minBuildHeight.toDouble() - VOID_RETURN_MARGIN
    }

    private fun tryQueueEntry(server: MinecraftServer, record: RunRecord, player: ServerPlayer): RunEntryAttempt {
        val handle = activeHandle(server, record)
            ?: return RunEntryAttempt.Rejected("Run is unavailable right now - backend site is missing")
        if (backend.isPlayerInRun(player, handle)) {
            record.pendingPlayers.remove(player.uuid)
            record.activePlayers.add(player.uuid)
            record.state = RunState.ACTIVE
            refreshSpawnPos(server, record)
            record.updatedGameTime = currentGameTime(server)
            persistRunNow(server, record)
            return RunEntryAttempt.Entered
        }
        return when (val result = backend.enterPlayer(player, handle)) {
            EnterRunResult.Entered -> {
                logger.info("Entered player {} into obelisk run {} site {}", player.gameProfile.name, record.id, record.instanceId)
                record.pendingPlayers.remove(player.uuid)
                record.activePlayers.add(player.uuid)
                record.state = RunState.ACTIVE
                refreshSpawnPos(server, record)
                record.updatedGameTime = currentGameTime(server)
                persistRunNow(server, record)
                RunEntryAttempt.Entered
            }
            is EnterRunResult.Rejected -> {
                logger.warn(
                    "Rejected player {} entry into obelisk run {} site {} reason={}",
                    player.gameProfile.name,
                    record.id,
                    record.instanceId,
                    result.reason
                )
                removePlayer(record, player.uuid)
                persistRunNow(server, record)
                player.sendSystemMessage(Component.literal("Failed to enter rift anchor run: ${result.reason}"))
                RunEntryAttempt.Rejected("Run is unavailable right now - ${result.reason}")
            }
        }
    }

    private fun refreshSpawnPos(server: MinecraftServer, record: RunRecord) {
        val prepared = preparedHandleFromRecord(record) ?: return
        val status = backend.pollPreparedSite(server, prepared) as? PreparedSiteStatus.Ready ?: return
        val spawn = status.spawnPos.takeIf { it != BlockPos.ZERO } ?: return
        if (record.spawnPos != spawn) {
            record.spawnPos = spawn.immutable()
            markRunDirty(record.id)
        }
    }

    private fun discardEmptyRun(server: MinecraftServer, record: RunRecord, reason: String) {
        if (record.activePlayers.isNotEmpty() || record.pendingPlayers.isNotEmpty()) {
            return
        }
        logger.warn("Discarding empty obelisk run {} definition={} site={} reason={}", record.id, record.definitionId, record.instanceId, reason)
        clearOriginObelisk(server, record, startCooldown = false)
        activeHandle(server, record)?.let { backend.destroyRun(server, it, reason) }
        runs.remove(record.id)
        deleteRunNow(server, record.id)
    }

    private fun preparedHandleFromRecord(record: RunRecord): PreparedSiteHandle? {
        val levelKey = record.backendLevelKey ?: return null
        val center = record.backendSiteCenter ?: return null
        val bounds = record.backendSiteBounds ?: return null
        return PreparedSiteHandle(record.instanceId, record.instanceTemplateId, levelKey, center, bounds)
    }

    private fun activeHandle(server: MinecraftServer, record: RunRecord): ActiveSiteHandle? {
        return backend.findActiveHandle(server, record.instanceId) ?: activeHandleFromRecord(record)
    }

    private fun activeHandleFromRecord(record: RunRecord): ActiveSiteHandle? {
        val levelKey = record.backendLevelKey ?: return null
        val center = record.backendSiteCenter ?: return null
        val bounds: SiteBounds = record.backendSiteBounds ?: return null
        return ActiveSiteHandle(record.instanceId, record.id, record.instanceTemplateId, levelKey, center, bounds)
    }

    private fun clearOriginObelisk(server: MinecraftServer, record: RunRecord, startCooldown: Boolean) {
        val obelisk = getOriginObelisk(server, record) ?: return
        if (obelisk.activeRunId == record.id) {
            obelisk.setActiveRun(null)
        }
        if (startCooldown) {
            obelisk.startCooldown()
        }
    }

    private fun getOriginObelisk(server: MinecraftServer, record: RunRecord): ObeliskBlockEntity? {
        val levelKey = record.originLevelKey ?: return null
        val pos = record.originObeliskPos ?: return null
        val level = server.getLevel(levelKey) ?: return null
        return level.getBlockEntity(pos) as? ObeliskBlockEntity
    }

    private fun restoreKillEnergy(server: MinecraftServer, record: RunRecord) {
        val obelisk = getOriginObelisk(server, record) ?: return
        val restoreAmount = (obelisk.getMaxEnergyStored() * 0.02).toInt().coerceAtLeast(1)
        obelisk.restoreRunEnergy(restoreAmount)
    }

    private fun recordMonsterKill(server: MinecraftServer, record: RunRecord): Boolean {
        record.monstersKilled++
        record.updatedGameTime = currentGameTime(server)
        restoreKillEnergy(server, record)
        markRunDirty(record.id)
        return true
    }

    private fun removePlayer(record: RunRecord, playerId: UUID) {
        record.activePlayers.remove(playerId)
        record.pendingPlayers.remove(playerId)
        record.updatedGameTime = record.updatedGameTime.coerceAtLeast(record.createdGameTime)
    }

    private fun mutableRunForPlayer(playerId: UUID): RunRecord? {
        return runs.values.firstOrNull { playerId in it.activePlayers || playerId in it.pendingPlayers }
    }

    private fun templateIdForDefinition(definitionId: String): String {
        return ObeliskDataManager.getObelisk(definitionId)?.instanceTemplateId ?: definitionId
    }

    private fun displayName(definitionId: String): String {
        return ObeliskDataManager.getObelisk(definitionId)?.displayName ?: definitionId
    }

    private fun currentGameTime(server: MinecraftServer): Long = server.overworld().gameTime

    private fun markRunDirty(runId: UUID) {
        dirtyRunIds += runId
    }

    private fun persistRunNow(server: MinecraftServer, record: RunRecord) {
        RunSavedData.get(server).upsert(record)
        dirtyRunIds.remove(record.id)
    }

    private fun deleteRunNow(server: MinecraftServer, runId: UUID) {
        RunSavedData.get(server).remove(runId)
        dirtyRunIds.remove(runId)
    }

    private fun flushDirtyRuns(server: MinecraftServer, force: Boolean) {
        if (dirtyRunIds.isEmpty()) {
            return
        }
        if (!force && currentGameTime(server) % RUN_SAVE_INTERVAL_TICKS != 0L) {
            return
        }
        val data = RunSavedData.get(server)
        dirtyRunIds.toList().forEach { runId ->
            val record = runs[runId]
            if (record == null) {
                data.remove(runId)
            } else {
                data.upsert(record)
            }
        }
        dirtyRunIds.clear()
    }
}
