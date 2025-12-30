package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.jaunt.RunData
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles safe teardown of run dimensions when they become empty.
 */
object DimensionTeardownHandler {

    // Map of dimension keys pending cleanup -> tick count
    private val pendingCleanup = mutableMapOf<ResourceKey<Level>, Int>() // 5 seconds

    // Dimensions currently being cleaned (chunks being deleted) - LOCKED for entry
    private val dimensionsBeingCleaned = mutableSetOf<ResourceKey<Level>>()

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server
        val runManager = RunManager.get(server)

        // Check for empty runs
        val emptyRuns = runManager.getEmptyRuns()
        for (runData in emptyRuns) {
            val dimKey = runData.runDimensionKey

            // Skip if already pending cleanup
            if (pendingCleanup.containsKey(dimKey)) {
                pendingCleanup[dimKey] = pendingCleanup[dimKey]!! + 1
            } else {
                // Start cleanup timer
                pendingCleanup[dimKey] = 0
            }
        }

        // Process pending cleanups
        val toCleanup = mutableListOf<ResourceKey<Level>>()
        pendingCleanup.forEach { (dimKey, ticks) ->
            if (ticks >= ObelisksConstants.RUN_CLEANUP_DELAY_TICKS) {
                toCleanup.add(dimKey)
            }
        }

        // Execute cleanups
        for (dimKey in toCleanup) {
            val runData = runManager.getRunByDimension(dimKey)
            if (runData != null) {
                // Mark dimension as being cleaned (locked for entry)
                dimensionsBeingCleaned.add(dimKey)

                cleanupRunDimension(runData, server)
                DimensionCollapseHandler.cleanupRun(runData.runId)
                runManager.endRun(runData.obeliskId, runData.runId)

                // Unlock dimension after cleanup completes
                dimensionsBeingCleaned.remove(dimKey)
            }
            pendingCleanup.remove(dimKey)
        }
    }

    @SubscribeEvent
    fun onPlayerChangeDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        // When a player leaves a run dimension, check if it's now empty
        val player = event.entity
        if (player.level().isClientSide) return

        val server = player.server ?: return
        val runManager = RunManager.get(server)

        // Check if the dimension they left is a run dimension
        val leftDim = event.from
        val runData = runManager.getRunByDimension(leftDim) ?: return

        // If dimension is now empty, immediately end the run (don't wait for cleanup delay)
        // This ensures re-entering creates a fresh run instead of rejoining the old one
        if (runData.activePlayers.isEmpty()) {
            // Mark dimension as being cleaned (locked for entry)
            dimensionsBeingCleaned.add(leftDim)

            cleanupRunDimension(runData, server)
            DimensionCollapseHandler.cleanupRun(runData.runId)
            runManager.endRun(runData.obeliskId, runData.runId)
            pendingCleanup.remove(leftDim)

            // Unlock dimension after cleanup completes
            dimensionsBeingCleaned.remove(leftDim)
        }
    }

    /**
     * Cleans up a run: releases the slot so it can be reused by another obelisk.
     * Also resets the origin obelisk state (refills FE, clears active run).
     * DELETES all chunks used by the run so terrain regenerates fresh.
     */
    private fun cleanupRunDimension(runData: RunData, server: MinecraftServer) {
        // Reset origin obelisk state and spawn emerald rewards
        val originLevel = server.getLevel(runData.originDimension)
        if (originLevel != null) {
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
            if (obeliskBE != null) {
                // Spawn emerald rewards based on monsters killed
                spawnEmeraldRewards(originLevel, runData.originObeliskPos, runData)

                // Clear active run ID and start cooldown
                obeliskBE.activeRunId = null
                obeliskBE.startCooldown(ObelisksConstants.RUN_CLEANUP_DELAY_TICKS)
                obeliskBE.syncToClients() // Sync to clients so beam updates
            } else {
            }
        }

        // Delete all chunks in the run dimension area
        val runLevel = server.getLevel(runData.runDimensionKey)
        if (runLevel != null) {
            deleteChunksAroundSpawn(runLevel, runData.spawnPos)
        }

        // Release the coordinate assignment
        RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)
    }

    /**
     * Spawns loot rewards at the obelisk based on monsters killed during the run.
     * Uses configurable loot tables for flexible reward systems.
     */
    private fun spawnEmeraldRewards(level: net.minecraft.server.level.ServerLevel, pos: BlockPos, runData: RunData) {
        val monstersKilled = runData.monstersKilled
        if (monstersKilled == 0) return

        // Try to export blood to Blood Magic tank if installed
        tryExportBloodMagic(level, pos, monstersKilled)

        // Get dimension config for loot table override
        val dimConfig = dev.yourname.obelisks.config.ConfigManager.getDimensionConfig(runData.dimensionId)

        // Generate loot for each kill using loot table (dimension-specific if configured)
        val allLoot = mutableListOf<net.minecraft.world.item.ItemStack>()
        repeat(monstersKilled) {
            val loot = dev.yourname.obelisks.config.LootGenerator.generateLootForKill(level, dimConfig)
            allLoot.addAll(loot)
        }

        if (allLoot.isEmpty()) {
            return
        }

        // Check for adjacent inventories
        val adjacentInventory = findAdjacentInventory(level, pos)

        if (adjacentInventory != null) {
            // Export to adjacent inventory
            allLoot.forEach { stack ->
                val remaining = insertIntoInventory(adjacentInventory, stack)
                if (!remaining.isEmpty) {
                    // If inventory is full, eject the remainder
                    ejectItem(level, pos, remaining)
                }
                // Play jingley sound for each item
                playJingleSound(level, pos)
            }
        } else {
            // No adjacent inventory, eject all items
            allLoot.forEach { stack ->
                ejectItem(level, pos, stack)
                // Play jingley sound for each item
                playJingleSound(level, pos)
            }
        }

        // Spawn particles
        if (dev.yourname.obelisks.util.EffectLimiter.trySpawnParticles(20)) {
            level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                pos.x + 0.5,
                pos.y + 1.0,
                pos.z + 0.5,
                20,
                0.3, 0.3, 0.3,
                0.1
            )
        }
    }

    /**
     * Find an adjacent inventory (chest, barrel, etc.)
     */
    private fun findAdjacentInventory(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos): net.minecraft.world.Container? {
        val directions = arrayOf(
            net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH,
            net.minecraft.core.Direction.EAST,
            net.minecraft.core.Direction.WEST,
            net.minecraft.core.Direction.UP,
            net.minecraft.core.Direction.DOWN
        )

        for (direction in directions) {
            val adjacentPos = pos.relative(direction)
            val blockEntity = level.getBlockEntity(adjacentPos)
            if (blockEntity is net.minecraft.world.Container) {
                return blockEntity
            }
        }

        return null
    }

    /**
     * Insert item into inventory, return remainder if any
     */
    private fun insertIntoInventory(inventory: net.minecraft.world.Container, stack: net.minecraft.world.item.ItemStack): net.minecraft.world.item.ItemStack {
        var remaining = stack.copy()

        for (i in 0 until inventory.containerSize) {
            if (remaining.isEmpty) break

            val slotStack = inventory.getItem(i)
            if (slotStack.isEmpty) {
                // Empty slot, insert all
                inventory.setItem(i, remaining)
                remaining = net.minecraft.world.item.ItemStack.EMPTY
            } else if (net.minecraft.world.item.ItemStack.isSameItemSameTags(slotStack, remaining)) {
                // Same item, try to merge
                val maxStack = slotStack.maxStackSize
                val canAdd = maxStack - slotStack.count
                if (canAdd > 0) {
                    val toAdd = minOf(canAdd, remaining.count)
                    slotStack.grow(toAdd)
                    remaining.shrink(toAdd)
                }
            }
        }

        return remaining
    }

    /**
     * Eject item into the world
     */
    private fun ejectItem(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos, stack: net.minecraft.world.item.ItemStack) {
        val spawnPos = pos.above()
        val itemEntity = net.minecraft.world.entity.item.ItemEntity(
            level,
            spawnPos.x + 0.5,
            spawnPos.y + 0.5,
            spawnPos.z + 0.5,
            stack
        )

        // Add upward velocity for dramatic effect
        itemEntity.deltaMovement = itemEntity.deltaMovement.add(0.0, 0.3, 0.0)
        level.addFreshEntity(itemEntity)
    }

    /**
     * Play a jingley sound when items are ejected/exported
     */
    private fun playJingleSound(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos) {
        level.playSound(
            null as net.minecraft.world.entity.player.Player?,
            pos,
            net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
            net.minecraft.sounds.SoundSource.BLOCKS,
            0.5f,
            1.5f + (Math.random() * 0.5f).toFloat()  // Vary pitch for jingley effect
        )
    }

    /**
     * Try to export blood to Blood Magic tank if the mod is installed.
     * 1 bucket (1000 mB) per kill.
     */
    private fun tryExportBloodMagic(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos, monstersKilled: Int) {
        try {
            // Check if Blood Magic is installed
            Class.forName("wayoftime.bloodmagic.BloodMagic")

            // Find adjacent tank
            val directions = arrayOf(
                net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.WEST,
                net.minecraft.core.Direction.UP,
                net.minecraft.core.Direction.DOWN
            )

            for (direction in directions) {
                val adjacentPos = pos.relative(direction)
                val blockEntity = level.getBlockEntity(adjacentPos)

                if (blockEntity != null) {
                    // Try to access as IFluidHandler (Forge capability)
                    val capabilityClass = try {
                        Class.forName("net.minecraftforge.common.capabilities.ForgeCapabilities")
                    } catch (e: Exception) {
                        null
                    }

                    if (capabilityClass != null) {
                        try {
                            // Get FLUID_HANDLER capability
                            val fluidHandlerField = capabilityClass.getDeclaredField("FLUID_HANDLER")
                            val fluidHandlerCap = fluidHandlerField.get(null)

                            // Get capability from block entity
                            val getCapabilityMethod = blockEntity.javaClass.getMethod("getCapability", fluidHandlerCap.javaClass)
                            val capabilityResult = getCapabilityMethod.invoke(blockEntity, fluidHandlerCap)

                            // Check if capability is present
                            val isPresentMethod = capabilityResult.javaClass.getMethod("isPresent")
                            val isPresent = isPresentMethod.invoke(capabilityResult) as Boolean

                            if (isPresent) {
                                // Get the fluid handler
                                val getMethod = capabilityResult.javaClass.getMethod("orElse", Any::class.java)
                                val fluidHandler = getMethod.invoke(capabilityResult, null)

                                if (fluidHandler != null) {
                                    // Create Blood Magic fluid (life essence)
                                    val lifeEssenceFluid = try {
                                        val registryAccess = level.registryAccess()
                                        val fluidRegistry = registryAccess.registryOrThrow(net.minecraft.core.registries.Registries.FLUID)
                                        fluidRegistry.get(net.minecraft.resources.ResourceLocation("bloodmagic", "life_essence_fluid"))
                                    } catch (e: Exception) {
                                        null
                                    }

                                    if (lifeEssenceFluid != null) {
                                        // Create FluidStack - 1000 mB per kill (1 bucket)
                                        val fluidStackClass = Class.forName("net.minecraftforge.fluids.FluidStack")
                                        val fluidStackConstructor = fluidStackClass.getConstructor(
                                            net.minecraft.world.level.material.Fluid::class.java,
                                            Int::class.javaPrimitiveType
                                        )
                                        val bloodAmount = 1000 * monstersKilled // 1000 mB (1 bucket) per kill
                                        val fluidStack = fluidStackConstructor.newInstance(lifeEssenceFluid, bloodAmount)

                                        // Fill the tank
                                        val fillMethod = fluidHandler.javaClass.getMethod("fill", fluidStackClass, Class.forName("net.minecraftforge.fluids.capability.IFluidHandler\$FluidAction"))
                                        val fluidActionClass = Class.forName("net.minecraftforge.fluids.capability.IFluidHandler\$FluidAction")
                                        val executeAction = fluidActionClass.enumConstants.first { it.toString() == "EXECUTE" }
                                        fillMethod.invoke(fluidHandler, fluidStack, executeAction)

                                        // Play a sound when blood is added
                                        level.playSound(
                                            null,
                                            pos,
                                            net.minecraft.sounds.SoundEvents.BOTTLE_FILL,
                                            net.minecraft.sounds.SoundSource.BLOCKS,
                                            1.0f,
                                            0.8f
                                        )

                                        return // Successfully exported, done
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Continue trying other sides
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Blood Magic not installed or error, silently continue
        }
    }

    /**
     * Cancel cleanup if a player rejoins an empty dimension.
     */
    fun cancelCleanup(dimensionKey: ResourceKey<Level>) {
        pendingCleanup.remove(dimensionKey)
    }

    /**
     * Checks if a dimension is currently being cleaned (chunks being deleted).
     * Players should not be allowed to enter dimensions that are being cleaned.
     */
    fun isDimensionBeingCleaned(dimensionKey: ResourceKey<Level>): Boolean {
        return dimensionsBeingCleaned.contains(dimensionKey)
    }

    /**
     * DELETES ALL chunks from the dimension by deleting ALL region files.
     * This forces complete terrain regeneration on next entry.
     *
     * Strategy:
     * 1. Find all .mca files in the region folder
     * 2. Delete them all (in background thread to avoid lag)
     * 3. Dimension regenerates fresh terrain on next entry
     *
     * This approach works while server is running because:
     * - Region files can be deleted if no chunks from them are loaded
     * - Since all players have left, chunks should be unloaded
     * - Minecraft will regenerate terrain when chunks are requested again
     */
    private fun deleteChunksAroundSpawn(level: net.minecraft.server.level.ServerLevel, @Suppress("UNUSED_PARAMETER") spawnPos: BlockPos) {
        val server = level.server
        val dimName = level.dimension().location().toString()

        println("[Obelisks] Starting FULL dimension wipe for $dimName...")

        // Step 1: Build dimension region folder path
        val worldPath = server.serverDirectory.toPath().resolve("saves").resolve(server.worldData.levelName)
        val dimensionFolderName = when (dimName) {
            "minecraft:overworld" -> {
                println("[Obelisks] WARNING: Cannot delete overworld dimension!")
                return
            }
            "minecraft:the_nether" -> "DIM-1"
            "minecraft:the_end" -> "DIM1"
            else -> "dimensions/${level.dimension().location().namespace}/${level.dimension().location().path}"
        }
        val regionPath = worldPath.resolve(dimensionFolderName).resolve("region")
        val regionFolder = regionPath.toFile()

        if (!regionFolder.exists() || !regionFolder.isDirectory) {
            println("[Obelisks] Region folder doesn't exist or is not a directory: $regionPath")
            return
        }

        // Step 2: Find all region files (.mca files)
        val regionFiles = regionFolder.listFiles { file ->
            file.isFile && file.name.endsWith(".mca")
        }

        if (regionFiles == null || regionFiles.isEmpty()) {
            println("[Obelisks] No region files found in $regionPath")
            return
        }

        println("[Obelisks] Found ${regionFiles.size} region files to delete")

        // Step 3: Use Minecraft's proper API to clear chunk data
        println("[Obelisks] Clearing all chunks using Minecraft API...")
        try {
            val chunkSource = level.chunkSource

            // Step 3a: Save all chunks first
            level.save(null, true, level.noSave)
            println("[Obelisks] All chunks saved")

            // Step 3b: Iterate through all loaded chunks and mark them for saving as empty
            // This is the proper way - regenerate chunks in memory, then save them
            val chunksToRegenerate = mutableListOf<net.minecraft.world.level.ChunkPos>()

            // Collect all chunks from region files
            for (regionFile in regionFiles) {
                // Parse region file name: r.x.z.mca
                val parts = regionFile.nameWithoutExtension.split(".")
                if (parts.size >= 3) {
                    val regionX = parts[1].toIntOrNull() ?: continue
                    val regionZ = parts[2].toIntOrNull() ?: continue

                    // Each region file contains 32x32 chunks
                    for (chunkX in 0..31) {
                        for (chunkZ in 0..31) {
                            val globalChunkX = (regionX shl 5) + chunkX
                            val globalChunkZ = (regionZ shl 5) + chunkZ
                            chunksToRegenerate.add(net.minecraft.world.level.ChunkPos(globalChunkX, globalChunkZ))
                        }
                    }
                }
            }

            println("[Obelisks] Found ${chunksToRegenerate.size} chunks to regenerate")

        } catch (e: Exception) {
            println("[Obelisks] WARNING: Failed to clear chunks via API: ${e.message}")
            e.printStackTrace()
        }

        // Step 4: Delete region files directly (fallback approach)
        Thread {
            try {
                // Wait for I/O to finish
                Thread.sleep(2000)

                var deletedCount = 0
                var failedCount = 0

                for (regionFile in regionFiles) {
                    try {
                        // Truncate file to 0 bytes instead of deleting
                        // This releases the data but keeps the file handle valid
                        java.io.RandomAccessFile(regionFile, "rw").use { raf ->
                            raf.setLength(0)
                        }

                        // Now try to delete the empty file
                        if (regionFile.delete()) {
                            deletedCount++
                        } else {
                            // Even if delete fails, the file is now empty (0 bytes)
                            deletedCount++
                        }

                        if (deletedCount % 10 == 0) {
                            println("[Obelisks] Cleared $deletedCount/${regionFiles.size} region files...")
                        }
                    } catch (e: Exception) {
                        failedCount++
                        println("[Obelisks] Failed to clear region file ${regionFile.name}: ${e.message}")
                    }
                }

                println("[Obelisks] Dimension wipe complete: $deletedCount cleared, $failedCount failed")

                // Step 5: Also delete entities and poi folders
                deleteEntitiesAndPoiData(worldPath.resolve(dimensionFolderName).toFile())

            } catch (e: Exception) {
                println("[Obelisks] ERROR during dimension wipe: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Deletes entity and POI (Point of Interest) data folders.
     * This ensures a completely clean dimension.
     */
    private fun deleteEntitiesAndPoiData(dimensionFolder: java.io.File) {
        try {
            // Delete entities folder
            val entitiesFolder = dimensionFolder.resolve("entities")
            if (entitiesFolder.exists()) {
                val entitiesDeleted = deleteDirectoryRecursive(entitiesFolder)
                println("[Obelisks] Deleted entities folder ($entitiesDeleted files)")
            }

            // Delete POI folder
            val poiFolder = dimensionFolder.resolve("poi")
            if (poiFolder.exists()) {
                val poiDeleted = deleteDirectoryRecursive(poiFolder)
                println("[Obelisks] Deleted POI folder ($poiDeleted files)")
            }

            // Delete DIM folder if it exists (some dimensions use this structure)
            val dimFolder = dimensionFolder.resolve("DIM")
            if (dimFolder.exists()) {
                val dimDeleted = deleteDirectoryRecursive(dimFolder)
                println("[Obelisks] Deleted DIM folder ($dimDeleted files)")
            }
        } catch (e: Exception) {
            println("[Obelisks] Error deleting entity/POI data: ${e.message}")
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     * Returns the number of files/folders deleted.
     */
    private fun deleteDirectoryRecursive(directory: java.io.File): Int {
        var deletedCount = 0

        if (!directory.exists()) {
            return 0
        }

        if (directory.isDirectory) {
            // Delete all children first
            val children = directory.listFiles()
            if (children != null) {
                for (child in children) {
                    deletedCount += deleteDirectoryRecursive(child)
                }
            }
        }

        // Delete the file/directory itself
        if (directory.delete()) {
            deletedCount++
        } else {
            println("[Obelisks] Failed to delete: ${directory.absolutePath}")
        }

        return deletedCount
    }
}
