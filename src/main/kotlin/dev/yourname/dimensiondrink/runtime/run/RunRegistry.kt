package dev.yourname.dimensiondrink.runtime.run

import com.mojang.logging.LogUtils
import dev.yourname.dimensiondrink.ObeliskConstants
import dev.yourname.dimensiondrink.api.RunBeginResult
import dev.yourname.dimensiondrink.api.RunHandle
import dev.yourname.dimensiondrink.api.RunService
import dev.yourname.dimensiondrink.content.ObeliskBlockEntity
import dev.yourname.dimensiondrink.data.CanonicalTargetResolver
import dev.yourname.dimensiondrink.data.ObeliskDataManager
import dev.yourname.dimensiondrink.runtime.combat.RunMonsterSpawner
import dev.yourname.dimensiondrink.runtime.backend.ActiveSiteHandle
import dev.yourname.dimensiondrink.runtime.backend.ActiveSiteResult
import dev.yourname.dimensiondrink.runtime.backend.EnterRunResult
import dev.yourname.dimensiondrink.runtime.backend.PreparedSiteHandle
import dev.yourname.dimensiondrink.runtime.backend.PreparedSiteResult
import dev.yourname.dimensiondrink.runtime.backend.PreparedSiteStatus
import dev.yourname.dimensiondrink.runtime.backend.ReturnRunResult
import dev.yourname.dimensiondrink.runtime.backend.RunBackendManager
import dev.yourname.dimensiondrink.runtime.backend.SiteBounds
import dev.yourname.dimensiondrink.runtime.reward.RewardSystem
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingFallEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import kotlin.math.exp

object RunRegistry : RunService {

    private const val RUN_SAVE_INTERVAL_TICKS = 100L
    private const val ENTRY_WARMUP_TICKS = 30L

    private val logger = LogUtils.getLogger()
    private val backend = RunBackendManager.backend
    private val runs = linkedMapOf<UUID, RunRecord>()
    private val dirtyRunIds = linkedSetOf<UUID>()
    private val fallDamageSuppressedPlayers = linkedSetOf<UUID>()
    private val pendingDeathReturns = linkedMapOf<UUID, DeathReturnTarget>()
    private val pendingEntryWarmups = linkedMapOf<UUID, EntryWarmup>()
    private var shuttingDown = false

    private sealed interface RunEntryAttempt {
        data object Entered : RunEntryAttempt
        data class Waiting(val message: String) : RunEntryAttempt
        data class Rejected(val message: String) : RunEntryAttempt
    }

    private data class DeathReturnTarget(
        val levelKey: ResourceKey<Level>,
        val pos: BlockPos
    )

    private data class EntryWarmup(
        val runId: UUID,
        val startedGameTime: Long,
        val bloodCost: Double,
        val message: String
    )

    override fun getRun(playerId: UUID): RunHandle? {
        return runs.values.firstOrNull { playerId in it.activePlayers || playerId in it.pendingPlayers }?.toHandle()
    }

    override fun getRunById(runId: UUID): RunHandle? = runs[runId]?.toHandle()

    fun get(runId: UUID): RunRecord? = runs[runId]?.deepCopy()

    fun snapshot(): List<RunRecord> = runs.values.map { it.deepCopy() }

    fun currentRuns(): Collection<RunRecord> = runs.values.toList()

    fun isPreparedInstanceReady(templateId: String): Boolean {
        val targetId = CanonicalTargetResolver.targetId(templateId)
        return ObeliskDataManager.enabledDimensionDrinks().any {
            it.id == templateId || it.instanceTemplateId == templateId || it.targetDimension == templateId ||
                it.instanceTemplateId == targetId || it.targetDimension == targetId
        }
    }

    fun describePreparedInstances(): String = "backend=${backend.javaClass.simpleName}"

    fun returnPlayer(player: ServerPlayer, disqualify: Boolean = true): Boolean {
        return when (backend.returnPlayer(player)) {
            ReturnRunResult.Returned -> {
                player.fallDistance = 0.0f
                val record = mutableRunForPlayer(player.uuid)
                if (record != null) {
                    clearEntryWarmup(player)
                    removePlayer(record, player.uuid, disqualify = disqualify)
                    persistRunNow(player.server, record)
                }
                true
            }
            ReturnRunResult.NotBound -> false
            is ReturnRunResult.Rejected -> false
        }
    }

    fun drinkReturnFont(player: ServerPlayer): String {
        applyEntryWarmupEffects(player)
        return if (returnPlayer(player)) {
            "Drinking from return font..."
        } else {
            "The return font has nowhere to send you."
        }
    }

    fun clearPlayerAssignment(server: MinecraftServer, playerId: UUID): Boolean {
        val record = mutableRunForPlayer(playerId) ?: return false
        removePlayer(record, playerId, disqualify = true)
        clearEntryWarmup(server, playerId)
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
        returnPlayers(server, record, null, disqualify = false)
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
                "You are already bound to this font run."
            } else {
                "You are already bound to another font run."
            }
        }

        val existingRun = obelisk.activeRunId?.let { runs[it] }
        if (existingRun != null && existingRun.state != RunState.FINISHED && existingRun.state != RunState.FAILED) {
            if (existingRun.pendingPlayers.add(player.uuid)) {
                markEligible(existingRun, player.uuid)
                existingRun.updatedGameTime = currentGameTime(server)
                persistRunNow(server, existingRun)
            }
            startEntryWarmup(server, existingRun, player, 0.0, "Drinking from active ${displayName(existingRun.definitionId)}...")
            return when (val entry = tryQueueEntry(server, existingRun, player)) {
                RunEntryAttempt.Entered -> "Drinking from active ${displayName(existingRun.definitionId)}..."
                is RunEntryAttempt.Waiting -> entry.message
                is RunEntryAttempt.Rejected -> entry.message
            }
        }

        val instanceTemplateId = templateIdForDefinition(obelisk.definitionId)
        val validationError = backend.validateTemplate(server, instanceTemplateId)
        if (validationError != null) {
            return "Cannot open ${displayName(obelisk.definitionId)} run: $validationError"
        }
        val startBloodMinimum = obelisk.getBloodStartCost()
        if (obelisk.bloodStored < startBloodMinimum) {
            return "The font needs ${startBloodMinimum.toInt()} mB of trip juice to open."
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
        markEligible(created, player.uuid)
        startEntryWarmup(server, created, player, 0.0, "Drinking from ${displayName(created.definitionId)}...")
        return when (val entry = tryQueueEntry(server, created, player)) {
            RunEntryAttempt.Entered -> "Drinking from ${displayName(created.definitionId)}..."
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
            removePlayer(run, player.uuid, disqualify = true)
            backend.clearPlayer(player.uuid)
            persistRunNow(player.server, run)
        }
        fallDamageSuppressedPlayers.remove(player.uuid)
        clearEntryWarmup(player)
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.START || shuttingDown) {
            return
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
    fun onLivingDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val record = mutableRunForPlayer(player.uuid) ?: return
        markRunDeath(player, record)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val target = pendingDeathReturns.remove(player.uuid) ?: return
        clearEntryWarmup(player)
        val level = player.server.getLevel(target.levelKey) ?: return
        val x = target.pos.x + 0.5
        val y = target.pos.y + 1.0
        val z = target.pos.z + 0.5
        player.fallDistance = 0.0f
        player.teleportTo(level, x, y, z, player.yRot, player.xRot)
        player.connection.resetPosition()
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
        pendingEntryWarmups.clear()
        RunSavedData.get(event.server).replaceAll(emptyList())
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        shuttingDown = false
        runs.clear()
        dirtyRunIds.clear()
        pendingEntryWarmups.clear()
    }

    fun restoreFromSavedData(server: MinecraftServer) {
        runs.clear()
        dirtyRunIds.clear()
        pendingEntryWarmups.clear()
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
                logger.warn("Font backend rejected site request definition={} template={} reason={}", definitionId, templateId, requested.reason)
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
                        logger.warn("Font backend activation rejected run={} template={} reason={}", runId, templateId, activated.reason)
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
                logger.warn("Font backend unexpectedly returned preparing template={} detail={}", templateId, status.detail)
                return null
            }
            is PreparedSiteStatus.Failed -> {
                logger.warn("Font backend site failed template={} reason={}", templateId, status.reason)
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
            "Created font run {} definition={} template={} site={} level={} center={} spawnReady={}",
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
            collapseRun(server, record, "Origin font was destroyed!")
            return
        }

        val handle = activeHandle(server, record)
        var playerCount = 0
        record.activePlayers.toList().forEach { playerId ->
            val player = server.playerList.getPlayer(playerId) ?: return@forEach
            if (handle == null || !backend.isPlayerInRun(player, handle)) {
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
        val drainAmount = (baseDrain * record.drainMultiplier).coerceAtLeast(0.01)
        val drained = obelisk.drainBlood(drainAmount)
        record.updatedGameTime = currentGameTime(server)
        if (!drained) {
            collapseRun(server, record, "The font runs out of trip juice.", "juice-depleted")
            return
        }
        markRunDirty(record.id)
    }

    private fun collapseRun(server: MinecraftServer, record: RunRecord, message: String, destroyReason: String = "collapsed") {
        if (record.state == RunState.COLLAPSING || record.state == RunState.FINISHING) {
            return
        }
        record.state = RunState.COLLAPSING
        returnPlayers(server, record, message, disqualify = true)
        finishRun(server, record.id, destroyReason)
    }

    private fun returnPlayers(server: MinecraftServer, record: RunRecord, message: String?, disqualify: Boolean) {
        val onlinePlayers = (record.activePlayers + record.pendingPlayers)
            .mapNotNull { server.playerList.getPlayer(it) }
        onlinePlayers.forEach { player ->
            val returned = returnPlayer(player, disqualify = disqualify)
            if (!returned && message != null) {
                logger.warn("Could not return player {} for font run {}", player.gameProfile.name, record.id)
            }
        }
        record.pendingPlayers.clear()
        record.activePlayers.clear()
    }

    private fun markRunDeath(player: ServerPlayer, record: RunRecord) {
        val originLevelKey = record.originLevelKey
        val originPos = record.originObeliskPos
        if (originLevelKey != null && originPos != null) {
            pendingDeathReturns[player.uuid] = DeathReturnTarget(originLevelKey, originPos)
        }
        removePlayer(record, player.uuid, disqualify = true)
        backend.clearPlayer(player.uuid)
        record.updatedGameTime = currentGameTime(player.server)
        persistRunNow(player.server, record)
        logger.info("Player {} died in dimensional font run {}; disqualified from rewards", player.gameProfile.name, record.id)
    }

    private fun tryQueueEntry(server: MinecraftServer, record: RunRecord, player: ServerPlayer): RunEntryAttempt {
        val warmup = pendingEntryWarmups[player.uuid]
        if (warmup == null || warmup.runId != record.id) {
            startEntryWarmup(server, record, player, 0.0, "Drinking from ${displayName(record.definitionId)}...")
            return RunEntryAttempt.Waiting(pendingEntryWarmups[player.uuid]?.message ?: "Drinking...")
        }
        applyEntryWarmupEffects(player)
        val elapsed = currentGameTime(server) - warmup.startedGameTime
        if (elapsed < ENTRY_WARMUP_TICKS) {
            return RunEntryAttempt.Waiting(warmup.message)
        }
        val handle = activeHandle(server, record)
        if (handle == null) {
            refundEntryCost(server, record, player)
            clearEntryWarmup(player)
            removePlayer(record, player.uuid)
            persistRunNow(server, record)
            return RunEntryAttempt.Rejected("Run is unavailable right now - backend site is missing")
        }
        if (backend.isPlayerInRun(player, handle)) {
            clearEntryWarmup(player)
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
                logger.info("Entered player {} into font run {} site {}", player.gameProfile.name, record.id, record.instanceId)
                clearEntryWarmup(player)
                record.pendingPlayers.remove(player.uuid)
                record.activePlayers.add(player.uuid)
                markEligible(record, player.uuid)
                record.state = RunState.ACTIVE
                refreshSpawnPos(server, record)
                record.updatedGameTime = currentGameTime(server)
                persistRunNow(server, record)
                RunEntryAttempt.Entered
            }
            is EnterRunResult.Rejected -> {
                logger.warn(
                    "Rejected player {} entry into font run {} site {} reason={}",
                    player.gameProfile.name,
                    record.id,
                    record.instanceId,
                    result.reason
                )
                refundEntryCost(server, record, player)
                clearEntryWarmup(player)
                removePlayer(record, player.uuid)
                persistRunNow(server, record)
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
        logger.warn("Discarding empty font run {} definition={} site={} reason={}", record.id, record.definitionId, record.instanceId, reason)
        clearOriginObelisk(server, record, startCooldown = false)
        activeHandle(server, record)?.let { backend.destroyRun(server, it, reason) }
        runs.remove(record.id)
        deleteRunNow(server, record.id)
    }

    private fun startEntryWarmup(server: MinecraftServer, record: RunRecord, player: ServerPlayer, bloodCost: Double, message: String) {
        val current = pendingEntryWarmups[player.uuid]
        if (current?.runId == record.id) {
            applyEntryWarmupEffects(player)
            return
        }
        pendingEntryWarmups[player.uuid] = EntryWarmup(
            runId = record.id,
            startedGameTime = currentGameTime(server),
            bloodCost = bloodCost,
            message = message
        )
        record.state = RunState.WARMING_UP
        applyEntryWarmupEffects(player)
    }

    private fun applyEntryWarmupEffects(player: ServerPlayer) {
        player.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 12, 3, false, false, true))
        player.addEffect(MobEffectInstance(MobEffects.DARKNESS, 12, 0, false, false, true))
        player.foodData.addExhaustion(0.16f)
    }

    private fun clearEntryWarmup(player: ServerPlayer) {
        pendingEntryWarmups.remove(player.uuid)
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN)
        player.removeEffect(MobEffects.DARKNESS)
    }

    private fun clearEntryWarmup(server: MinecraftServer, playerId: UUID) {
        pendingEntryWarmups.remove(playerId)
        server.playerList.getPlayer(playerId)?.let(::clearEntryWarmup)
    }

    private fun refundEntryCost(server: MinecraftServer, record: RunRecord, player: ServerPlayer) {
        val cost = pendingEntryWarmups[player.uuid]?.bloodCost ?: 0.0
        if (cost <= 0.0) return
        getOriginObelisk(server, record)?.restoreRunBlood(cost)
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
        val restoreAmount = (obelisk.getMaxBlood() * 0.02).coerceAtLeast(1.0)
        obelisk.restoreRunBlood(restoreAmount)
    }

    private fun recordMonsterKill(server: MinecraftServer, record: RunRecord): Boolean {
        record.monstersKilled++
        record.updatedGameTime = currentGameTime(server)
        restoreKillEnergy(server, record)
        markRunDirty(record.id)
        return true
    }

    private fun markEligible(record: RunRecord, playerId: UUID) {
        record.participants += playerId
        record.survivors += playerId
        record.disqualifiedPlayers.remove(playerId)
    }

    private fun removePlayer(record: RunRecord, playerId: UUID, disqualify: Boolean = false) {
        record.activePlayers.remove(playerId)
        record.pendingPlayers.remove(playerId)
        pendingEntryWarmups.remove(playerId)
        if (disqualify) {
            record.survivors.remove(playerId)
            record.disqualifiedPlayers += playerId
        }
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
