package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.player.PlayerRunInfo
import dev.yourname.obelisks.player.getRunInfo
import dev.yourname.obelisks.jaunt.RunData
import dev.yourname.obelisks.jaunt.RunManager
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
        val oldDimensionId: String?,
        val oldActiveRunId: Long?,
        val oldPlayerRunInfo: PlayerRunInfo,
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

        // Check if joining existing run vs starting new run
        val isJoiningExistingRun = obelisk.activeRunId != null && obelisk.isRunActive()

        if (!isJoiningExistingRun) {
            // Starting a NEW run - require full charge
            val currentFE = obelisk.getEnergyStored()
            val maxFE = obelisk.getMaxEnergyStored()
            if (currentFE < maxFE) {
                val percent = (currentFE.toDouble() / maxFE * 100).toInt()
                return Result.failure("Obelisk not fully charged ($percent% - wait for 100%)")
            }

            // Check if obelisk is on cooldown
            if (obelisk.isOnCooldown()) {
                val remaining = obelisk.getCooldownRemainingSeconds()
                return Result.failure("Obelisk on cooldown (${remaining}s remaining)")
            }
        }
        // If joining existing run, skip FE and cooldown checks (multiplayer support)

        // No need to check slot availability - we use actual dimensions with unlimited runs

        return Result.success("Validation passed")
    }

    private fun takeSnapshot(): Result<EnterSnapshot> {
        val playerRunInfo = player.getRunInfo()
            ?: return Result.failure("Player has no run info capability")

        return Result.success(EnterSnapshot(
            oldDimensionId = obelisk.targetDimensionId,
            oldActiveRunId = obelisk.activeRunId,
            oldPlayerRunInfo = PlayerRunInfo(
                originObeliskId = playerRunInfo.originObeliskId,
                originPos = playerRunInfo.originPos,
                originDimension = playerRunInfo.originDimension,
                runId = playerRunInfo.runId,
                runDimensionKey = playerRunInfo.runDimensionKey
            ),
            createdRun = null
        ))
    }

    private fun executeInternal(): Result<DimensionEvent> {
        val server = originLevel.server
        val runManager = RunManager.get(server)

        // Step 1: Get dimension ID from obelisk (must be set)
        val dimensionId = obelisk.targetDimensionId
            ?: return Result.failure("Obelisk has no dimension configured")

        // Step 1.5: Check if dimension is being cleaned (chunks being deleted)
        val targetDimKey = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation(dimensionId)
        )
        if (DimensionTeardownHandler.isDimensionBeingCleaned(targetDimKey)) {
            return Result.failure("Dimension is being cleaned - please wait a moment and try again")
        }

        // Step 2: Get or create run data first (to get runId)
        val existingRun = obelisk.activeRunId?.let { runManager.getRun(obelisk.obeliskId, it) }
        val runId = existingRun?.runId ?: runManager.getNextRunId()

        // Step 3: Assign coordinates in the actual modded dimension and generate platform
        val locationResult = RunCoordinateManager.assignRunLocation(
            server, obelisk.obeliskId, runId, dimensionId
        ) ?: return Result.failure("Dimension $dimensionId not available on server")

        val (runDimension, spawnPos) = locationResult

        // Step 4: Create run data with the actual dimension key
        val runData = runManager.getOrCreateRunWithDimension(
            obelisk.obeliskId, dimensionId, runDimension.dimension(),
            spawnPos, obeliskPos, originLevel.dimension(),
            obelisk.activeRunId
        )
        snapshot = snapshot!!.copy(createdRun = runData)

        // Step 5: Update obelisk
        if (obelisk.activeRunId == null) {
            obelisk.activeRunId = runData.runId
            obelisk.syncToClients() // Sync to clients so beam appears
        }

        // Step 5.5: Play activation sound at obelisk
        if (dev.yourname.obelisks.ObelisksConstants.OBELISK_ACTIVATION_SOUND_ENABLED) {
            if (dev.yourname.obelisks.util.EffectLimiter.tryPlaySound()) {
                originLevel.playSound(
                    null,
                    obeliskPos,
                    net.minecraft.sounds.SoundEvents.END_PORTAL_SPAWN,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    1.0f,
                    1.0f
                )
            }
        }

        // Step 6: Update player run info
        val playerRunInfo = player.getRunInfo()
            ?: return Result.failure("Lost player run capability")

        // Store position on top of obelisk as return location
        // This ensures player returns to a safe, consistent location at the obelisk
        val returnPos = obeliskPos.above() // One block above obelisk cap

        println("[Obelisks] Storing return position for ${player.name.string}: $returnPos (obelisk at $obeliskPos)")

        playerRunInfo.originObeliskId = obelisk.obeliskId
        playerRunInfo.originPos = returnPos // Return to top of obelisk
        playerRunInfo.originDimension = originLevel.dimension()
        playerRunInfo.runId = runData.runId
        playerRunInfo.runDimensionKey = runData.runDimensionKey

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

        // Step 10: Notify player
        val dimConfig = dev.yourname.obelisks.config.ConfigManager.getDimensionConfig(dimensionId)
        val dimName = dimConfig?.dimensionName ?: dimensionId
        player.sendSystemMessage(
            Component.literal("Entering $dimName run #${runData.runId}...")
        )

        return Result.success(DimensionEvent(
            commandType = "EnterDimension",
            playerName = player.gameProfile.name,
            success = true,
            details = "Obelisk ${obelisk.obeliskId}, Run ${runData.runId}"
        ))
    }

    /**
     * Direct teleport to modded dimension (no run system isolation).
     * Used for dimensions that don't have run templates.
     */
    private fun executeModdedDimensionTeleport(): Result<DimensionEvent> {
        val server = originLevel.server
        val targetDimKey = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation(obelisk.targetDimensionId!!)
        )

        val targetLevel = server.getLevel(targetDimKey)
            ?: return Result.failure("Target dimension not available: ${obelisk.targetDimensionId}")

        // Find spawn location in target dimension
        val targetSpawnPos = targetLevel.sharedSpawnPos

        // Store origin for return
        val playerRunInfo = player.getRunInfo()
        playerRunInfo?.apply {
            originObeliskId = obelisk.obeliskId
            originPos = player.blockPosition()
            originDimension = originLevel.dimension()
        }

        // Teleport player
        player.changeDimension(targetLevel, object : net.minecraftforge.common.util.ITeleporter {
            override fun placeEntity(
                entity: net.minecraft.world.entity.Entity,
                currentWorld: ServerLevel,
                destWorld: ServerLevel,
                yaw: Float,
                repositionEntity: java.util.function.Function<Boolean, net.minecraft.world.entity.Entity>
            ): net.minecraft.world.entity.Entity {
                val newEntity = repositionEntity.apply(false)
                newEntity.moveTo(
                    targetSpawnPos.x.toDouble() + 0.5,
                    targetSpawnPos.y.toDouble(),
                    targetSpawnPos.z.toDouble() + 0.5,
                    yaw,
                    entity.xRot
                )
                return newEntity
            }

            override fun isVanilla(): Boolean = false
        })

        // Play activation sound
        if (dev.yourname.obelisks.ObelisksConstants.OBELISK_ACTIVATION_SOUND_ENABLED) {
            if (dev.yourname.obelisks.util.EffectLimiter.tryPlaySound()) {
                originLevel.playSound(
                    null,
                    obeliskPos,
                    net.minecraft.sounds.SoundEvents.END_PORTAL_SPAWN,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    1.0f,
                    1.0f
                )
            }
        }

        val dimName = obelisk.dimensionDisplayName ?: obelisk.targetDimensionId.toString()
        player.sendSystemMessage(Component.literal("Entering $dimName..."))

        return Result.success(DimensionEvent(
            commandType = "EnterModdedDimension",
            playerName = player.gameProfile.name,
            success = true,
            details = "Obelisk ${obelisk.obeliskId} -> $dimName"
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
        // Spawn spooky particles at departure point
        spawnTeleportParticles(originLevel, player.position())

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


                newEntity.moveTo(targetX, targetY, targetZ, yaw, entity.xRot)

                // Spawn spooky particles at arrival point
                spawnTeleportParticles(destWorld, net.minecraft.world.phys.Vec3(targetX, targetY, targetZ))

                return newEntity
            }

            override fun isVanilla(): Boolean = false
        })
        
        
        // Schedule a delayed check and force position sync
        player.server.execute {
            
            // Check what block is at player position
            val blockBelow = player.level().getBlockState(player.blockPosition().below())
            val blockAt = player.level().getBlockState(player.blockPosition())
            
            // FORCE position resync to client
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
        }
    }

    override fun rollback() {
        val snap = snapshot ?: return
        val server = originLevel.server
        val runManager = RunManager.get(server)


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
            obelisk.targetDimensionId = snap.oldDimensionId
            obelisk.setChanged()

            // 4. End run if we created it and it's empty
            if (snap.createdRun != null && snap.createdRun.activePlayers.isEmpty()) {
                DimensionCollapseHandler.cleanupRun(snap.createdRun.runId)
                runManager.endRun(obelisk.obeliskId, snap.createdRun.runId)
                // Release coordinates
                RunCoordinateManager.releaseRun(obelisk.obeliskId, snap.createdRun.runId)
            }

            player.sendSystemMessage(Component.literal("Failed to enter dimension - transaction rolled back"))
        } catch (e: Exception) {
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
                dimensionId = runData.dimensionId,
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

        // Phase 3: Execute with rollback on failurethe s
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

        println("[Obelisks] Returning ${player.name.string} to position: $originPos in dimension $originDim")

        val originLevel = player.server.getLevel(originDim)
            ?: return Result.failure("Origin dimension not loaded")

        val targetX = originPos.x.toDouble() + 0.5
        val targetY = originPos.y.toDouble()
        val targetZ = originPos.z.toDouble() + 0.5
        
        if (player.level().dimension() != originDim) {

            // Use teleportTo with level parameter for cross-dimension teleport
            player.teleportTo(originLevel, targetX, targetY, targetZ, player.yRot, player.xRot)

        } else {
            player.teleportTo(targetX, targetY, targetZ)
        }

        // Step 2.5: Reset fall damage to prevent death on return
        player.fallDistance = 0.0f

        // Step 3: Clear player data AFTER successful teleport
        playerRunInfo.clear()

        // Step 3.5: Play return sound effect at origin location
        if (dev.yourname.obelisks.ObelisksConstants.OBELISK_ACTIVATION_SOUND_ENABLED) {
            if (dev.yourname.obelisks.util.EffectLimiter.tryPlaySound()) {
                originLevel.playSound(
                    null,
                    BlockPos(targetX.toInt(), targetY.toInt(), targetZ.toInt()),
                    net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                    net.minecraft.sounds.SoundSource.PLAYERS,
                    1.0f,
                    1.0f
                )
            }
        }

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
            e.printStackTrace()
        }
    }
}

/**
 * Spawns spooky interdimensional particles at teleportation points.
 */
private fun spawnTeleportParticles(level: ServerLevel, pos: net.minecraft.world.phys.Vec3) {
    val random = level.random

    // Spawn a burst of spooky particles in a sphere around the teleport location
    for (i in 0 until 30) {
        val radius = 1.5
        val theta = random.nextDouble() * Math.PI * 2.0
        val phi = random.nextDouble() * Math.PI

        val offsetX = radius * Math.sin(phi) * Math.cos(theta)
        val offsetY = radius * Math.sin(phi) * Math.sin(theta)
        val offsetZ = radius * Math.cos(phi)

        val spawnX = pos.x + offsetX
        val spawnY = pos.y + offsetY + 1.0
        val spawnZ = pos.z + offsetZ

        // Velocity outward from center
        val velocityX = offsetX * 0.15
        val velocityY = offsetY * 0.15
        val velocityZ = offsetZ * 0.15

        // Mix of spooky particle types
        val particleType = when (random.nextInt(5)) {
            0 -> net.minecraft.core.particles.ParticleTypes.PORTAL
            1 -> net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL
            2 -> net.minecraft.core.particles.ParticleTypes.WARPED_SPORE
            3 -> net.minecraft.core.particles.ParticleTypes.SOUL
            else -> net.minecraft.core.particles.ParticleTypes.ENCHANT
        }

        level.sendParticles(
            particleType,
            spawnX, spawnY, spawnZ,
            1, // count
            velocityX, velocityY, velocityZ,
            0.0 // speed multiplier
        )
    }
}
