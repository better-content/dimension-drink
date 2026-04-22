package dev.yourname.obelisks.runtime.run

import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.api.InstanceCreateResult
import dev.yourname.instanceddimensions.api.TravelEnterResult
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceState
import dev.yourname.instanceddimensions.engine.travel.TravelManager
import dev.yourname.instanceddimensions.events.PlayerInstanceTravelEvent
import dev.yourname.instanceddimensions.events.RuntimeDimensionTransitionEvent
import dev.yourname.instanceddimensions.events.RuntimeInstanceEvent
import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.api.RunBeginResult
import dev.yourname.obelisks.api.RunHandle
import dev.yourname.obelisks.api.RunService
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.runtime.reward.RewardSystem
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import kotlin.math.exp

object RunRegistry : RunService {

    private const val VOID_FALL_Y = -64.0
    private const val PREPARED_INSTANCE_RETRY_DELAY_TICKS = 40L
    private const val PREPARED_INSTANCE_FAILURE_RETRY_DELAY_TICKS = 20L * 15L
    private const val MAX_CONCURRENT_PREPARED_WORLDGEN = 1
    private const val PREPARED_SUMMARY_ACTIVE_INTERVAL_TICKS = 100L
    private const val PREPARED_SUMMARY_IDLE_INTERVAL_TICKS = 20L * 60L
    private val logger = LogUtils.getLogger()
    private val runs = linkedMapOf<UUID, RunRecord>()
    private val preparedInstances = linkedMapOf<String, PreparedInstanceSlot>()
    private val preparedRequestQueue = linkedSetOf<String>()
    private val preparedRetryAfter = linkedMapOf<String, Long>()
    private var preparedSummaryLastLogGameTime = Long.MIN_VALUE
    private var shuttingDown = false

    private data class PreparedInstanceSlot(
        val templateId: String,
        val instanceId: UUID,
        val requestedGameTime: Long,
        var spawnPos: BlockPos? = null,
        var readyGameTime: Long? = null
    )

    private data class PreparedWorkSnapshot(
        val templateId: String,
        val phase: String,
        val completed: Int? = null,
        val total: Int? = null,
        val detail: String? = null
    ) {
        fun describe(): String {
            val progress = if (completed == null || total == null || total <= 0) {
                null
            } else {
                val percent = ((completed.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                "${percent}% ($completed/$total)"
            }
            return listOfNotNull(templateId, phase, progress, detail).joinToString(" ")
        }
    }

    private data class AcquiredRunInstance(
        val instanceId: UUID,
        val spawnPos: BlockPos? = null,
        val source: String,
        val activationPending: Boolean = false
    )

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

    fun isPreparedInstanceReady(templateId: String): Boolean {
        return preparedInstances[templateId]?.readyGameTime != null
    }

    fun describePreparedInstances(): String {
        if (preparedInstances.isEmpty() && preparedRequestQueue.isEmpty()) {
            return "[]"
        }
        val entries = preparedInstances.values.map { slot ->
            val state = InstanceManager.getInstance(slot.instanceId)?.state ?: "<missing>"
            "template=${slot.templateId},instance=${slot.instanceId},state=$state,spawn=${slot.spawnPos},ready=${slot.readyGameTime != null}"
        } + preparedRequestQueue.map { templateId ->
            "template=$templateId,instance=<queued>,spawn=null,ready=false"
        }
        return entries.joinToString(prefix = "[", postfix = "]")
    }

    fun clearPlayerAssignment(server: MinecraftServer, playerId: UUID): Boolean {
        val record = mutableRunForPlayer(playerId) ?: return false
        removePlayer(record, playerId)
        sync(server)
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
        ) ?: return RunBeginResult.Rejected("Run is still preparing for ${displayName(definitionId)} - try again in a moment")
        return RunBeginResult.Accepted(created.toHandle())
    }

    override fun finishRun(server: MinecraftServer, runId: UUID): Boolean {
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
        InstanceManager.scheduleDestroy(server, record.instanceId)
        runs.remove(runId)
        sync(server)
        return true
    }

    internal fun recordDamage(playerId: UUID, levelKey: ResourceKey<Level>, amount: Float): Boolean {
        if (amount <= 0f) return false
        val record = mutableRunForPlayer(playerId) ?: return false
        val instance = InstanceManager.getInstance(record.instanceId) ?: return false
        if (instance.levelKey != levelKey || record.state != RunState.ACTIVE) {
            return false
        }

        record.totalDamageDealt += amount
        record.updatedGameTime = currentGameTimeFromRecord(record)
        return true
    }

    internal fun recordKill(server: MinecraftServer, playerId: UUID, levelKey: ResourceKey<Level>): Boolean {
        val record = mutableRunForPlayer(playerId) ?: return false
        val instance = InstanceManager.getInstance(record.instanceId) ?: return false
        if (instance.levelKey != levelKey || record.state != RunState.ACTIVE) {
            return false
        }

        record.monstersKilled++
        record.updatedGameTime = currentGameTime(server)
        restoreKillEnergy(server, record)
        sync(server)
        return true
    }

    fun activateObelisk(player: ServerPlayer, obelisk: ObeliskBlockEntity, pos: BlockPos): String? {
        val server = player.server
        val existingPlayerRun = getRun(player.uuid)
        if (existingPlayerRun != null) {
            return if (existingPlayerRun.obeliskId == obelisk.obeliskId) {
                "You are already assigned to this obelisk run."
            } else {
                "You are already assigned to another obelisk run."
            }
        }

        val existingRun = obelisk.activeRunId?.let { runs[it] }
        if (existingRun != null && existingRun.state != RunState.FINISHED && existingRun.state != RunState.FAILED) {
            existingRun.pendingPlayers += player.uuid
            existingRun.updatedGameTime = currentGameTime(server)
            sync(server)
            return when (val entry = tryQueueEntry(server, existingRun, player)) {
                RunEntryAttempt.Entered -> "Joining active ${displayName(existingRun.definitionId)} run..."
                is RunEntryAttempt.Waiting -> entry.message
                is RunEntryAttempt.Rejected -> entry.message
            }
        }

        if (obelisk.isOnCooldown()) {
            val seconds = (obelisk.getCooldownRemainingTicks() / ObeliskConstants.TICKS_PER_SECOND).coerceAtLeast(1L)
            return "Obelisk on cooldown (${seconds}s remaining)"
        }

        val currentFe = obelisk.getEnergyStored()
        val maxFe = obelisk.getMaxEnergyStored()
        if (currentFe < maxFe) {
            val percent = ((currentFe.toDouble() / maxFe.toDouble()) * 100.0).toInt()
            return "Obelisk not fully charged ($percent% - wait for 100%)"
        }

        val instanceTemplateId = templateIdForDefinition(obelisk.definitionId)
        val validationError = InstanceManager.validateTemplateForRuntime(server, instanceTemplateId)
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
        ) ?: return buildString {
            append("Run is still preparing for ")
            append(displayName(obelisk.definitionId))
            append(" - try again in a moment")
        }
        obelisk.setActiveRun(created.id)
        sync(server)
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
        preparedInstances.clear()
        preparedRequestQueue.clear()
        preparedRetryAfter.clear()
        preparedSummaryLastLogGameTime = Long.MIN_VALUE
        restoreFromSavedData(event.server)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        if (shuttingDown) {
            return
        }

        val server = event.server
        maintainPreparedInstances(server)
        if (runs.isEmpty()) {
            return
        }
        var dirty = false
        runs.values.toList().forEach { record ->
            dirty = tickRun(server, record) || dirty
        }
        if (dirty) {
            sync(server)
        }
    }

    @SubscribeEvent
    fun onRuntimeInstanceActivated(event: RuntimeInstanceEvent.Activated) {
        val run = runForInstance(event.instance.id) ?: return
        if (run.state == RunState.ALLOCATED) {
            run.state = RunState.WARMING_UP
        }
        run.updatedGameTime = currentGameTime(event.server)
        sync(event.server)
    }

    @SubscribeEvent
    fun onRuntimeInstanceDestroyed(event: RuntimeInstanceEvent.Destroyed) {
        preparedSlotForInstance(event.instance.id)?.let { slot ->
            logger.warn(
                "Prepared runtime instance destroyed template={} instance={} ready={} level={}",
                slot.templateId,
                slot.instanceId,
                slot.readyGameTime != null,
                event.instance.levelKey.location()
            )
            preparedInstances.remove(slot.templateId)
            preparedRetryAfter[slot.templateId] = currentGameTime(event.server) + PREPARED_INSTANCE_RETRY_DELAY_TICKS
            return
        }
        val run = runForInstance(event.instance.id) ?: return
        clearOriginObelisk(event.server, run, startCooldown = false)
        runs.remove(run.id)
        sync(event.server)
    }

    @SubscribeEvent
    fun onEntered(event: PlayerInstanceTravelEvent.Entered) {
        val run = runForInstance(event.instance.id) ?: return
        run.pendingPlayers.remove(event.player.uuid)
        run.activePlayers.add(event.player.uuid)
        run.emptyTicks = 0L
        if (run.state == RunState.WARMING_UP) {
            run.state = RunState.ACTIVE
        }
        run.updatedGameTime = currentGameTime(event.player.server)
        sync(event.player.server)
    }

    @SubscribeEvent
    fun onReturned(event: PlayerInstanceTravelEvent.Returned) {
        val run = runForInstance(event.instance.id) ?: return
        removePlayer(run, event.player.uuid)
        sync(event.player.server)
    }

    @SubscribeEvent
    fun onDimensionTransition(event: RuntimeDimensionTransitionEvent.Post) {
        val fromRun = event.fromInstance?.id?.let(::runForInstance)
        val toRun = event.toInstance?.id?.let(::runForInstance)
        var dirty = false

        if (fromRun != null && fromRun.id != toRun?.id) {
            removePlayer(fromRun, event.player.uuid)
            dirty = true
        }
        if (toRun != null) {
            toRun.pendingPlayers.remove(event.player.uuid)
            toRun.activePlayers.add(event.player.uuid)
            toRun.emptyTicks = 0L
            if (toRun.state == RunState.WARMING_UP) {
                toRun.state = RunState.ACTIVE
            }
            toRun.updatedGameTime = currentGameTime(event.player.server)
            dirty = true
        }

        if (dirty) {
            sync(event.player.server)
        }
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val run = runs.values.firstOrNull { player.uuid in it.activePlayers || player.uuid in it.pendingPlayers } ?: return
        removePlayer(run, player.uuid)
        sync(player.server)
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        shuttingDown = true
        if (runs.isEmpty() && preparedInstances.isEmpty() && preparedRequestQueue.isEmpty() && preparedRetryAfter.isEmpty()) {
            return
        }
        logger.info(
            "Server stopping; clearing run registry runs={} preparedInstances={} queuedRequests={} retryEntries={}",
            runs.size,
            preparedInstances.size,
            preparedRequestQueue.size,
            preparedRetryAfter.size
        )
        runs.values.toList().forEach { record ->
            clearOriginObelisk(event.server, record, startCooldown = false)
        }
        runs.clear()
        preparedInstances.clear()
        preparedRequestQueue.clear()
        preparedRetryAfter.clear()
        preparedSummaryLastLogGameTime = Long.MIN_VALUE
        sync(event.server)
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        shuttingDown = false
        runs.clear()
        preparedInstances.clear()
        preparedRequestQueue.clear()
        preparedRetryAfter.clear()
        preparedSummaryLastLogGameTime = Long.MIN_VALUE
    }

    fun restoreFromSavedData(server: MinecraftServer) {
        runs.clear()
        preparedInstances.clear()
        RunSavedData.get(server).snapshot().forEach { record ->
            runs[record.id] = record.deepCopy()
        }
        val desiredTemplates = ObeliskDataManager.enabledObelisks()
            .map { it.instanceTemplateId }
            .toCollection(linkedSetOf())
        val seenTemplates = linkedSetOf<String>()
        InstanceManager.records()
            .filter { it.ownerId == null && (it.state == InstanceState.PREPARING || it.state == InstanceState.PREPARED) }
            .sortedWith(compareByDescending<dev.yourname.instanceddimensions.engine.instance.InstanceRecord> { it.state == InstanceState.PREPARED }.thenByDescending { it.updatedGameTime })
            .forEach { record ->
                if (record.templateId !in desiredTemplates) {
                    InstanceManager.scheduleDestroy(server, record.id)
                    return@forEach
                }
                if (!seenTemplates.add(record.templateId)) {
                    logger.warn(
                        "Discarding duplicate recovered prepared runtime instance template={} instance={} state={}",
                        record.templateId,
                        record.id,
                        record.state
                    )
                    InstanceManager.scheduleDestroy(server, record.id)
                    return@forEach
                }
                preparedInstances[record.templateId] = PreparedInstanceSlot(
                    templateId = record.templateId,
                    instanceId = record.id,
                    requestedGameTime = record.createdGameTime,
                    spawnPos = record.preparedSpawnPos,
                    readyGameTime = if (record.state == InstanceState.PREPARED) record.updatedGameTime else null
                )
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
        val instanceTemplateId = templateIdForDefinition(definitionId)
        val acquired = consumePreparedInstance(server, instanceTemplateId, runId) ?: return null
        logger.info(
            "Created obelisk run {} definition={} template={} instance={} source={}",
            runId,
            definitionId,
            instanceTemplateId,
            acquired.instanceId,
            acquired.source
        )
        val record = RunRecord(
            id = runId,
            instanceId = acquired.instanceId,
            obeliskId = obeliskId,
            definitionId = definitionId,
            instanceTemplateId = instanceTemplateId,
            originLevelKey = originLevelKey,
            originObeliskPos = originObeliskPos,
            spawnPos = acquired.spawnPos,
            createdGameTime = currentGameTime(server),
            updatedGameTime = currentGameTime(server)
        )
        if (queuedPlayerId != null) {
            record.pendingPlayers += queuedPlayerId
        }
        if (queuedPlayerId != null || acquired.activationPending) {
            record.state = RunState.WARMING_UP
        }
        runs[record.id] = record
        sync(server)
        return record
    }

    private fun consumePreparedInstance(server: MinecraftServer, templateId: String, runId: UUID): AcquiredRunInstance? {
        val slot = preparedInstances[templateId]
        if (slot == null) {
            logger.info("Prepared runtime instance missing for template={} when creating run={}", templateId, runId)
            return null
        }

        val instance = InstanceManager.getInstance(slot.instanceId)
        if (instance == null) {
            logger.warn("Prepared runtime instance vanished before consumption template={} instance={}", templateId, slot.instanceId)
            preparedInstances.remove(templateId)
            preparedRetryAfter[templateId] = currentGameTime(server) + PREPARED_INSTANCE_RETRY_DELAY_TICKS
            ensurePreparedInstanceRequested(server, templateId, reason = "missing-before-consume")
            return null
        }
        val arrivalStatus = InstanceManager.arrivalStatus(slot.instanceId)
        if (
            slot.spawnPos == null ||
            slot.readyGameTime == null ||
            instance.state != InstanceState.PREPARED
        ) {
            logger.info(
                "Prepared runtime instance not ready for consumption template={} instance={} state={} spawn={} ready={} arrivalPhase={} arrivalFailure={}",
                templateId,
                slot.instanceId,
                instance.state,
                slot.spawnPos,
                slot.readyGameTime != null,
                arrivalStatus.phase,
                arrivalStatus.failureReason
            )
            return null
        }

        preparedInstances.remove(templateId)
        preparedRetryAfter.remove(templateId)
        val activationError = InstanceManager.activatePreparedInstance(server, slot.instanceId, runId)
        if (activationError != null) {
            logger.warn(
                "Failed to activate prepared runtime instance {} for run {} reason={}",
                slot.instanceId,
                runId,
                activationError
            )
            ensurePreparedInstanceRequested(server, templateId, reason = "owner-assign-failed")
            return null
        }
        logger.info(
            "Consumed prepared runtime instance template={} instance={} run={} spawn={} requestedAt={} readyAt={}",
            templateId,
            slot.instanceId,
            runId,
            slot.spawnPos,
            slot.requestedGameTime,
            slot.readyGameTime
        )
        ensurePreparedInstanceRequested(server, templateId, reason = "consumed")
        return AcquiredRunInstance(
            instanceId = slot.instanceId,
            spawnPos = slot.spawnPos,
            source = "prepared",
            activationPending = true
        )
    }

    private fun maintainPreparedInstances(server: MinecraftServer) {
        val desiredTemplates = ObeliskDataManager.enabledObelisks()
            .map { it.instanceTemplateId }
            .toCollection(linkedSetOf())
        preparedInstances.entries.toList().forEach { (templateId, slot) ->
            if (templateId !in desiredTemplates) {
                retirePreparedInstance(server, slot, reason = "template-no-longer-enabled")
            }
        }
        preparedRetryAfter.keys.toList().forEach { templateId ->
            if (templateId !in desiredTemplates) {
                preparedRetryAfter.remove(templateId)
            }
        }
        preparedRequestQueue.removeIf { it !in desiredTemplates }
        desiredTemplates.forEach { templateId ->
            val slot = preparedInstances[templateId]
            if (slot == null) {
                ensurePreparedInstanceRequested(server, templateId, reason = "ensure")
                return@forEach
            }
            advancePreparedInstance(server, slot)
        }
        admitQueuedPreparedInstances(server)
        maybeLogPreparedSummary(server, desiredTemplates)
    }

    private fun ensurePreparedInstanceRequested(server: MinecraftServer, templateId: String, reason: String) {
        if (preparedInstances.containsKey(templateId) || templateId in preparedRequestQueue) {
            return
        }
        val now = currentGameTime(server)
        val retryAt = preparedRetryAfter[templateId]
        if (retryAt != null && now < retryAt) {
            return
        }
        preparedRequestQueue += templateId
        logger.info(
            "Queued prepared runtime instance request template={} reason={} queueDepth={} inFlightWorldgen={} budget={}",
            templateId,
            reason,
            preparedRequestQueue.size,
            currentPreparedWorldgenCount(),
            MAX_CONCURRENT_PREPARED_WORLDGEN
        )
    }

    private fun admitQueuedPreparedInstances(server: MinecraftServer) {
        var availableBudget = MAX_CONCURRENT_PREPARED_WORLDGEN - currentPreparedWorldgenCount()
        if (availableBudget <= 0) {
            maybeLogPreparedQueueState(server, "budget-full", "queued=${preparedRequestQueue.size} inFlight=${currentPreparedWorldgenCount()}")
            return
        }
        preparedRequestQueue.toList().forEach { templateId ->
            if (availableBudget <= 0) {
                return@forEach
            }
            if (preparedInstances.containsKey(templateId)) {
                preparedRequestQueue.remove(templateId)
                return@forEach
            }
            val now = currentGameTime(server)
            val retryAt = preparedRetryAfter[templateId]
            if (retryAt != null && now < retryAt) {
                return@forEach
            }
            val validationError = InstanceManager.validateTemplateForRuntime(server, templateId)
            if (validationError != null) {
                logger.warn("Prepared runtime instance request rejected template={} error={}", templateId, validationError)
                preparedRetryAfter[templateId] = now + PREPARED_INSTANCE_RETRY_DELAY_TICKS
                return@forEach
            }

            val instance = when (val created = InstanceManager.createPreparedInstance(server, templateId)) {
                is InstanceCreateResult.Accepted -> created.instance
                is InstanceCreateResult.Rejected -> {
                    logger.warn("Prepared runtime instance creation rejected template={} error={}", templateId, created.reason)
                    preparedRetryAfter[templateId] = now + PREPARED_INSTANCE_RETRY_DELAY_TICKS
                    return@forEach
                }
            }
            if (!InstanceManager.deferDefaultArrivalPreparation(instance.id)) {
                logger.warn(
                    "Prepared runtime instance could not defer default arrival preparation template={} instance={}",
                    templateId,
                    instance.id
                )
            }
            preparedInstances[templateId] = PreparedInstanceSlot(
                templateId,
                instanceId = instance.id,
                requestedGameTime = now
            )
            preparedRequestQueue.remove(templateId)
            preparedRetryAfter.remove(templateId)
            availableBudget--
            logger.info(
                "Admitted prepared runtime instance template={} instance={} level={} queueDepth={} inFlightWorldgen={} budget={}",
                templateId,
                instance.id,
                instance.levelKey.location(),
                preparedRequestQueue.size,
                currentPreparedWorldgenCount(),
                MAX_CONCURRENT_PREPARED_WORLDGEN
            )
        }
    }

    private fun advancePreparedInstance(server: MinecraftServer, slot: PreparedInstanceSlot) {
        val instance = InstanceManager.getInstance(slot.instanceId)
        if (instance == null) {
            logger.warn("Prepared runtime instance missing template={} instance={}", slot.templateId, slot.instanceId)
            preparedInstances.remove(slot.templateId)
            preparedRetryAfter[slot.templateId] = currentGameTime(server) + PREPARED_INSTANCE_RETRY_DELAY_TICKS
            return
        }
        if (slot.readyGameTime != null) {
            return
        }
        if (slot.spawnPos == null) {
            slot.spawnPos = InstanceManager.preparedSpawnPos(slot.instanceId)
        }
        if (instance.state == InstanceState.PREPARED && !InstanceManager.isLevelLoaded(server, slot.instanceId)) {
            slot.spawnPos = slot.spawnPos ?: InstanceManager.preparedSpawnPos(slot.instanceId)
            if (slot.spawnPos == null) {
                logger.warn(
                    "Prepared runtime instance reached cold-ready state without a spawn position template={} instance={}",
                    slot.templateId,
                    slot.instanceId
                )
                retirePreparedInstance(server, slot, reason = "missing-prepared-spawn")
                return
            }
            slot.readyGameTime = currentGameTime(server)
            logger.info(
                "Prepared runtime instance ready template={} instance={} spawn={} requestedAt={} readyAt={}",
                slot.templateId,
                slot.instanceId,
                slot.spawnPos,
                slot.requestedGameTime,
                slot.readyGameTime
            )
            return
        }
        val initialArrivalStatus = InstanceManager.arrivalStatus(slot.instanceId)
        if (initialArrivalStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstanceArrivalPhase.FAILED) {
            logger.warn(
                "Prepared runtime instance arrival preparation failed template={} instance={} reason={}",
                slot.templateId,
                slot.instanceId,
                initialArrivalStatus.failureReason ?: "<unknown>"
            )
            retirePreparedInstance(server, slot, reason = "arrival-failed")
            return
        }
        if (instance.state == InstanceState.DRAINING || instance.state == InstanceState.UNLOADING || instance.state == InstanceState.CLOSING) {
            maybeLogPreparedProgress(server, slot, "cooling-down", "state=${instance.state}")
            return
        }
        if (instance.state != InstanceState.PREPARING && instance.state != InstanceState.ACTIVE) {
            maybeLogPreparedProgress(server, slot, "waiting-runtime-load", "state=${instance.state}")
            return
        }

        val level = InstanceManager.loadedLevel(server, slot.instanceId)
        if (level == null) {
            maybeLogPreparedProgress(server, slot, "waiting-level", "level=${instance.levelKey.location()}")
            return
        }

        if (slot.spawnPos == null) {
            val spawnPos = SpawnPlatformGenerator.ensurePlatform(level)
            if (spawnPos == null) {
                val bootstrapError = InstanceManager.ensurePlatformBootstrap(slot.instanceId, level, level.sharedSpawnPos)
                if (bootstrapError != null) {
                    logger.warn(
                        "Prepared runtime instance platform bootstrap rejected template={} instance={} level={} center={} reason={}",
                        slot.templateId,
                        slot.instanceId,
                        instance.levelKey.location(),
                        level.sharedSpawnPos,
                        bootstrapError
                    )
                    retirePreparedInstance(server, slot, reason = "platform-bootstrap-rejected")
                    return
                }
                val bootstrapStatus = InstanceManager.platformBootstrapStatus(slot.instanceId)
                if (bootstrapStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstancePlatformBootstrapPhase.FAILED) {
                    logger.warn(
                        "Prepared runtime instance platform bootstrap failed template={} instance={} level={} reason={}",
                        slot.templateId,
                        slot.instanceId,
                        instance.levelKey.location(),
                        bootstrapStatus.failureReason ?: "<unknown>"
                    )
                    retirePreparedInstance(server, slot, reason = "platform-bootstrap-failed")
                    return
                }
                maybeLogPreparedProgress(
                    server,
                    slot,
                    "waiting-platform",
                    "level=${instance.levelKey.location()} bootstrapPhase=${bootstrapStatus.phase}"
                )
                return
            }
            InstanceManager.clearPlatformBootstrap(slot.instanceId, level)
            slot.spawnPos = spawnPos
            val preparationError = InstanceManager.prepareArrivalRegion(slot.instanceId, level, spawnPos)
            if (preparationError != null) {
                logger.warn(
                    "Prepared runtime instance arrival preparation rejected template={} instance={} level={} spawn={} reason={}",
                    slot.templateId,
                    slot.instanceId,
                    instance.levelKey.location(),
                    spawnPos,
                    preparationError
                )
                retirePreparedInstance(server, slot, reason = "arrival-preparation-rejected")
                return
            }
            logger.info(
                "Prepared runtime instance platform ready template={} instance={} level={} spawn={}",
                slot.templateId,
                slot.instanceId,
                instance.levelKey.location(),
                spawnPos
            )
        }

        val arrivalStatus = InstanceManager.arrivalStatus(slot.instanceId)
        if (arrivalStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstanceArrivalPhase.IDLE) {
            val spawnPos = slot.spawnPos ?: return
            val preparationError = InstanceManager.prepareArrivalRegion(slot.instanceId, level, spawnPos)
            if (preparationError != null) {
                logger.warn(
                    "Prepared runtime instance arrival preparation restart rejected template={} instance={} level={} spawn={} reason={}",
                    slot.templateId,
                    slot.instanceId,
                    instance.levelKey.location(),
                    spawnPos,
                    preparationError
                )
                retirePreparedInstance(server, slot, reason = "arrival-preparation-restart-rejected")
                return
            }
            maybeLogPreparedProgress(server, slot, "arrival-restarted", "spawn=$spawnPos")
            return
        }

        if (!InstanceManager.isTravelReady(slot.instanceId)) {
            val currentArrival = InstanceManager.arrivalStatus(slot.instanceId)
            maybeLogPreparedProgress(
                server,
                slot,
                "waiting-arrival",
                "spawn=${slot.spawnPos} arrivalPhase=${currentArrival.phase} completedChunks=${currentArrival.completedChunks}/${currentArrival.totalChunks}"
            )
            return
        }

        val suspendError = InstanceManager.suspendPreparedInstance(server, slot.instanceId, slot.spawnPos ?: return)
        if (suspendError != null) {
            logger.warn(
                "Prepared runtime instance suspend rejected template={} instance={} level={} spawn={} reason={}",
                slot.templateId,
                slot.instanceId,
                instance.levelKey.location(),
                slot.spawnPos,
                suspendError
            )
            retirePreparedInstance(server, slot, reason = "suspend-rejected")
            return
        }
        maybeLogPreparedProgress(server, slot, "cooling-requested", "spawn=${slot.spawnPos} state=${instance.state}")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun maybeLogPreparedProgress(_server: MinecraftServer, _slot: PreparedInstanceSlot, _phase: String, _details: String) = Unit

    private fun retirePreparedInstance(server: MinecraftServer, slot: PreparedInstanceSlot, reason: String) {
        preparedInstances.remove(slot.templateId)
        val retryDelay = retryDelayForRetiredPreparedInstance(reason)
        if (retryDelay > 0L) {
            preparedRetryAfter[slot.templateId] = currentGameTime(server) + retryDelay
        } else {
            preparedRetryAfter.remove(slot.templateId)
        }
        logger.info(
            "Retiring prepared runtime instance template={} instance={} reason={} ready={} spawn={} retryDelayTicks={}",
            slot.templateId,
            slot.instanceId,
            reason,
            slot.readyGameTime != null,
            slot.spawnPos,
            retryDelay
        )
        InstanceManager.scheduleDestroy(server, slot.instanceId)
    }

    private fun currentPreparedWorldgenCount(): Int {
        return preparedInstances.values.count { it.readyGameTime == null }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun maybeLogPreparedQueueState(_server: MinecraftServer, _phase: String, _details: String) = Unit

    private fun maybeLogPreparedSummary(server: MinecraftServer, desiredTemplates: Set<String>) {
        val now = currentGameTime(server)
        val slotStates = preparedInstances.values.mapNotNull { slot ->
            InstanceManager.getInstance(slot.instanceId)?.state
        }
        val coldReady = preparedInstances.values.count { it.readyGameTime != null }
        val hotLoaded = preparedInstances.values.count { slot -> InstanceManager.isLevelLoaded(server, slot.instanceId) }
        val preparing = slotStates.count { it == InstanceState.PREPARING }
        val cooling = slotStates.count { it == InstanceState.DRAINING || it == InstanceState.UNLOADING || it == InstanceState.CLOSING }
        val summaryInterval = if (preparedRequestQueue.isEmpty() && preparing == 0 && cooling == 0 && hotLoaded == 0 && coldReady == desiredTemplates.size) {
            PREPARED_SUMMARY_IDLE_INTERVAL_TICKS
        } else {
            PREPARED_SUMMARY_ACTIVE_INTERVAL_TICKS
        }
        if (preparedSummaryLastLogGameTime != Long.MIN_VALUE && now - preparedSummaryLastLogGameTime < summaryInterval) {
            return
        }
        preparedSummaryLastLogGameTime = now
        val working = preparedInstances.values
            .filter { it.readyGameTime == null }
            .map { describePreparedWork(server, it) }
            .joinToString(prefix = "[", postfix = "]") { it.describe() }
            .ifEmpty { "[]" }
        val queueLeft = desiredTemplates
            .filter { it !in preparedInstances.keys }
            .joinToString(prefix = "[", postfix = "]") { templateId ->
                val retryAt = preparedRetryAfter[templateId]
                if (retryAt != null && retryAt > now) {
                    val remainingTicks = retryAt - now
                    val remaining = formatTickDuration(now = remainingTicks, since = 0L) ?: "retry"
                    "$templateId retry $remaining"
                } else {
                    templateId
                }
            }
            .ifEmpty { "[]" }
        logger.info(
            "Prepared runtime pool ready={}/{} working={} queueLeft={} preparing={} cooling={} hotLoaded={}",
            coldReady,
            desiredTemplates.size,
            working,
            queueLeft,
            preparing,
            cooling,
            hotLoaded
        )
    }

    private fun describePreparedWork(server: MinecraftServer, slot: PreparedInstanceSlot): PreparedWorkSnapshot {
        val instance = InstanceManager.getInstance(slot.instanceId)
            ?: return PreparedWorkSnapshot(slot.templateId, "missing")
        if (slot.readyGameTime != null) {
            return PreparedWorkSnapshot(slot.templateId, "ready", 1, 1)
        }

        val arrivalStatus = InstanceManager.arrivalStatus(slot.instanceId)
        if (arrivalStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstanceArrivalPhase.FAILED) {
            return PreparedWorkSnapshot(
                templateId = slot.templateId,
                phase = "failed",
                detail = summarizeFailure("arrival", arrivalStatus.failureReason)
            )
        }
        if (arrivalStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstanceArrivalPhase.PREPARING) {
            val totalChunks = arrivalStatus.totalChunks.coerceAtLeast(1)
            return PreparedWorkSnapshot(
                templateId = slot.templateId,
                phase = "arrival",
                completed = arrivalStatus.completedChunks.coerceIn(0, totalChunks),
                total = totalChunks,
                detail = formatTickDuration(now = currentGameTime(server), since = arrivalStatus.requestedGameTime)
            )
        }

        val bootstrapStatus = InstanceManager.platformBootstrapStatus(slot.instanceId)
        if (bootstrapStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstancePlatformBootstrapPhase.FAILED) {
            return PreparedWorkSnapshot(
                templateId = slot.templateId,
                phase = "failed",
                detail = summarizeFailure("platform", bootstrapStatus.failureReason)
            )
        }
        if (bootstrapStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstancePlatformBootstrapPhase.PREPARING) {
            val waited = formatTickDuration(now = currentGameTime(server), since = bootstrapStatus.requestedGameTime)
            val targetChunk = bootstrapStatus.center?.let(::ChunkPos)
            val detail = buildString {
                append("waiting")
                waited?.let {
                    append(' ')
                    append(it)
                }
                targetChunk?.let {
                    append(" @ [")
                    append(it.x)
                    append(',')
                    append(it.z)
                    append(']')
                }
            }
            return PreparedWorkSnapshot(slot.templateId, "platform", detail = detail)
        }

        if (instance.state == InstanceState.DRAINING || instance.state == InstanceState.UNLOADING || instance.state == InstanceState.CLOSING) {
            return PreparedWorkSnapshot(slot.templateId, "cooling")
        }
        if (!InstanceManager.isLevelLoaded(server, slot.instanceId)) {
            return PreparedWorkSnapshot(slot.templateId, "loading")
        }
        if (slot.spawnPos == null) {
            return PreparedWorkSnapshot(slot.templateId, "platform", detail = "pending")
        }
        return PreparedWorkSnapshot(slot.templateId, instance.state.name.lowercase())
    }

    private fun formatTickDuration(now: Long, since: Long?): String? {
        since ?: return null
        val elapsedTicks = (now - since).coerceAtLeast(0L)
        val totalSeconds = elapsedTicks / 20L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) {
            "${minutes}m${seconds}s"
        } else {
            "${seconds}s"
        }
    }

    private fun summarizeFailure(phase: String, reason: String?): String {
        val normalized = reason?.lineSequence()?.firstOrNull()?.take(72)?.ifBlank { null } ?: "<unknown>"
        return "$phase $normalized"
    }

    private fun retryDelayForRetiredPreparedInstance(reason: String): Long {
        return when {
            reason.contains("failed") || reason.contains("rejected") || reason.contains("missing") ->
                PREPARED_INSTANCE_FAILURE_RETRY_DELAY_TICKS
            else -> 0L
        }
    }

    private fun tickRun(server: MinecraftServer, record: RunRecord): Boolean {
        var dirty = false
        when (record.state) {
            RunState.ALLOCATED,
            RunState.WARMING_UP -> {
                dirty = warmUpRun(server, record) || dirty
            }

            RunState.ACTIVE -> {
                dirty = warmUpRun(server, record) || dirty
                dirty = tickActiveRun(server, record) || dirty
            }

            RunState.COLLAPSING,
            RunState.FINISHING,
            RunState.FINISHED,
            RunState.FAILED -> Unit
        }
        return dirty
    }

    private fun warmUpRun(server: MinecraftServer, record: RunRecord): Boolean {
        val instance = InstanceManager.getInstance(record.instanceId)
        if (instance == null) {
            logger.warn("Obelisk run {} is waiting for missing instance {}", record.id, record.instanceId)
            return false
        }
        if (instance.state != InstanceState.ACTIVE) {
            return false
        }

        val level = InstanceManager.loadedLevel(server, instance.levelKey)
        if (level == null) {
            logger.warn("Obelisk run {} instance {} is active but level {} is not loaded", record.id, record.instanceId, instance.levelKey.location())
            return false
        }
        var dirty = false
        if (record.spawnPos == null) {
            val spawnPos = SpawnPlatformGenerator.ensurePlatform(level)
            if (spawnPos != null) {
                InstanceManager.clearPlatformBootstrap(record.instanceId, level)
                record.spawnPos = spawnPos
                record.state = RunState.WARMING_UP
                val preparationError = InstanceManager.prepareArrivalRegion(record.instanceId, level, spawnPos)
                if (preparationError != null) {
                    logger.warn(
                        "Obelisk run {} could not prepare arrival region in {}: {}",
                        record.id,
                        instance.levelKey.location(),
                        preparationError
                    )
                    collapseRun(server, record, "Dimension failed to prepare a safe arrival region")
                    return true
                }
                logger.info("Prepared spawn platform for obelisk run {} at {} in {}", record.id, spawnPos, instance.levelKey.location())
                dirty = true
            } else {
                val bootstrapError = InstanceManager.ensurePlatformBootstrap(record.instanceId, level, level.sharedSpawnPos)
                if (bootstrapError != null) {
                    logger.warn(
                        "Obelisk run {} could not bootstrap spawn platform generation in {}: {}",
                        record.id,
                        instance.levelKey.location(),
                        bootstrapError
                    )
                    collapseRun(server, record, "Dimension failed to bootstrap a safe arrival region")
                    return true
                }
                val bootstrapStatus = InstanceManager.platformBootstrapStatus(record.instanceId)
                if (bootstrapStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstancePlatformBootstrapPhase.FAILED) {
                    collapseRun(
                        server,
                        record,
                        bootstrapStatus.failureReason ?: "Dimension failed to bootstrap a safe arrival region"
                    )
                    return true
                }
                logger.info(
                    "Obelisk run {} is waiting for spawn platform bootstrap in {} phase={}",
                    record.id,
                    instance.levelKey.location(),
                    bootstrapStatus.phase
                )
            }
        }

        val arrivalStatus = InstanceManager.arrivalStatus(record.instanceId)
        if (record.spawnPos != null && arrivalStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstanceArrivalPhase.IDLE) {
            val spawnPos = record.spawnPos ?: return dirty
            val preparationError = InstanceManager.prepareArrivalRegion(record.instanceId, level, spawnPos)
            if (preparationError != null) {
                logger.warn(
                    "Obelisk run {} could not resume arrival region preparation in {}: {}",
                    record.id,
                    instance.levelKey.location(),
                    preparationError
                )
                collapseRun(server, record, "Dimension failed to resume a safe arrival region")
                return true
            }
            logger.info("Resumed arrival preparation for obelisk run {} at {} in {}", record.id, spawnPos, instance.levelKey.location())
            dirty = true
        }

        if (arrivalStatus.phase == dev.yourname.instanceddimensions.engine.instance.InstanceArrivalPhase.FAILED) {
            collapseRun(server, record, arrivalStatus.failureReason ?: "Dimension failed to prepare a safe arrival region")
            return true
        }

        if (!InstanceManager.isTravelReady(record.instanceId)) {
            return dirty
        }

        record.pendingPlayers.toList().forEach { playerId ->
            val player = server.playerList.getPlayer(playerId) ?: return@forEach
            when (val entry = tryQueueEntry(server, record, player)) {
                RunEntryAttempt.Entered -> dirty = true
                is RunEntryAttempt.Waiting -> dirty = true
                is RunEntryAttempt.Rejected -> dirty = true
            }
        }
        return dirty
    }

    private fun displayName(definitionId: String): String {
        return ObeliskDataManager.getObelisk(definitionId)?.displayName ?: definitionId
    }

    private fun tickActiveRun(server: MinecraftServer, record: RunRecord): Boolean {
        val obelisk = getOriginObelisk(server, record)
        if (obelisk == null) {
            collapseRun(server, record, "Origin obelisk was destroyed!")
            return true
        }

        val instance = InstanceManager.getInstance(record.instanceId)
        var playerCount = 0
        record.activePlayers.toList().forEach { playerId ->
            val player = server.playerList.getPlayer(playerId) ?: return@forEach
            if (instance == null || player.serverLevel().dimension() != instance.levelKey) {
                return@forEach
            }
            if (player.y < VOID_FALL_Y) {
                if (TravelManager.returnPlayer(player)) {
                    player.sendSystemMessage(Component.literal("Fell into the void"))
                } else {
                    removePlayer(record, player.uuid)
                }
                return@forEach
            }
            playerCount++
        }

        if (playerCount <= 0) {
            record.emptyTicks++
            if (record.emptyTicks >= ObeliskConstants.RUN_EMPTY_CLEANUP_DELAY_TICKS) {
                finishRun(server, record.id)
            }
            record.updatedGameTime = currentGameTime(server)
            return true
        }

        record.emptyTicks = 0L
        record.ticksElapsed++
        if (record.ticksElapsed % ObeliskConstants.DRAIN_EXPONENTIAL_INTERVAL_TICKS.toLong() == 0L) {
            record.drainMultiplier = exp(obelisk.getModifiedDrainFactor() * record.ticksElapsed.toDouble())
        }
        val baseDrain = obelisk.getModifiedBaseDrain() + (playerCount * obelisk.getModifiedPlayerDrain())
        val drainAmount = (baseDrain * record.drainMultiplier).toInt().coerceAtLeast(1)
        val drained = obelisk.drainEnergy(drainAmount)
        record.updatedGameTime = currentGameTime(server)
        if (!drained) {
            collapseRun(server, record, "Dimension collapsed - FE depleted to 0%!")
        }
        return true
    }

    private fun collapseRun(server: MinecraftServer, record: RunRecord, message: String) {
        if (record.state == RunState.COLLAPSING || record.state == RunState.FINISHING) {
            return
        }
        record.state = RunState.COLLAPSING
        returnPlayers(server, record, message)
        finishRun(server, record.id)
    }

    private fun returnPlayers(server: MinecraftServer, record: RunRecord, message: String?) {
        val onlinePlayers = (record.activePlayers + record.pendingPlayers)
            .mapNotNull { server.playerList.getPlayer(it) }
        onlinePlayers.forEach { player ->
            if (!TravelManager.returnPlayer(player) && message != null) {
                player.sendSystemMessage(Component.literal(message))
            } else if (message != null) {
                player.sendSystemMessage(Component.literal(message))
            }
        }
        record.pendingPlayers.clear()
        record.activePlayers.clear()
    }

    private fun tryQueueEntry(server: MinecraftServer, record: RunRecord, player: ServerPlayer): RunEntryAttempt {
        val instance = InstanceManager.getInstance(record.instanceId)
            ?: return RunEntryAttempt.Rejected("Run is unavailable right now - runtime instance is missing")
        if (instance.state != InstanceState.ACTIVE || record.spawnPos == null) {
            record.pendingPlayers += player.uuid
            record.updatedGameTime = currentGameTime(server)
            sync(server)
            return RunEntryAttempt.Waiting("Run is still preparing for ${displayName(record.definitionId)} - try again in a moment")
        }
        if (player.serverLevel().dimension() == instance.levelKey) {
            record.pendingPlayers.remove(player.uuid)
            record.activePlayers.add(player.uuid)
            record.state = RunState.ACTIVE
            record.updatedGameTime = currentGameTime(server)
            sync(server)
            return RunEntryAttempt.Entered
        }
        return when (val result = TravelManager.enterInstance(player, record.instanceId)) {
            TravelEnterResult.Entered -> {
                logger.info("Entered player {} into obelisk run {} instance {}", player.gameProfile.name, record.id, record.instanceId)
                record.updatedGameTime = currentGameTime(server)
                RunEntryAttempt.Entered
            }
            is TravelEnterResult.Rejected -> {
                logger.warn(
                    "Rejected player {} entry into obelisk run {} instance {} reason={}",
                    player.gameProfile.name,
                    record.id,
                    record.instanceId,
                    result.reason
                )
                removePlayer(record, player.uuid)
                sync(server)
                player.sendSystemMessage(Component.literal("Failed to enter obelisk run: ${result.reason}"))
                RunEntryAttempt.Rejected("Run is unavailable right now - ${result.reason}")
            }
        }
    }

    private fun discardEmptyRun(server: MinecraftServer, record: RunRecord, reason: String) {
        if (record.activePlayers.isNotEmpty() || record.pendingPlayers.isNotEmpty()) {
            return
        }
        logger.warn(
            "Discarding empty obelisk run {} definition={} instance={} reason={}",
            record.id,
            record.definitionId,
            record.instanceId,
            reason
        )
        clearOriginObelisk(server, record, startCooldown = false)
        InstanceManager.scheduleDestroy(server, record.instanceId)
        runs.remove(record.id)
        sync(server)
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

    private fun removePlayer(record: RunRecord, playerId: UUID) {
        record.activePlayers.remove(playerId)
        record.pendingPlayers.remove(playerId)
        record.updatedGameTime = record.updatedGameTime.coerceAtLeast(record.createdGameTime)
    }

    private fun mutableRunForPlayer(playerId: UUID): RunRecord? {
        return runs.values.firstOrNull { playerId in it.activePlayers || playerId in it.pendingPlayers }
    }

    private fun runForInstance(instanceId: UUID): RunRecord? = runs.values.firstOrNull { it.instanceId == instanceId }

    private fun preparedSlotForInstance(instanceId: UUID): PreparedInstanceSlot? {
        return preparedInstances.values.firstOrNull { it.instanceId == instanceId }
    }

    private fun templateIdForDefinition(definitionId: String): String {
        return ObeliskDataManager.getObelisk(definitionId)?.instanceTemplateId ?: definitionId
    }

    private fun currentGameTime(server: MinecraftServer): Long = server.overworld().gameTime

    private fun currentGameTimeFromRecord(record: RunRecord): Long = record.updatedGameTime.coerceAtLeast(record.createdGameTime)

    private fun sync(server: MinecraftServer) {
        RunSavedData.get(server).replaceAll(runs.values)
    }
}
