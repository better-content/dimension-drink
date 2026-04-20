package dev.yourname.instanceddimensions.engine.travel

import com.mojang.logging.LogUtils
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
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.ArrayDeque
import java.util.UUID

object TravelManager : TravelService {

    private val logger = LogUtils.getLogger()
    private val returnAnchors = linkedMapOf<UUID, PlayerReturnAnchor>()
    private val pendingTransfers = ArrayDeque<PendingPlayerTransfer>()
    private val pendingTicketReleases = ArrayDeque<PendingChunkTicketRelease>()
    private val pendingTransferWaitLogTimes = linkedMapOf<UUID, Long>()

    override fun enterInstance(player: ServerPlayer, instanceId: UUID) {
        logger.info("Player {} requested runtime instance enter instance={}", player.scoreboardName, instanceId)
        val targetHandle = InstanceManager.getInstance(instanceId)
            ?: error("Unknown runtime instance: $instanceId")
        check(targetHandle.state == InstanceState.ACTIVE) {
            "Runtime instance ${targetHandle.id} is not ready for travel: ${targetHandle.state}"
        }
        val targetLevel = player.server.getLevel(targetHandle.levelKey)
            ?: error("Runtime level not loaded for instance ${targetHandle.id}")
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
            return
        }
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

        val targetPos = resolveSpawn(targetLevel)
        if (hasLiveConnection(player) && !InstanceManager.isTravelReady(targetHandle.id)) {
            pendingTransfers.addLast(
                PendingPlayerTransfer(
                    playerId = player.uuid,
                    targetLevelKey = targetLevel.dimension(),
                    instanceId = targetHandle.id,
                    x = targetPos.x + 0.5,
                    y = targetPos.y.toDouble(),
                    z = targetPos.z + 0.5,
                    yRot = player.yRot,
                    xRot = player.xRot
                )
            )
            logger.info(
                "Queued delayed runtime enter for player {} instance={} targetLevel={} targetPos={} queueDepth={}",
                player.scoreboardName,
                targetHandle.id,
                targetLevel.dimension().location(),
                targetPos,
                pendingTransfers.size
            )
            return
        }

        logger.info(
            "Entering runtime instance immediately for player {} instance={} targetLevel={} targetPos={}",
            player.scoreboardName,
            targetHandle.id,
            targetLevel.dimension().location(),
            targetPos
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
    }

    override fun returnPlayer(player: ServerPlayer): Boolean {
        val anchor = returnAnchors.remove(player.uuid) ?: defaultAnchor(player)
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
            returnAnchors[player.uuid] = anchor
            logger.info("Player {} runtime return cancelled by event for instance={}", player.scoreboardName, sourceInstance.id)
            return false
        }
        val targetLevel = player.server.getLevel(anchor.levelKey) ?: player.server.overworld()

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
        }
        return true
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
            "Resetting travel manager returnAnchors={} pendingTransfers={} pendingTicketReleases={}",
            returnAnchors.size,
            pendingTransfers.size,
            pendingTicketReleases.size
        )
        returnAnchors.clear()
        pendingTransfers.clear()
        pendingTicketReleases.clear()
        pendingTransferWaitLogTimes.clear()
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
        targetLevel.chunkSource.addRegionTicket(TicketType.POST_TELEPORT, targetChunk, 1, player.id)
        player.teleportTo(targetLevel, x, y, z, yRot, xRot)
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
        targetLevel.chunkSource.addRegionTicket(TicketType.POST_TELEPORT, targetChunk, 1, player.id)
        currentLevel.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION)
        player.revive()
        player.absMoveTo(x, y, z, yRot, xRot)
        player.setServerLevel(targetLevel)
        targetLevel.addDuringCommandTeleport(player)
        player.gameMode.setLevel(targetLevel)
        player.connection.resetPosition()
        targetLevel.chunkSource.removeRegionTicket(TicketType.POST_TELEPORT, targetChunk, 1, player.id)
        player.setYHeadRot(yRot)
    }

    private fun hasLiveConnection(player: ServerPlayer): Boolean {
        return player.server.playerList.players.contains(player) && player.connection.connection.isConnected
    }

    private fun resolveSpawn(level: ServerLevel): BlockPos {
        val spawn = level.sharedSpawnPos
        return if (spawn == BlockPos.ZERO) BlockPos(0, 80, 0) else spawn
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

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || (pendingTransfers.isEmpty() && pendingTicketReleases.isEmpty())) {
            return
        }
        logger.info(
            "TravelManager tick pendingTransfers={} pendingTicketReleases={} gameTime={}",
            pendingTransfers.size,
            pendingTicketReleases.size,
            event.server.overworld().gameTime
        )

        val remaining = pendingTransfers.size
        repeat(remaining) {
            val transfer = pendingTransfers.removeFirst()
            val player = event.server.playerList.getPlayer(transfer.playerId)
            val targetLevel = event.server.getLevel(transfer.targetLevelKey)
            if (player == null || targetLevel == null || !hasLiveConnection(player)) {
                logger.info(
                    "Dropping pending transfer player={} targetLevel={} playerPresent={} levelPresent={} liveConnection={}",
                    transfer.playerId,
                    transfer.targetLevelKey.location(),
                    player != null,
                    targetLevel != null,
                    player?.let(::hasLiveConnection) == true
                )
                pendingTransferWaitLogTimes.remove(transfer.playerId)
                return@repeat
            }
            if (!InstanceManager.isTravelReadyForLevel(targetLevel.dimension())) {
                val now = event.server.overworld().gameTime
                val lastLoggedAt = pendingTransferWaitLogTimes[transfer.playerId]
                if (lastLoggedAt == null || now - lastLoggedAt >= 20L) {
                    logger.info(
                        "Pending transfer still waiting player={} instance={} targetLevel={} gameTime={}",
                        player.scoreboardName,
                        transfer.instanceId,
                        targetLevel.dimension().location(),
                        now
                    )
                    pendingTransferWaitLogTimes[transfer.playerId] = now
                }
                pendingTransfers.addLast(transfer)
                return@repeat
            }

            moveWithNetwork(player, targetLevel, transfer.x, transfer.y, transfer.z, transfer.yRot, transfer.xRot)
            pendingTransferWaitLogTimes.remove(transfer.playerId)
            val instance = InstanceManager.getInstance(transfer.instanceId) ?: return@repeat
            val returnAnchor = returnAnchors[player.uuid] ?: return@repeat
            logger.info("Pending transfer completed player={} instance={}", player.scoreboardName, transfer.instanceId)
            MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Entered(player, instance, returnAnchor))
        }

        while (pendingTicketReleases.isNotEmpty() && pendingTicketReleases.first().releaseGameTime <= event.server.overworld().gameTime) {
            val release = pendingTicketReleases.removeFirst()
            val level = event.server.getLevel(release.levelKey) ?: continue
            level.chunkSource.removeRegionTicket(TicketType.POST_TELEPORT, release.chunkPos, 1, release.passengerId)
            logger.info(
                "Released POST_TELEPORT ticket level={} chunk={} passengerId={} remainingReleases={}",
                release.levelKey.location(),
                release.chunkPos,
                release.passengerId,
                pendingTicketReleases.size
            )
        }
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        reset()
    }

    private data class PendingPlayerTransfer(
        val playerId: UUID,
        val targetLevelKey: net.minecraft.resources.ResourceKey<Level>,
        val instanceId: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val yRot: Float,
        val xRot: Float
    )

    private data class PendingChunkTicketRelease(
        val levelKey: net.minecraft.resources.ResourceKey<Level>,
        val chunkPos: ChunkPos,
        val passengerId: Int,
        val releaseGameTime: Long
    )
}
