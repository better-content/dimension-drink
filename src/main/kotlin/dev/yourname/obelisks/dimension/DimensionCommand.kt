package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.config.ObeliskTypeRegistry
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.player.PlayerRunInfo
import dev.yourname.obelisks.player.getRunInfo
import dev.yourname.obelisks.run.RunData
import dev.yourname.obelisks.run.RunManager
import dev.yourname.obelisks.util.Result
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level

/**
 * Base command for dimension operations with ACID-like properties.
 * All commands support execute() and rollback().
 */
sealed class DimensionCommand {
    abstract fun execute(): Result<DimensionEvent>
    abstract fun rollback()
}

/**
 * Event logged after command execution.
 */
data class DimensionEvent(
    val commandType: String,
    val playerName: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val details: String = ""
)

/**
 * Command to enter a dimension (player clicks obelisk).
 * Atomically handles: slot allocation, spawn generation, player tracking, teleportation.
 */
class EnterDimensionCommand(
    private val player: ServerPlayer,
    private val obelisk: ObeliskBlockEntity,
    private val obeliskPos: BlockPos,
    private val originLevel: ServerLevel
) : DimensionCommand() {

    private var snapshot: EnterSnapshot? = null

    data class EnterSnapshot(
        val oldBaseType: DimensionBaseType?,
        val oldActiveRunId: Long?,
        val oldPlayerRunInfo: PlayerRunInfo,
        val allocatedSlot: Int?,
        val createdRun: RunData?,
        val forcedChunks: List<ChunkPos> = emptyList()
    )

    override fun execute(): Result<DimensionEvent> {
        // Phase 1: Validate (no side effects)
        val validation = validate()
        if (validation.isFailure()) {
            return Result.failure(validation.getOrThrow())
        }

        // Phase 2: Take snapshot
        val snapshotResult = takeSnapshot()
        if (snapshotResult.isFailure()) {
            val error = (snapshotResult as Result.Failure).error
            return Result.failure(error)
        }
        snapshot = snapshotResult.getOrNull()

        // Phase 3: Execute with rollback on failure
        return try {
            executeInternal()
        } catch (e: Exception) {
            rollback()
            Result.failure("Enter dimension failed: ${e.message}", e)
        }
    }

    private fun validate(): Result<String> {
        // Check if player already in run
        val playerRunInfo = player.getRunInfo()
        if (playerRunInfo?.isInRun() == true) {
            return Result.failure("Player already in a run")
        }

        // Check slot availability
        val server = originLevel.server
        val hasSlot = (DimensionSlotManager.getUsedSlotCount() < DimensionSlotManager.getTotalSlotCount()) ||
                     DimensionSlotManager.getSlotForObelisk(obelisk.obeliskId) != null

        if (!hasSlot) {
            val used = DimensionSlotManager.getUsedSlotCount()
            val total = DimensionSlotManager.getTotalSlotCount()
            return Result.failure("All dimension slots in use ($used/$total)")
        }

        return Result.success("Validation passed")
    }

    private fun takeSnapshot(): Result<EnterSnapshot> {
        val playerRunInfo = player.getRunInfo()
            ?: return Result.failure("Player has no run info capability")

        return Result.success(EnterSnapshot(
            oldBaseType = obelisk.baseType,
            oldActiveRunId = obelisk.activeRunId,
            oldPlayerRunInfo = PlayerRunInfo(
                originObeliskId = playerRunInfo.originObeliskId,
                originPos = playerRunInfo.originPos,
                originDimension = playerRunInfo.originDimension,
                runId = playerRunInfo.runId,
                runDimensionKey = playerRunInfo.runDimensionKey
            ),
            allocatedSlot = null,
            createdRun = null
        ))
    }

    private fun executeInternal(): Result<DimensionEvent> {
        val server = originLevel.server
        val runManager = RunManager.get(server)

        // Step 1: Assign base type if not set
        if (obelisk.baseType == null) {
            obelisk.baseType = DimensionBaseType.random()
            obelisk.setChanged()
        }
        val baseType = obelisk.baseType!!

        // Step 2: Allocate dimension slot and get prepared spawn platform
        // Platform is generated on-demand and cached for the slot's lifetime
        val dimensionResult = DimensionSlotManager.getDimensionForRun(
            server, obelisk.obeliskId, baseType
        ) ?: return Result.failure("Failed to allocate dimension slot")

        val (runDimension, spawnPos) = dimensionResult
        val allocatedSlot = DimensionSlotManager.getSlotForObelisk(obelisk.obeliskId)
            ?: return Result.failure("Slot allocation inconsistency")

        snapshot = snapshot!!.copy(allocatedSlot = allocatedSlot)

        // Step 3: Create run data
        val slotDimensionKey = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation("obelisks", "run_slot_$allocatedSlot")
        )

        val runData = runManager.getOrCreateRunWithDimension(
            obelisk.obeliskId, baseType, slotDimensionKey,
            spawnPos, obeliskPos, originLevel.dimension(),
            obelisk.activeRunId
        )
        snapshot = snapshot!!.copy(createdRun = runData)

        // Step 5: Update obelisk
        if (obelisk.activeRunId == null) {
            obelisk.activeRunId = runData.runId
            obelisk.setChanged()
        }

        // Step 6: Update player run info
        val playerRunInfo = player.getRunInfo()
            ?: return Result.failure("Lost player run capability")

        // Store the player's current position as return location
        val playerOriginPos = player.blockPosition()
        val safeOriginPos = if (playerOriginPos.y < ObelisksConstants.SAFE_Y_MIN || playerOriginPos.y > ObelisksConstants.SAFE_Y_MAX) {
            // Player is in void or too high - use obelisk cap position as fallback
            println("[Obelisks] WARNING: Player at unsafe Y=${playerOriginPos.y}, using obelisk position instead")
            obeliskPos.above() // One block above obelisk cap
        } else {
            playerOriginPos
        }
        println("[Obelisks] Storing player origin position: $safeOriginPos")
        
        playerRunInfo.apply {
            originObeliskId = obelisk.obeliskId
            originPos = safeOriginPos // Player's exact position when entering (or safe fallback)
            originDimension = originLevel.dimension()
            runId = runData.runId
            runDimensionKey = runData.runDimensionKey
        }

        // Step 7: Add player to run manager
        runManager.addPlayerToRun(player.uuid, obelisk.obeliskId, runData.runId)

        // Step 8: Force load spawn chunks
        val forcedChunks = forceLoadSpawnArea(runDimension, spawnPos)
        snapshot = snapshot!!.copy(forcedChunks = forcedChunks)

        // Step 8.5: Ensure chunks are fully loaded and tracked by forcing them to be sent to client
        // This prevents the player from seeing missing chunks when they arrive
        forcedChunks.forEach { chunkPos ->
            val chunk = runDimension.getChunk(chunkPos.x, chunkPos.z)
            runDimension.chunkSource.addRegionTicket(
                net.minecraft.server.level.TicketType.PORTAL,
                chunkPos,
                ObelisksConstants.CHUNK_TICKET_LEVEL,
                net.minecraft.core.BlockPos(chunkPos.x * 16, 64, chunkPos.z * 16)
            )
        }

        // Step 9: Teleport player
        teleportPlayer(player, runDimension, spawnPos)

        // Notify player
        player.sendSystemMessage(
            Component.literal("Entering ${baseType.name} run #${runData.runId}...")
        )

        return Result.success(DimensionEvent(
            commandType = "EnterDimension",
            playerName = player.gameProfile.name,
            success = true,
            details = "Obelisk ${obelisk.obeliskId}, Run ${runData.runId}"
        ))
    }

    private fun forceLoadSpawnArea(level: ServerLevel, spawnPos: BlockPos): List<ChunkPos> {
        val chunks = mutableListOf<ChunkPos>()
        val chunkX = spawnPos.x shr 4
        val chunkZ = spawnPos.z shr 4

        for (x in -ObelisksConstants.ENTER_FORCE_LOAD_RADIUS..ObelisksConstants.ENTER_FORCE_LOAD_RADIUS) {
            for (z in -ObelisksConstants.ENTER_FORCE_LOAD_RADIUS..ObelisksConstants.ENTER_FORCE_LOAD_RADIUS) {
                val chunkPos = ChunkPos(chunkX + x, chunkZ + z)
                level.setChunkForced(chunkPos.x, chunkPos.z, true)
                chunks.add(chunkPos)
            }
        }

        return chunks
    }

    private fun teleportPlayer(player: ServerPlayer, targetLevel: ServerLevel, spawnPos: BlockPos) {
        println("[Obelisks] ========================================")
        println("[Obelisks] TELEPORT DEBUG START")
        println("[Obelisks] Player current position: ${player.position()}")
        println("[Obelisks] Player current dimension: ${player.level().dimension().location()}")
        println("[Obelisks] Target spawn BlockPos: $spawnPos")
        println("[Obelisks] Target dimension: ${targetLevel.dimension().location()}")
        println("[Obelisks] ========================================")
        
        player.changeDimension(targetLevel, object : net.minecraftforge.common.util.ITeleporter {
            override fun placeEntity(
                entity: net.minecraft.world.entity.Entity,
                currentWorld: ServerLevel,
                destWorld: ServerLevel,
                yaw: Float,
                repositionEntity: java.util.function.Function<Boolean, net.minecraft.world.entity.Entity>
            ): net.minecraft.world.entity.Entity {
                val newEntity = repositionEntity.apply(false)
                val targetX = spawnPos.x.toDouble() + ObelisksConstants.TELEPORT_CENTER_OFFSET
                val targetY = spawnPos.y.toDouble()
                val targetZ = spawnPos.z.toDouble() + ObelisksConstants.TELEPORT_CENTER_OFFSET
                
                println("[Obelisks] ========================================")
                println("[Obelisks] ITeleporter.placeEntity called")
                println("[Obelisks] Entity before moveTo: ${newEntity.position()}")
                println("[Obelisks] Moving to: X=$targetX Y=$targetY Z=$targetZ")
                
                newEntity.moveTo(targetX, targetY, targetZ, yaw, entity.xRot)
                
                println("[Obelisks] Entity after moveTo: ${newEntity.position()}")
                println("[Obelisks] Entity block position: ${newEntity.blockPosition()}")
                println("[Obelisks] ========================================")
                return newEntity
            }

            override fun isVanilla(): Boolean = false
        })
        
        println("[Obelisks] ========================================")
        println("[Obelisks] TELEPORT DEBUG END")
        println("[Obelisks] Player final position: ${player.position()}")
        println("[Obelisks] Player final dimension: ${player.level().dimension().location()}")
        println("[Obelisks] ========================================")
        
        // Schedule a delayed check and force position sync
        player.server.execute {
            println("[Obelisks] ========================================")
            println("[Obelisks] DELAYED POSITION CHECK (1 tick later)")
            println("[Obelisks] Player position: ${player.position()}")
            println("[Obelisks] Player block position: ${player.blockPosition()}")
            println("[Obelisks] Player dimension: ${player.level().dimension().location()}")
            
            // Check what block is at player position
            val blockBelow = player.level().getBlockState(player.blockPosition().below())
            val blockAt = player.level().getBlockState(player.blockPosition())
            println("[Obelisks] Block below player: $blockBelow")
            println("[Obelisks] Block at player: $blockAt")
            
            // FORCE position resync to client
            println("[Obelisks] Forcing position update to client...")
            player.teleportTo(player.x, player.y, player.z)
            
            // Also try sending explicit position packet
            player.connection.send(
                net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket(
                    player.x,
                    player.y,
                    player.z,
                    player.yRot,
                    player.xRot,
                    setOf(),
                    0
                )
            )
            println("[Obelisks] Position update packets sent")
            println("[Obelisks] ========================================")
        }
    }

    override fun rollback() {
        val snap = snapshot ?: return
        val server = originLevel.server
        val runManager = RunManager.get(server)

        println("[Obelisks] ROLLBACK: EnterDimension for ${player.gameProfile.name}")

        try {
            // Rollback in REVERSE order

            // 9. Teleport back if player was moved
            if (player.level() != originLevel) {
                val originPos = snap.oldPlayerRunInfo.originPos ?: obeliskPos
                player.teleportTo(
                    originLevel,
                    originPos.x.toDouble() + 0.5,
                    originPos.y.toDouble(),
                    originPos.z.toDouble() + 0.5,
                    player.yRot,
                    player.xRot
                )
            }

            // 8. Unload forced chunks
            if (snap.createdRun != null) {
                val runLevel = server.getLevel(snap.createdRun.runDimensionKey)
                if (runLevel != null) {
                    snap.forcedChunks.forEach { chunk ->
                        runLevel.setChunkForced(chunk.x, chunk.z, false)
                    }
                }
            }

            // 7. Remove player from run manager
            runManager.removePlayerFromRun(player.uuid)

            // 6. Restore player run info
            val playerRunInfo = player.getRunInfo()
            playerRunInfo?.apply {
                originObeliskId = snap.oldPlayerRunInfo.originObeliskId
                originPos = snap.oldPlayerRunInfo.originPos
                originDimension = snap.oldPlayerRunInfo.originDimension
                runId = snap.oldPlayerRunInfo.runId
                runDimensionKey = snap.oldPlayerRunInfo.runDimensionKey
            }

            // 5. Restore obelisk state
            obelisk.activeRunId = snap.oldActiveRunId
            obelisk.baseType = snap.oldBaseType
            obelisk.setChanged()

            // 4. End run if we created it and it's empty
            if (snap.createdRun != null && snap.createdRun.activePlayers.isEmpty()) {
                DimensionCollapseHandler.cleanupRun(snap.createdRun.runId)
                runManager.endRun(obelisk.obeliskId, snap.createdRun.runId)
            }

            // 2. Release dimension slot
            if (snap.allocatedSlot != null) {
                DimensionSlotManager.releaseSlot(obelisk.obeliskId)
            }

            player.sendSystemMessage(Component.literal("Failed to enter dimension - transaction rolled back"))
        } catch (e: Exception) {
            println("[Obelisks] ERROR during rollback: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * Command to exit a dimension (player returns to origin).
 * Atomically handles: player tracking removal, teleportation, data cleanup.
 */
class ExitDimensionCommand(
    private val player: ServerPlayer,
    private val reason: String
) : DimensionCommand() {

    private var snapshot: ExitSnapshot? = null

    data class ExitSnapshot(
        val runData: RunData,
        val playerRunInfo: PlayerRunInfo,
        val wasInRunManager: Boolean,
        val originalDimension: ResourceKey<Level>,
        val originalPosition: BlockPos
    )

    override fun execute(): Result<DimensionEvent> {
        // Phase 1: Validate
        val playerRunInfo = player.getRunInfo()
            ?: return Result.failure("Player has no run info capability")

        if (!playerRunInfo.isInRun()) {
            return Result.failure("Player not in a run")
        }

        val server = player.server
        val runManager = RunManager.get(server)
        val runData = runManager.getPlayerRun(player.uuid)

        // Phase 2: Snapshot
        if (runData == null) {
            // Player has run info but not in manager - clear stale data
            playerRunInfo.clear()
            return Result.failure("RunData missing from manager (cleared stale data)")
        }

        snapshot = ExitSnapshot(
            runData = RunData(
                obeliskId = runData.obeliskId,
                runId = runData.runId,
                baseType = runData.baseType,
                runDimensionKey = runData.runDimensionKey,
                spawnPos = runData.spawnPos,
                originObeliskPos = runData.originObeliskPos,
                originDimension = runData.originDimension,
                activePlayers = runData.activePlayers.toMutableSet()
            ),
            playerRunInfo = PlayerRunInfo(
                originObeliskId = playerRunInfo.originObeliskId,
                originPos = playerRunInfo.originPos,
                originDimension = playerRunInfo.originDimension,
                runId = playerRunInfo.runId,
                runDimensionKey = playerRunInfo.runDimensionKey
            ),
            wasInRunManager = true,
            originalDimension = player.level().dimension(),
            originalPosition = player.blockPosition()
        )

        // Phase 3: Execute with rollback on failure
        return try {
            executeExit(runManager, playerRunInfo, runData)
        } catch (e: Exception) {
            rollback()
            Result.failure("Exit dimension failed: ${e.message}", e)
        }
    }

    private fun executeExit(
        runManager: RunManager,
        playerRunInfo: PlayerRunInfo,
        runData: RunData
    ): Result<DimensionEvent> {
        val snap = snapshot!!

        // Step 1: Remove from tracking BEFORE teleport (prevents race conditions)
        runManager.removePlayerFromRun(player.uuid)

        // Step 2: Teleport player
        val originDim = snap.playerRunInfo.originDimension
            ?: return Result.failure("No origin dimension in player run info")
        val originPos = snap.playerRunInfo.originPos
            ?: return Result.failure("No origin position in player run info")

        println("[Obelisks] ========================================")
        println("[Obelisks] RETURN TELEPORT - Stored origin position: $originPos")
        println("[Obelisks] RETURN TELEPORT - Stored origin dimension: $originDim")
        println("[Obelisks] ========================================")

        val originLevel = player.server.getLevel(originDim)
            ?: return Result.failure("Origin dimension not loaded")

        val targetX = originPos.x.toDouble() + 0.5
        val targetY = originPos.y.toDouble()
        val targetZ = originPos.z.toDouble() + 0.5
        
        if (player.level().dimension() != originDim) {
            println("[Obelisks] RETURN TELEPORT - Changing dimension to $originDim")
            println("[Obelisks] RETURN TELEPORT - Target position: X=$targetX Y=$targetY Z=$targetZ")
            
            // Use teleportTo with level parameter for cross-dimension teleport
            player.teleportTo(originLevel, targetX, targetY, targetZ, player.yRot, player.xRot)
            
            println("[Obelisks] RETURN TELEPORT - After teleportTo, player position: ${player.position()}")
        } else {
            println("[Obelisks] RETURN TELEPORT (same dimension) - Moving to: X=$targetX Y=$targetY Z=$targetZ")
            player.teleportTo(targetX, targetY, targetZ)
        }

        // Step 3: Clear player data AFTER successful teleport
        playerRunInfo.clear()

        // Step 4: Notify player
        player.sendSystemMessage(Component.literal("Returned to origin ($reason)"))

        return Result.success(DimensionEvent(
            commandType = "ExitDimension",
            playerName = player.gameProfile.name,
            success = true,
            details = "Reason: $reason"
        ))
    }

    override fun rollback() {
        val snap = snapshot ?: return
        val server = player.server
        val runManager = RunManager.get(server)

        println("[Obelisks] ROLLBACK: ExitDimension for ${player.gameProfile.name}")

        try {
            // Rollback in REVERSE order

            // 3. Restore player run info
            val playerRunInfo = player.getRunInfo()
            playerRunInfo?.apply {
                originObeliskId = snap.playerRunInfo.originObeliskId
                originPos = snap.playerRunInfo.originPos
                originDimension = snap.playerRunInfo.originDimension
                runId = snap.playerRunInfo.runId
                runDimensionKey = snap.playerRunInfo.runDimensionKey
            }

            // 2. Teleport back to run dimension (if they were moved)
            val runLevel = server.getLevel(snap.runData.runDimensionKey)
            if (runLevel != null && player.level() != runLevel) {
                player.changeDimension(runLevel, object : net.minecraftforge.common.util.ITeleporter {
                    override fun placeEntity(
                        entity: net.minecraft.world.entity.Entity,
                        currentWorld: ServerLevel,
                        destWorld: ServerLevel,
                        yaw: Float,
                        repositionEntity: java.util.function.Function<Boolean, net.minecraft.world.entity.Entity>
                    ): net.minecraft.world.entity.Entity {
                        val newEntity = repositionEntity.apply(false)
                        newEntity.moveTo(
                            snap.runData.spawnPos.x.toDouble() + ObelisksConstants.TELEPORT_CENTER_OFFSET,
                            snap.runData.spawnPos.y.toDouble() + ObelisksConstants.TELEPORT_SPAWN_HEIGHT_OFFSET,
                            snap.runData.spawnPos.z.toDouble() + ObelisksConstants.TELEPORT_CENTER_OFFSET,
                            yaw,
                            entity.xRot
                        )
                        return newEntity
                    }

                    override fun isVanilla(): Boolean = false
                })
            }

            // 1. Re-add to run manager
            if (snap.wasInRunManager) {
                runManager.addPlayerToRun(player.uuid, snap.runData.obeliskId, snap.runData.runId)
            }

            player.sendSystemMessage(Component.literal("Failed to exit dimension - transaction rolled back"))
        } catch (e: Exception) {
            println("[Obelisks] ERROR during rollback: ${e.message}")
            e.printStackTrace()
        }
    }
}
