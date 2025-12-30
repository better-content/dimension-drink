package dev.yourname.obelisks.jaunt

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles per-tick FE drain from origin obelisks based on active players in run dimensions.
 * Phase 3: FE System
 */
object InstanceTickHandler {

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server
        val runManager = RunManager.get(server)

        // Process each active run
        for (runData in runManager.getAllRuns()) {
            // Count players in this run's dimension
            val playerCount = runData.activePlayers.size

            if (playerCount == 0) {
                // No players, no drain (cleanup handler will handle empty runs)
                continue
            }

            // Increment tick counter
            runData.ticksElapsed++

            // Update drain multiplier based on exponential growth (dimension-specific)
            // Get dimension-specific config
            val dimConfig = dev.yourname.obelisks.config.ConfigManager.getDimensionConfig(runData.dimensionId)
            val exponentialInterval = (dimConfig?.drainExponentialIntervalTicks ?: ObelisksConstants.DRAIN_EXPONENTIAL_INTERVAL_TICKS).coerceAtLeast(1)

            if (runData.ticksElapsed % exponentialInterval == 0L) {
                // Exponential formula: multiplier = e^(factor * ticks)
                val factor = dimConfig?.drainExponentialFactor ?: ObelisksConstants.DRAIN_EXPONENTIAL_FACTOR
                runData.drainMultiplier = Math.exp(factor * runData.ticksElapsed)
            }

            // Get origin obelisk BlockEntity
            val originLevel = server.getLevel(runData.originDimension)
            if (originLevel == null) {
                // Origin dimension not loaded - this shouldn't happen for overworld
                continue
            }

            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? ObeliskBlockEntity
            if (obeliskBE == null) {
                // Obelisk no longer exists - force end the run
                forceEndRun(server, runData)
                continue
            }

            // Calculate FE drain for this tick with exponential multiplier
            // Use modified values from the obelisk's modifiers
            val baseDrain = obeliskBE.getModifiedBaseDrain() +
                           (playerCount * obeliskBE.getModifiedPlayerDrain())
            val drainAmount = (baseDrain * runData.drainMultiplier).toInt()

            // Drain FE
            val success = obeliskBE.drainEnergy(drainAmount)

            if (!success) {
                // FE depleted to 0% - trigger forced collapse
                forceCollapseRun(server, runData)
            }
        }
    }

    /**
     * Forces a run to end when FE reaches 0%.
     * Returns all players to their origin obelisks.
     */
    private fun forceCollapseRun(server: net.minecraft.server.MinecraftServer, runData: RunData) {
        // Get all players in this run
        val playersToReturn = runData.activePlayers.toList()

        // Return each player
        for (playerId in playersToReturn) {
            val player = server.playerList.getPlayer(playerId)
            if (player != null) {
                dev.yourname.obelisks.player.PlayerReturnHandler.returnPlayerToOrigin(
                    player,
                    "Dimension collapsed - FE depleted to 0%!"
                )
            }
        }

        // Mark the obelisk as no longer having an active run and spawn rewards
        val originLevel = server.getLevel(runData.originDimension)
        if (originLevel != null) {
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? ObeliskBlockEntity
            if (obeliskBE != null) {
                // Spawn completion rewards (players survived and were force-returned)
                spawnCompletionRewards(originLevel, runData.originObeliskPos, runData)
                
                obeliskBE.activeRunId = null
                obeliskBE.setChanged()
            }
        }

        // CRITICAL: End the run in RunManager to trigger cleanup (boss bar, etc.)
        val runManager = RunManager.get(server)
        runManager.endRun(runData.obeliskId, runData.runId)

        // Release the coordinate assignment
        dev.yourname.obelisks.dimension.RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)
    }

    /**
     * Forces a run to end when the origin obelisk is destroyed.
     */
    private fun forceEndRun(server: net.minecraft.server.MinecraftServer, runData: RunData) {
        // Similar to forceCollapseRun but with different message
        val playersToReturn = runData.activePlayers.toList()

        for (playerId in playersToReturn) {
            val player = server.playerList.getPlayer(playerId)
            if (player != null) {
                dev.yourname.obelisks.player.PlayerReturnHandler.returnPlayerToOrigin(
                    player,
                    "Origin obelisk was destroyed!"
                )
            }
        }

        // CRITICAL: End the run in RunManager to trigger cleanup (boss bar, etc.)
        val runManager = RunManager.get(server)
        runManager.endRun(runData.obeliskId, runData.runId)

        // Release the coordinate assignment
        dev.yourname.obelisks.dimension.RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)
    }

    /**
     * Spawns loot rewards at the obelisk based on monsters killed during the run.
     * Uses configurable loot tables for flexible reward systems.
     */
    private fun spawnCompletionRewards(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos, runData: RunData) {
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
}
