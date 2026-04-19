package dev.yourname.obelisks.runtime.run

import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceState
import dev.yourname.instanceddimensions.engine.travel.TravelManager
import dev.yourname.instanceddimensions.events.PlayerInstanceTravelEvent
import dev.yourname.instanceddimensions.events.RuntimeDimensionTransitionEvent
import dev.yourname.instanceddimensions.events.RuntimeInstanceEvent
import dev.yourname.obelisks.ObeliskConstants
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
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import kotlin.math.exp

object RunRegistry : RunService {

    private const val VOID_FALL_Y = -64.0
    private val logger = LogUtils.getLogger()
    private val runs = linkedMapOf<UUID, RunRecord>()

    override fun getRun(playerId: UUID): RunHandle? {
        return runs.values.firstOrNull { playerId in it.activePlayers || playerId in it.pendingPlayers }?.toHandle()
    }

    override fun getRunById(runId: UUID): RunHandle? = runs[runId]?.toHandle()

    fun get(runId: UUID): RunRecord? = runs[runId]?.deepCopy()

    fun snapshot(): List<RunRecord> = runs.values.map { it.deepCopy() }

    fun clearPlayerAssignment(server: MinecraftServer, playerId: UUID): Boolean {
        val record = mutableRunForPlayer(playerId) ?: return false
        removePlayer(record, playerId)
        sync(server)
        return true
    }

    override fun beginRun(server: MinecraftServer, obeliskId: UUID, definitionId: String): RunHandle {
        return createRun(
            server = server,
            obeliskId = obeliskId,
            definitionId = definitionId,
            originLevelKey = null,
            originObeliskPos = null,
            queuedPlayerId = null
        ).toHandle()
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
            tryQueueEntry(server, existingRun, player)
            return "Joining active ${displayName(existingRun.definitionId)} run..."
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

        val instanceTemplateId = ObeliskDataManager.getObelisk(obelisk.definitionId)?.instanceTemplateId ?: obelisk.definitionId
        if (InstanceManager.getTemplate(instanceTemplateId) == null) {
            return "Trying to initialize ${displayName(obelisk.definitionId)} run..."
        }

        val created = createRun(
            server = server,
            obeliskId = obelisk.obeliskId,
            definitionId = obelisk.definitionId,
            originLevelKey = player.serverLevel().dimension(),
            originObeliskPos = pos,
            queuedPlayerId = player.uuid
        )
        obelisk.setActiveRun(created.id)
        sync(server)
        return "Initializing ${displayName(created.definitionId)} run..."
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        restoreFromSavedData(event.server)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || runs.isEmpty()) {
            return
        }

        val server = event.server
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
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        runs.clear()
    }

    fun restoreFromSavedData(server: MinecraftServer) {
        runs.clear()
        RunSavedData.get(server).snapshot().forEach { record ->
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
    ): RunRecord {
        val runId = UUID.randomUUID()
        val instanceTemplateId = ObeliskDataManager.getObelisk(definitionId)?.instanceTemplateId ?: definitionId
        val instance = InstanceManager.createInstance(server, instanceTemplateId, ownerId = runId)
        logger.info("Created obelisk run {} definition={} template={} instance={}", runId, definitionId, instanceTemplateId, instance.id)
        val record = RunRecord(
            id = runId,
            instanceId = instance.id,
            obeliskId = obeliskId,
            definitionId = definitionId,
            instanceTemplateId = instanceTemplateId,
            originLevelKey = originLevelKey,
            originObeliskPos = originObeliskPos,
            createdGameTime = currentGameTime(server),
            updatedGameTime = currentGameTime(server)
        )
        if (queuedPlayerId != null) {
            record.pendingPlayers += queuedPlayerId
            record.state = RunState.WARMING_UP
        }
        runs[record.id] = record
        sync(server)
        return record
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

        val level = server.getLevel(instance.levelKey)
        if (level == null) {
            logger.warn("Obelisk run {} instance {} is active but level {} is not loaded", record.id, record.instanceId, instance.levelKey.location())
            return false
        }
        var dirty = false
        if (record.spawnPos == null) {
            val spawnPos = SpawnPlatformGenerator.ensurePlatform(level)
            if (spawnPos != null) {
                record.spawnPos = spawnPos
                record.state = RunState.WARMING_UP
                InstanceManager.retargetTravelWarmup(record.instanceId, level, spawnPos)
                logger.info("Prepared spawn platform for obelisk run {} at {} in {}", record.id, spawnPos, instance.levelKey.location())
                dirty = true
            } else {
                logger.warn("Obelisk run {} could not prepare spawn platform in {}", record.id, instance.levelKey.location())
            }
        }

        if (!InstanceManager.isTravelReady(record.instanceId)) {
            return dirty
        }

        if (record.spawnPos != null && record.pendingPlayers.isNotEmpty()) {
            record.pendingPlayers.toList().forEach { playerId ->
                val player = server.playerList.getPlayer(playerId) ?: return@forEach
                tryQueueEntry(server, record, player)
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

    private fun tryQueueEntry(server: MinecraftServer, record: RunRecord, player: ServerPlayer) {
        val instance = InstanceManager.getInstance(record.instanceId) ?: return
        if (instance.state != InstanceState.ACTIVE || record.spawnPos == null) {
            return
        }
        if (player.serverLevel().dimension() == instance.levelKey) {
            record.pendingPlayers.remove(player.uuid)
            record.activePlayers.add(player.uuid)
            record.state = RunState.ACTIVE
            return
        }
        runCatching { TravelManager.enterInstance(player, record.instanceId) }
            .onSuccess {
                logger.info("Queued player {} into obelisk run {} instance {}", player.gameProfile.name, record.id, record.instanceId)
            }
            .onFailure { throwable ->
                logger.warn("Failed to queue player {} into obelisk run {}", player.gameProfile.name, record.id, throwable)
                player.sendSystemMessage(Component.literal("Failed to enter obelisk run: ${throwable.message ?: throwable.javaClass.simpleName}"))
            }
        record.updatedGameTime = currentGameTime(server)
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

    private fun currentGameTime(server: MinecraftServer): Long = server.overworld().gameTime

    private fun currentGameTimeFromRecord(record: RunRecord): Long = record.updatedGameTime.coerceAtLeast(record.createdGameTime)

    private fun sync(server: MinecraftServer) {
        RunSavedData.get(server).replaceAll(runs.values)
    }
}
