package dev.yourname.instanceddimensions.engine.travel

import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.api.TravelEnterResult
import dev.yourname.instanceddimensions.api.TravelService
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceState
import dev.yourname.instanceddimensions.engine.levelsync.RuntimeLevelKeySyncManager
import dev.yourname.instanceddimensions.events.PlayerInstanceTravelEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.ArrayDeque
import java.util.Comparator
import java.util.UUID

object TravelManager : TravelService {

    private val logger = LogUtils.getLogger()
    private val runtimePlayerTicketType = TicketType.create("instanceddimensions_runtime_player", Comparator.naturalOrder<UUID>())
    private const val PRECISE_CHUNK_TICKET_DISTANCE = 1
    private val returnAnchors = linkedMapOf<UUID, PlayerReturnAnchor>()
    private val pendingTicketReleases = ArrayDeque<PendingChunkTicketRelease>()
    private val runtimePlayerChunkWindows = linkedMapOf<UUID, RuntimePlayerChunkWindow>()
    private val runtimePlayerWindowLastMissingChunks = linkedMapOf<UUID, Int>()

    override fun enterInstance(player: ServerPlayer, instanceId: UUID): TravelEnterResult {
        logger.info("Player {} requested runtime instance enter instance={}", player.scoreboardName, instanceId)
        val targetHandle = InstanceManager.getInstance(instanceId)
            ?: return TravelEnterResult.Rejected("Unknown runtime instance: $instanceId")
        if (targetHandle.state != InstanceState.ACTIVE) {
            return TravelEnterResult.Rejected("Runtime instance ${targetHandle.id} is not active: ${targetHandle.state}")
        }
        val targetLevel = InstanceManager.loadedLevel(player.server, targetHandle.levelKey)
            ?: return TravelEnterResult.Rejected("Runtime level not loaded for instance ${targetHandle.id}")
        val arrivalStatus = InstanceManager.arrivalStatus(targetHandle.id)
        if (!InstanceManager.isTravelReady(targetHandle.id)) {
            return TravelEnterResult.Rejected(
                arrivalStatus.failureReason
                    ?: "Runtime instance ${targetHandle.id} arrival region is not ready (${arrivalStatus.phase.name.lowercase()})"
            )
        }
        val targetPos = resolveSpawn(targetHandle.id, targetLevel)
        val liveTravelWindow = describeLiveTravelWindow(targetLevel, targetPos)
        logger.info(
            "Runtime travel preflight player={} instance={} level={} targetPos={} arrivalPhase={} arrivalCenter={} loadedChunks={}/{} missingSample={}",
            player.scoreboardName,
            targetHandle.id,
            targetLevel.dimension().location(),
            targetPos,
            arrivalStatus.phase,
            arrivalStatus.center ?: "<none>",
            liveTravelWindow.loadedChunkCount,
            liveTravelWindow.totalChunkCount,
            liveTravelWindow.missingChunks.take(4)
        )
        if (!liveTravelWindow.isReady) {
            val missingCount = liveTravelWindow.missingChunks.size
            logger.warn(
                "Rejecting runtime enter because the live travel window is not resident player={} instance={} level={} targetPos={} missingChunks={} sample={}",
                player.scoreboardName,
                targetHandle.id,
                targetLevel.dimension().location(),
                targetPos,
                missingCount,
                liveTravelWindow.missingChunks.take(8)
            )
            return TravelEnterResult.Rejected(
                "Runtime instance ${targetHandle.id} is not entry-safe yet (${missingCount}/${liveTravelWindow.totalChunkCount} arrival chunks still loading)"
            )
        }
        val returnAnchor = PlayerReturnAnchor(
            levelKey = player.serverLevel().dimension(),
            x = player.x,
            y = player.y,
            z = player.z,
            yRot = player.yRot,
            xRot = player.xRot
        )
        if (MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Entering(player, targetHandle, returnAnchor))) {
            logger.info("Player {} runtime enter cancelled by event for instance={}", player.scoreboardName, targetHandle.id)
            return TravelEnterResult.Rejected("Runtime enter cancelled")
        }

        val sourceSnapshot = capturePlayerSnapshot(player)
        logger.info(
            "Entering runtime instance immediately for player {} instance={} targetLevel={} targetPos={}",
            player.scoreboardName,
            targetHandle.id,
            targetLevel.dimension().location(),
            targetPos
        )
        return runCatching {
            returnAnchors[player.uuid] = returnAnchor
            logger.info(
                "Stored return anchor for player {} level={} pos=({}, {}, {}) instance={}",
                player.scoreboardName,
                returnAnchor.levelKey.location(),
                returnAnchor.x,
                returnAnchor.y,
                returnAnchor.z,
                targetHandle.id
            )
            transferPlayer(
                player = player,
                targetLevel = targetLevel,
                x = targetPos.x + 0.5,
                y = targetPos.y.toDouble(),
                z = targetPos.z + 0.5,
                yRot = player.yRot,
                xRot = player.xRot
            ) {
                logger.info("Player {} entered runtime instance {}", player.scoreboardName, targetHandle.id)
                MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Entered(player, targetHandle, returnAnchor))
            }
            TravelEnterResult.Entered
        }.getOrElse { throwable ->
            returnAnchors.remove(player.uuid)
            val rollback = attemptRollback(
                player = player,
                snapshot = sourceSnapshot,
                expectedLevel = targetLevel.dimension(),
                reason = "failed-runtime-enter"
            )
            logger.warn("Failed to enter runtime instance {} for player {}", targetHandle.id, player.scoreboardName, throwable)
            logger.warn(
                "Runtime enter failure cleanup player={} instance={} rollbackResult={}",
                player.scoreboardName,
                targetHandle.id,
                rollback
            )
            TravelEnterResult.Rejected(throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    override fun returnPlayer(player: ServerPlayer): Boolean {
        val hadStoredAnchor = returnAnchors.containsKey(player.uuid)
        val anchor = returnAnchors[player.uuid] ?: defaultAnchor(player)
        val sourceSnapshot = capturePlayerSnapshot(player)
        logger.info(
            "Player {} requested runtime return sourceLevel={} targetLevel={} targetPos=({}, {}, {})",
            player.scoreboardName,
            player.serverLevel().dimension().location(),
            anchor.levelKey.location(),
            anchor.x,
            anchor.y,
            anchor.z
        )
        val sourceInstance = InstanceManager.getInstance(player.serverLevel().dimension())
        if (sourceInstance != null && MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Returning(player, sourceInstance, anchor))) {
            logger.info("Player {} runtime return cancelled by event for instance={}", player.scoreboardName, sourceInstance.id)
            return false
        }
        val targetLevel = InstanceManager.loadedLevel(player.server, anchor.levelKey) ?: player.server.overworld()
        return runCatching {
            transferPlayer(
                player = player,
                targetLevel = targetLevel,
                x = anchor.x,
                y = anchor.y,
                z = anchor.z,
                yRot = anchor.yRot,
                xRot = anchor.xRot
            ) {
                if (sourceInstance != null) {
                    logger.info("Player {} returned from runtime instance {}", player.scoreboardName, sourceInstance.id)
                    MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Returned(player, sourceInstance, anchor))
                }
                returnAnchors.remove(player.uuid)
            }
            true
        }.getOrElse { throwable ->
            val rollback = attemptRollback(
                player = player,
                snapshot = sourceSnapshot,
                expectedLevel = targetLevel.dimension(),
                reason = "failed-runtime-return"
            )
            if (!hadStoredAnchor) {
                returnAnchors.remove(player.uuid)
            }
            logger.warn("Failed to return player {} from runtime level {}", player.scoreboardName, player.serverLevel().dimension().location(), throwable)
            logger.warn(
                "Runtime return failure cleanup player={} rollbackResult={} anchorPreserved={}",
                player.scoreboardName,
                rollback,
                hadStoredAnchor
            )
            false
        }
    }

    override fun hasReturnAnchor(playerId: UUID): Boolean = returnAnchors.containsKey(playerId)

    fun peekReturnAnchor(playerId: UUID): PlayerReturnAnchor? = returnAnchors[playerId]

    fun rememberReturnAnchor(playerId: UUID, anchor: PlayerReturnAnchor): Boolean {
        if (returnAnchors.containsKey(playerId)) {
            logger.info("Skipping rememberReturnAnchor for player {} because an anchor already exists", playerId)
            return false
        }
        returnAnchors[playerId] = anchor
        logger.info("Remembered external return anchor for player {} level={}", playerId, anchor.levelKey.location())
        return true
    }

    fun clearReturnAnchor(playerId: UUID) {
        logger.info("Clearing return anchor for player {}", playerId)
        returnAnchors.remove(playerId)
    }

    fun reset() {
        logger.info(
            "Resetting travel manager returnAnchors={} pendingTicketReleases={} runtimePlayerChunkWindows={}",
            returnAnchors.size,
            pendingTicketReleases.size,
            runtimePlayerChunkWindows.size
        )
        returnAnchors.clear()
        pendingTicketReleases.clear()
        runtimePlayerChunkWindows.clear()
        runtimePlayerWindowLastMissingChunks.clear()
    }

    private fun transferPlayer(
        player: ServerPlayer,
        targetLevel: ServerLevel,
        x: Double,
        y: Double,
        z: Double,
        yRot: Float,
        xRot: Float,
        afterMove: () -> Unit
    ) {
        logger.info(
            "Transferring player {} to level={} pos=({}, {}, {}) networkMode={}",
            player.scoreboardName,
            targetLevel.dimension().location(),
            x,
            y,
            z,
            if (hasLiveConnection(player)) "live" else "headless"
        )
        if (hasLiveConnection(player)) {
            moveWithNetwork(player, targetLevel, x, y, z, yRot, xRot)
            afterMove()
            return
        }
        moveWithoutNetwork(player, targetLevel, x, y, z, yRot, xRot)
        afterMove()
    }

    private fun moveWithNetwork(
        player: ServerPlayer,
        targetLevel: ServerLevel,
        x: Double,
        y: Double,
        z: Double,
        yRot: Float,
        xRot: Float
    ) {
        val targetChunk = ChunkPos(BlockPos.containing(x, y, z))
        logger.info(
            "moveWithNetwork player={} targetLevel={} chunk={} yRot={} xRot={}",
            player.scoreboardName,
            targetLevel.dimension().location(),
            targetChunk,
            yRot,
            xRot
        )
        RuntimeLevelKeySyncManager.ensurePlayerKnowsLevel(player, targetLevel.dimension())
        targetLevel.chunkSource.addRegionTicket(net.minecraft.server.level.TicketType.POST_TELEPORT, targetChunk, 1, player.id)
        player.teleportTo(targetLevel, x, y, z, yRot, xRot)
        syncRuntimePlayerChunkWindow(player, "moveWithNetwork")
        pendingTicketReleases.addLast(
            PendingChunkTicketRelease(
                levelKey = targetLevel.dimension(),
                chunkPos = targetChunk,
                passengerId = player.id,
                releaseGameTime = targetLevel.server.overworld().gameTime + 1L
            )
        )
        logger.info(
            "moveWithNetwork complete player={} targetLevel={} releaseQueueDepth={}",
            player.scoreboardName,
            targetLevel.dimension().location(),
            pendingTicketReleases.size
        )
    }

    private fun moveWithoutNetwork(
        player: ServerPlayer,
        targetLevel: ServerLevel,
        x: Double,
        y: Double,
        z: Double,
        yRot: Float,
        xRot: Float
    ) {
        val currentLevel = player.serverLevel()
        if (currentLevel == targetLevel) {
            logger.info("moveWithoutNetwork player={} staying within level={}", player.scoreboardName, targetLevel.dimension().location())
            player.absMoveTo(x, y, z, yRot, xRot)
            player.connection.resetPosition()
            currentLevel.chunkSource.move(player)
            player.setYHeadRot(yRot)
            syncRuntimePlayerChunkWindow(player, "moveWithoutNetwork-sameLevel")
            return
        }

        val targetChunk = ChunkPos(BlockPos.containing(x, y, z))
        logger.info(
            "moveWithoutNetwork player={} sourceLevel={} targetLevel={} targetChunk={}",
            player.scoreboardName,
            currentLevel.dimension().location(),
            targetLevel.dimension().location(),
            targetChunk
        )
        targetLevel.chunkSource.addRegionTicket(net.minecraft.server.level.TicketType.POST_TELEPORT, targetChunk, 1, player.id)
        currentLevel.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION)
        player.revive()
        player.absMoveTo(x, y, z, yRot, xRot)
        player.setServerLevel(targetLevel)
        targetLevel.addDuringCommandTeleport(player)
        player.gameMode.setLevel(targetLevel)
        player.connection.resetPosition()
        targetLevel.chunkSource.removeRegionTicket(net.minecraft.server.level.TicketType.POST_TELEPORT, targetChunk, 1, player.id)
        player.setYHeadRot(yRot)
        syncRuntimePlayerChunkWindow(player, "moveWithoutNetwork")
    }

    private fun hasLiveConnection(player: ServerPlayer): Boolean {
        return player.server.playerList.players.contains(player) && player.connection.connection.isConnected
    }

    private fun resolveSpawn(instanceId: UUID, level: ServerLevel): BlockPos {
        val spawn = InstanceManager.arrivalStatus(instanceId).center
            ?: InstanceManager.preparedSpawnPos(instanceId)
            ?: level.sharedSpawnPos
        return if (spawn == BlockPos.ZERO) BlockPos(0, 80, 0) else spawn
    }

    private fun describeLiveTravelWindow(level: ServerLevel, center: BlockPos): LiveTravelWindowStatus {
        val coveredChunks = RuntimePlayerChunkWindowProfile.coveredChunks(center)
        val missingChunks = coveredChunks.filter { chunk ->
            level.chunkSource.getChunkNow(chunk.x, chunk.z) == null
        }
        return LiveTravelWindowStatus(
            centerChunk = ChunkPos(center),
            totalChunkCount = coveredChunks.size,
            missingChunks = missingChunks
        )
    }

    private fun defaultAnchor(player: ServerPlayer): PlayerReturnAnchor {
        val overworld = player.server.overworld()
        val spawn = overworld.sharedSpawnPos
        return PlayerReturnAnchor(
            levelKey = overworld.dimension(),
            x = spawn.x + 0.5,
            y = spawn.y.toDouble(),
            z = spawn.z + 0.5,
            yRot = 0.0F,
            xRot = 0.0F
        )
    }

    private fun capturePlayerSnapshot(player: ServerPlayer): PlayerLocationSnapshot {
        return PlayerLocationSnapshot(
            levelKey = player.serverLevel().dimension(),
            x = player.x,
            y = player.y,
            z = player.z,
            yRot = player.yRot,
            xRot = player.xRot
        )
    }

    private fun attemptRollback(
        player: ServerPlayer,
        snapshot: PlayerLocationSnapshot,
        expectedLevel: net.minecraft.resources.ResourceKey<Level>,
        reason: String
    ): String {
        if (player.serverLevel().dimension() != expectedLevel) {
            return "not-needed currentLevel=${player.serverLevel().dimension().location()}"
        }

        val rollbackLevel = InstanceManager.loadedLevel(player.server, snapshot.levelKey) ?: player.server.overworld()
        return runCatching {
            logger.warn(
                "Attempting travel rollback reason={} player={} currentLevel={} rollbackLevel={} rollbackPos=({}, {}, {})",
                reason,
                player.scoreboardName,
                player.serverLevel().dimension().location(),
                rollbackLevel.dimension().location(),
                snapshot.x,
                snapshot.y,
                snapshot.z
            )
            transferPlayer(
                player = player,
                targetLevel = rollbackLevel,
                x = snapshot.x,
                y = snapshot.y,
                z = snapshot.z,
                yRot = snapshot.yRot,
                xRot = snapshot.xRot
            ) {}
            "succeeded targetLevel=${rollbackLevel.dimension().location()}"
        }.getOrElse { rollbackThrowable ->
            logger.error(
                "Travel rollback failed reason={} player={} currentLevel={} intendedRollbackLevel={}",
                reason,
                player.scoreboardName,
                player.serverLevel().dimension().location(),
                rollbackLevel.dimension().location(),
                rollbackThrowable
            )
            "failed ${rollbackThrowable.message ?: rollbackThrowable.javaClass.simpleName}"
        }
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        val hasRuntimePlayers = event.server.playerList.players.any { InstanceManager.isRuntimeLevel(it.serverLevel().dimension()) }
        if (event.phase != TickEvent.Phase.END || (pendingTicketReleases.isEmpty() && runtimePlayerChunkWindows.isEmpty() && !hasRuntimePlayers)) {
            return
        }
        logger.info(
            "TravelManager tick pendingTicketReleases={} runtimePlayerChunkWindows={} hasRuntimePlayers={} gameTime={}",
            pendingTicketReleases.size,
            runtimePlayerChunkWindows.size,
            hasRuntimePlayers,
            event.server.overworld().gameTime
        )

        while (pendingTicketReleases.isNotEmpty() && pendingTicketReleases.first().releaseGameTime <= event.server.overworld().gameTime) {
            val release = pendingTicketReleases.removeFirst()
            val level = InstanceManager.loadedLevel(event.server, release.levelKey) ?: continue
            level.chunkSource.removeRegionTicket(net.minecraft.server.level.TicketType.POST_TELEPORT, release.chunkPos, 1, release.passengerId)
            logger.info(
                "Released POST_TELEPORT ticket level={} chunk={} passengerId={} remainingReleases={}",
                release.levelKey.location(),
                release.chunkPos,
                release.passengerId,
                pendingTicketReleases.size
            )
        }

        maintainRuntimePlayerChunkWindows(event.server)
    }

    @SubscribeEvent
    fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        reset()
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        reset()
    }

    private data class PendingChunkTicketRelease(
        val levelKey: net.minecraft.resources.ResourceKey<Level>,
        val chunkPos: ChunkPos,
        val passengerId: Int,
        val releaseGameTime: Long
    )

    private data class RuntimePlayerChunkWindow(
        val playerId: UUID,
        val levelKey: net.minecraft.resources.ResourceKey<Level>,
        val centerChunk: ChunkPos,
        val coveredChunks: Set<ChunkPos>
    )

    private data class PlayerLocationSnapshot(
        val levelKey: net.minecraft.resources.ResourceKey<Level>,
        val x: Double,
        val y: Double,
        val z: Double,
        val yRot: Float,
        val xRot: Float
    )

    private data class LiveTravelWindowStatus(
        val centerChunk: ChunkPos,
        val totalChunkCount: Int,
        val missingChunks: List<ChunkPos>
    ) {
        val loadedChunkCount: Int
            get() = totalChunkCount - missingChunks.size

        val isReady: Boolean
            get() = missingChunks.isEmpty()
    }

    private fun maintainRuntimePlayerChunkWindows(server: net.minecraft.server.MinecraftServer) {
        val activeRuntimePlayers = linkedSetOf<UUID>()
        server.playerList.players.forEach { player ->
            if (!InstanceManager.isRuntimeLevel(player.serverLevel().dimension())) {
                return@forEach
            }
            activeRuntimePlayers += player.uuid
            syncRuntimePlayerChunkWindow(player, "serverTick")
            logRuntimePlayerChunkWindowReadiness(player, player.serverLevel())
        }

        val stalePlayers = runtimePlayerChunkWindows.keys.filterNot(activeRuntimePlayers::contains)
        stalePlayers.forEach { playerId ->
            releaseRuntimePlayerChunkWindow(server, playerId, "left-runtime-level")
        }
    }

    private fun syncRuntimePlayerChunkWindow(player: ServerPlayer, reason: String) {
        val server = player.server
        val currentLevel = player.serverLevel()
        if (!InstanceManager.isRuntimeLevel(currentLevel.dimension())) {
            releaseRuntimePlayerChunkWindow(server, player.uuid, "$reason-non-runtime")
            return
        }

        val nextWindow = RuntimePlayerChunkWindow(
            playerId = player.uuid,
            levelKey = currentLevel.dimension(),
            centerChunk = player.chunkPosition(),
            coveredChunks = RuntimePlayerChunkWindowProfile.coveredChunks(player.chunkPosition())
        )
        val previous = runtimePlayerChunkWindows[player.uuid]
        if (previous == nextWindow) {
            return
        }

        val removedChunks = if (previous != null && previous.levelKey == nextWindow.levelKey) {
            previous.coveredChunks - nextWindow.coveredChunks
        } else {
            previous?.coveredChunks.orEmpty()
        }
        val addedChunks = if (previous != null && previous.levelKey == nextWindow.levelKey) {
            nextWindow.coveredChunks - previous.coveredChunks
        } else {
            nextWindow.coveredChunks
        }

        addedChunks.forEach { chunk ->
            currentLevel.chunkSource.addRegionTicket(
                runtimePlayerTicketType,
                chunk,
                PRECISE_CHUNK_TICKET_DISTANCE,
                nextWindow.playerId
            )
        }
        runtimePlayerChunkWindows[player.uuid] = nextWindow
        logger.info(
            "Runtime player chunk window add/update reason={} player={} level={} center={} radius={} coveredChunks={} ticketMode=per-chunk ticketDistance={}",
            reason,
            player.scoreboardName,
            nextWindow.levelKey.location(),
            nextWindow.centerChunk,
            RuntimePlayerChunkWindowProfile.TICKET_RADIUS,
            nextWindow.coveredChunks.size,
            PRECISE_CHUNK_TICKET_DISTANCE
        )

        if (previous != null && previous != nextWindow) {
            val previousLevel = InstanceManager.loadedLevel(server, previous.levelKey)
            if (previousLevel != null) {
                removedChunks.forEach { chunk ->
                    previousLevel.chunkSource.removeRegionTicket(
                        runtimePlayerTicketType,
                        chunk,
                        PRECISE_CHUNK_TICKET_DISTANCE,
                        previous.playerId
                    )
                }
            }
            logger.info(
                "Runtime player chunk window release previous reason={} player={} level={} center={} removedChunks={}",
                reason,
                player.scoreboardName,
                previous.levelKey.location(),
                previous.centerChunk,
                removedChunks.size
            )
        }
    }

    private fun releaseRuntimePlayerChunkWindow(
        server: net.minecraft.server.MinecraftServer,
        playerId: UUID,
        reason: String
    ) {
        val previous = runtimePlayerChunkWindows.remove(playerId) ?: return
        runtimePlayerWindowLastMissingChunks.remove(playerId)
        val level = InstanceManager.loadedLevel(server, previous.levelKey)
        if (level != null) {
            previous.coveredChunks.forEach { chunk ->
                level.chunkSource.removeRegionTicket(
                    runtimePlayerTicketType,
                    chunk,
                    PRECISE_CHUNK_TICKET_DISTANCE,
                    previous.playerId
                )
            }
        }
        logger.info(
            "Runtime player chunk window released reason={} playerId={} level={} center={} coveredChunks={} levelPresent={}",
            reason,
            playerId,
            previous.levelKey.location(),
            previous.centerChunk,
            previous.coveredChunks.size,
            level != null
        )
    }

    private fun logRuntimePlayerChunkWindowReadiness(player: ServerPlayer, level: ServerLevel) {
        val centerChunk = player.chunkPosition()
        val missingChunks = RuntimePlayerChunkWindowProfile.coveredChunks(centerChunk).count { chunk ->
            level.chunkSource.getChunkNow(chunk.x, chunk.z) == null
        }
        val previousMissingChunks = runtimePlayerWindowLastMissingChunks[player.uuid]
        if (missingChunks == 0) {
            if (previousMissingChunks != null && previousMissingChunks > 0) {
                logger.info(
                    "Runtime player chunk window ready player={} level={} center={} radius={} coveredChunks={}",
                    player.scoreboardName,
                    level.dimension().location(),
                    centerChunk,
                    RuntimePlayerChunkWindowProfile.TICKET_RADIUS,
                    RuntimePlayerChunkWindowProfile.coveredChunks(centerChunk).size
                )
            }
            runtimePlayerWindowLastMissingChunks.remove(player.uuid)
            return
        }

        if (previousMissingChunks == null || previousMissingChunks != missingChunks || level.server.overworld().gameTime % 20L == 0L) {
            logger.info(
                "Runtime player chunk window waiting player={} level={} center={} radius={} missingChunks={} coveredChunks={}",
                player.scoreboardName,
                level.dimension().location(),
                centerChunk,
                RuntimePlayerChunkWindowProfile.TICKET_RADIUS,
                missingChunks,
                RuntimePlayerChunkWindowProfile.coveredChunks(centerChunk).size
            )
        }
        runtimePlayerWindowLastMissingChunks[player.uuid] = missingChunks
    }
}
