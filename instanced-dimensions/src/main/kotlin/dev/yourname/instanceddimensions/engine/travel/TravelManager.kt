package dev.yourname.instanceddimensions.engine.travel

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

    private val returnAnchors = linkedMapOf<UUID, PlayerReturnAnchor>()
    private val pendingTransfers = ArrayDeque<PendingPlayerTransfer>()

    override fun enterInstance(player: ServerPlayer, instanceId: UUID) {
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
            return
        }
        returnAnchors[player.uuid] = returnAnchor

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
            return
        }

        transferPlayer(
            player = player,
            targetLevel = targetLevel,
            x = targetPos.x + 0.5,
            y = targetPos.y.toDouble(),
            z = targetPos.z + 0.5,
            yRot = player.yRot,
            xRot = player.xRot
        ) {
            MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Entered(player, targetHandle, returnAnchor))
        }
    }

    override fun returnPlayer(player: ServerPlayer): Boolean {
        val anchor = returnAnchors.remove(player.uuid) ?: defaultAnchor(player)
        val sourceInstance = InstanceManager.getInstance(player.serverLevel().dimension())
        if (sourceInstance != null && MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Returning(player, sourceInstance, anchor))) {
            returnAnchors[player.uuid] = anchor
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
                MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Returned(player, sourceInstance, anchor))
            }
        }
        return true
    }

    override fun hasReturnAnchor(playerId: UUID): Boolean = returnAnchors.containsKey(playerId)

    fun peekReturnAnchor(playerId: UUID): PlayerReturnAnchor? = returnAnchors[playerId]

    fun rememberReturnAnchor(playerId: UUID, anchor: PlayerReturnAnchor): Boolean {
        if (returnAnchors.containsKey(playerId)) {
            return false
        }
        returnAnchors[playerId] = anchor
        return true
    }

    fun clearReturnAnchor(playerId: UUID) {
        returnAnchors.remove(playerId)
    }

    fun reset() {
        returnAnchors.clear()
        pendingTransfers.clear()
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
        RuntimeLevelKeySyncManager.ensurePlayerKnowsLevel(player, targetLevel.dimension())
        player.teleportTo(targetLevel, x, y, z, yRot, xRot)
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
            player.absMoveTo(x, y, z, yRot, xRot)
            player.connection.resetPosition()
            currentLevel.chunkSource.move(player)
            player.setYHeadRot(yRot)
            return
        }

        val targetChunk = ChunkPos(BlockPos.containing(x, y, z))
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
        if (event.phase != TickEvent.Phase.END || pendingTransfers.isEmpty()) {
            return
        }

        val remaining = pendingTransfers.size
        repeat(remaining) {
            val transfer = pendingTransfers.removeFirst()
            val player = event.server.playerList.getPlayer(transfer.playerId)
            val targetLevel = event.server.getLevel(transfer.targetLevelKey)
            if (player == null || targetLevel == null || !hasLiveConnection(player)) {
                return@repeat
            }
            if (!InstanceManager.isTravelReadyForLevel(targetLevel.dimension())) {
                pendingTransfers.addLast(transfer)
                return@repeat
            }

            moveWithNetwork(player, targetLevel, transfer.x, transfer.y, transfer.z, transfer.yRot, transfer.xRot)
            val instance = InstanceManager.getInstance(transfer.instanceId) ?: return@repeat
            val returnAnchor = returnAnchors[player.uuid] ?: return@repeat
            MinecraftForge.EVENT_BUS.post(PlayerInstanceTravelEvent.Entered(player, instance, returnAnchor))
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
}
