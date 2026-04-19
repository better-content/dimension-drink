package dev.yourname.obelisks.gametest

import com.google.gson.GsonBuilder
import com.mojang.authlib.GameProfile
import dev.yourname.instanceddimensions.MOD_ID as INSTANCED_DIMENSIONS_MOD_ID
import dev.yourname.instanceddimensions.NETWORK_CHANNEL
import dev.yourname.instanceddimensions.compat.C2meCompat
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceState
import dev.yourname.instanceddimensions.engine.instance.InstanceTemplate
import dev.yourname.instanceddimensions.engine.instance.InstanceTemplateDataManager
import dev.yourname.instanceddimensions.engine.travel.TravelManager
import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.api.ObeliskApi
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.ObeliskDefinition
import dev.yourname.obelisks.data.RewardEntryDefinition
import dev.yourname.obelisks.data.RewardPoolDefinition
import dev.yourname.obelisks.data.RewardTableDefinition
import dev.yourname.obelisks.data.WorldgenFamilyDefinition
import dev.yourname.obelisks.registry.ModBlocks
import dev.yourname.obelisks.runtime.reward.RewardSystem
import dev.yourname.obelisks.runtime.run.RunRecord
import dev.yourname.obelisks.runtime.run.RunRegistry
import dev.yourname.obelisks.runtime.run.RunState
import dev.yourname.obelisks.runtime.run.RunSavedData
import dev.yourname.obelisks.runtime.ui.RunBossBarManager
import dev.yourname.obelisks.worldgen.ObeliskFeature
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.PacketListener
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.handshake.ClientIntentionPacket
import net.minecraft.network.protocol.login.ClientLoginPacketListener
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.network.NetworkHooks
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.net.SocketAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ObeliskGameTestSupport {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val runtimeChannel = ResourceLocation.fromNamespaceAndPath(INSTANCED_DIMENSIONS_MOD_ID, NETWORK_CHANNEL)
    private val memoryChannels = ConcurrentHashMap<net.minecraft.server.MinecraftServer, SocketAddress>()

    fun runCreationPersistsOwnedInstanceMetadata(helper: GameTestHelper) {
        val server = helper.level.server
        val run = RunRegistry.beginRun(server, UUID.randomUUID(), "overworld")

        waitUntil(helper, 40, "Expected run-backed instance to become ACTIVE", condition = {
            InstanceManager.getInstance(run.instanceId)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val activeRun = RunRegistry.get(run.runId)
            val activeInstance = InstanceManager.getInstance(run.instanceId)
            helper.assertTrue(activeRun != null, "Expected created run to be registered")
            helper.assertTrue(activeInstance?.ownerId == run.runId, "Expected runtime instance to be owned by the created run")
            helper.assertTrue(
                RunSavedData.get(server).snapshot().any { it.id == run.runId },
                "Expected run to persist in saved data while active"
            )
            helper.assertTrue(RunRegistry.finishRun(server, run.runId), "Expected run cleanup to be accepted")
            waitUntil(helper, 1200, failureMessage = {
                val runSnapshot = RunRegistry.get(run.runId)
                val instanceSnapshot = InstanceManager.getInstance(run.instanceId)
                val persistedRun = RunSavedData.get(server).snapshot().firstOrNull { it.id == run.runId }
                buildString {
                    append("Expected run cleanup to remove the run record and leave the runtime instance scheduled for teardown")
                    append(" | run=")
                    append(runSnapshot?.state ?: "null")
                    append(" instance=")
                    append(instanceSnapshot?.state ?: "null")
                    append(" levelLoaded=")
                    append(instanceSnapshot?.levelKey?.let(server::getLevel) != null)
                    append(" close=")
                    append(InstanceManager.describeCloseState(server, run.instanceId))
                    append(" savedRun=")
                    append(persistedRun?.state ?: "null")
                }
            }, condition = {
                RunRegistry.get(run.runId) == null &&
                    RunSavedData.get(server).snapshot().none { it.id == run.runId }
            }, onSuccess = {
                helper.assertTrue(RunRegistry.get(run.runId) == null, "Expected finished run to be removed from the registry")
                helper.assertTrue(
                    RunSavedData.get(server).snapshot().none { it.id == run.runId },
                    "Expected finished run to be removed from saved data"
                )
                helper.succeed()
            })
        })
    }

    fun chargedObeliskActivatesRunAndReturnsPlayer(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val originDimension = player.serverLevel().dimension()
        val originPos = player.blockPosition()
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 1))

        try {
            helper.level.setBlock(obeliskPos.below(), Blocks.OBSIDIAN.defaultBlockState(), 3)
            helper.level.setBlock(obeliskPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)

            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected placed obelisk block entity to exist")
            prepareTestObelisk(obelisk!!)
            obelisk.regenerateEnergy(obelisk.getMaxEnergyStored())

            val activationResult = ModBlocks.OBELISK.get().use(
                helper.level.getBlockState(obeliskPos),
                helper.level,
                obeliskPos,
                player,
                InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3.atCenterOf(obeliskPos), Direction.UP, obeliskPos, false)
            )
            helper.assertTrue(
                activationResult == InteractionResult.CONSUME || activationResult == InteractionResult.SUCCESS,
                "Expected charged obelisk activation to consume the interaction"
            )

            waitUntil(helper, 320, "Expected charged obelisk to create a run and move the player into its runtime instance", condition = {
                client.pump(server)
                val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                val run = activeRunId?.let(RunRegistry::get)
                val instance = run?.let { InstanceManager.getInstance(it.instanceId) }
                run?.spawnPos != null &&
                    run.state == dev.yourname.obelisks.runtime.run.RunState.ACTIVE &&
                    instance != null &&
                    player.serverLevel().dimension() == instance.levelKey
            }, onSuccess = {
                client.pump(server)
                val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                helper.assertTrue(liveObelisk != null, "Expected origin obelisk block entity to remain loaded")
                val runId = requireNotNull(liveObelisk!!.activeRunId) { "Expected active run id after activation" }
                val run = requireNotNull(RunRegistry.get(runId)) { "Expected run registry entry after activation" }
                val instance = requireNotNull(InstanceManager.getInstance(run.instanceId)) { "Expected instance handle after activation" }
                val runtimeLevel = requireNotNull(server.getLevel(instance.levelKey)) { "Expected runtime level to be loaded after activation" }
                val returnPadPos = requireNotNull(run.spawnPos).below()

                helper.assertTrue(runtimeLevel.getBlockState(returnPadPos).`is`(ModBlocks.RETURN_PAD.get()), "Expected spawn platform to place a return pad")
                assertGenericSpawnPlatform(helper, runtimeLevel, returnPadPos)
                helper.assertTrue(
                    player.serverLevel().dimension() == instance.levelKey,
                    "Expected activation to move the player into the runtime instance"
                )
                helper.assertTrue(
                    run.activePlayers.contains(player.uuid),
                    "Expected activated run to track the entering player as active"
                )

                val returnResult = ModBlocks.RETURN_PAD.get().use(
                    runtimeLevel.getBlockState(returnPadPos),
                    runtimeLevel,
                    returnPadPos,
                    player,
                    InteractionHand.MAIN_HAND,
                    BlockHitResult(Vec3.atCenterOf(returnPadPos), Direction.UP, returnPadPos, false)
                )
                helper.assertTrue(
                    returnResult == InteractionResult.CONSUME || returnResult == InteractionResult.SUCCESS,
                    "Expected return pad interaction to consume the return request"
                )

                waitUntil(helper, 120, "Expected return pad to move the player back to the origin dimension", condition = {
                    client.pump(server)
                    player.serverLevel().dimension() == originDimension
                }, onSuccess = {
                    helper.assertTrue(player.serverLevel().dimension() == originDimension, "Expected return pad to restore the origin dimension")
                    helper.assertTrue(
                        player.blockPosition().closerThan(originPos, 8.0),
                        "Expected return pad to restore the player near the origin obelisk"
                    )

                    waitUntil(helper, 360, failureMessage = {
                        val runSnapshot = RunRegistry.get(runId)
                        val instanceSnapshot = InstanceManager.getInstance(run.instanceId)
                        val currentObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                        buildString {
                            append("Expected empty run cleanup to unload the runtime instance and cooldown the origin obelisk")
                            append(" | run=")
                            append(runSnapshot?.state ?: "null")
                            append(" activePlayers=")
                            append(runSnapshot?.activePlayers?.size ?: -1)
                            append(" pendingPlayers=")
                            append(runSnapshot?.pendingPlayers?.size ?: -1)
                            append(" instance=")
                            append(instanceSnapshot?.state ?: "null")
                            append(" close=")
                            append(InstanceManager.describeCloseState(server, run.instanceId))
                            append(" obeliskActiveRun=")
                            append(currentObelisk?.activeRunId)
                            append(" cooldown=")
                            append(currentObelisk?.isOnCooldown())
                            append(" playerDim=")
                            append(player.serverLevel().dimension().location())
                        }
                    }, condition = {
                        client.pump(server)
                        RunRegistry.get(runId) == null &&
                            (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId == null &&
                            ((helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.isOnCooldown() == true)
                    }, onSuccess = {
                        val cooledObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                        helper.assertTrue(cooledObelisk?.activeRunId == null, "Expected finished obelisk run to clear the active run id")
                        helper.assertTrue(cooledObelisk?.isOnCooldown() == true, "Expected finished obelisk run to start cooldown")
                        helper.assertTrue(RunRegistry.get(runId) == null, "Expected finished obelisk run to be removed from the registry")
                        client.close(server)
                        helper.succeed()
                    })
                })
            })
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun relocatedSpawnRetargetsTravelWarmup(helper: GameTestHelper) {
        val server = helper.level.server
        val initialWarmupDeadline = server.overworld().gameTime + C2meCompat.warmupTicketTicks() + 1L
        val run = RunRegistry.beginRun(server, UUID.randomUUID(), "end")

        waitUntil(helper, 240, "Expected end run to prepare its relocated spawn platform", condition = {
            RunRegistry.get(run.runId)?.spawnPos != null
        }, onSuccess = {
            val recordAfterPlatform = requireNotNull(RunRegistry.get(run.runId)) { "Expected run record after platform generation" }
            val instance = requireNotNull(InstanceManager.getInstance(recordAfterPlatform.instanceId)) { "Expected active instance for end run" }
            val runtimeLevel = requireNotNull(server.getLevel(instance.levelKey)) { "Expected end runtime level to be loaded" }
            val spawnPos = requireNotNull(recordAfterPlatform.spawnPos) { "Expected relocated spawn position" }

            val delayUntilInitialWarmupExpires = (initialWarmupDeadline - server.overworld().gameTime).coerceAtLeast(1L)
            helper.runAfterDelay(delayUntilInitialWarmupExpires) {
                helper.assertTrue(
                    !InstanceManager.isTravelReady(recordAfterPlatform.instanceId),
                    "Expected relocated spawn platform to keep travel warmup active beyond the original instance warmup window"
                )

                waitUntil(helper, 120, "Expected relocated spawn warmup to finish after retargeting", condition = {
                    InstanceManager.isTravelReady(recordAfterPlatform.instanceId)
                }, onSuccess = {
                    helper.assertTrue(
                        runtimeLevel.chunkSource.getChunkNow(spawnPos.x shr 4, spawnPos.z shr 4) != null,
                        "Expected relocated spawn chunk to remain loaded when the instance becomes travel-ready"
                    )

                    helper.assertTrue(RunRegistry.finishRun(server, run.runId), "Expected relocated spawn test cleanup to finish the run")
                    waitUntil(helper, 1200, failureMessage = {
                        val runSnapshot = RunRegistry.get(run.runId)
                        val instanceSnapshot = InstanceManager.getInstance(run.instanceId)
                        buildString {
                            append("Expected relocated spawn test cleanup to remove the run and background-destroy its instance")
                            append(" | run=")
                            append(runSnapshot?.state ?: "null")
                            append(" instance=")
                            append(instanceSnapshot?.state ?: "null")
                            append(" close=")
                            append(InstanceManager.describeCloseState(server, run.instanceId))
                        }
                    }, condition = {
                        RunRegistry.get(run.runId) == null
                    }, onSuccess = {
                        helper.succeed()
                    })
                })
            }
        })
    }

    fun successfulRunBuffersRewardsAndShowsBossBar(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 4))
        val originDimension = player.serverLevel().dimension()

        try {
            helper.level.setBlock(obeliskPos.below(), Blocks.OBSIDIAN.defaultBlockState(), 3)
            helper.level.setBlock(obeliskPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)
            helper.level.setBlock(obeliskPos.east(), Blocks.HOPPER.defaultBlockState(), 3)

            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected placed obelisk block entity to exist")
            prepareTestObelisk(obelisk!!)
            obelisk.regenerateEnergy(obelisk.getMaxEnergyStored())

            val activationMessage = RunRegistry.activateObelisk(player, obelisk, obeliskPos)
            helper.assertTrue(
                activationMessage?.startsWith("Initializing") == true,
                "Expected reward test activation to initialize a run, got: $activationMessage"
            )

            waitUntil(helper, 120, "Expected reward test obelisk to create an active run id", condition = {
                client.pump(server)
                val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                activeRunId != null && RunRegistry.get(activeRunId) != null
            }, onSuccess = {
                val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                helper.assertTrue(liveObelisk != null, "Expected live obelisk block entity after reward-test activation")
                val runId = requireNotNull(liveObelisk!!.activeRunId) { "Expected active run id for reward test" }

                waitUntil(helper, 120, "Expected reward test run to allocate a runtime instance", condition = {
                    client.pump(server)
                    val run = RunRegistry.get(runId)
                    val instance = run?.let { InstanceManager.getInstance(it.instanceId) }
                    instance?.state == InstanceState.ACTIVE
                }, onSuccess = {
                    waitUntil(helper, 240, "Expected reward test run to build its spawn platform", condition = {
                        client.pump(server)
                        RunRegistry.get(runId)?.spawnPos != null
                    }, onSuccess = {
                        client.pump(server)
                        val run = requireNotNull(RunRegistry.get(runId)) { "Expected active run entry for reward test" }
                        val instance = requireNotNull(InstanceManager.getInstance(run.instanceId)) { "Expected runtime instance for reward test" }
                        val runtimeLevel = requireNotNull(server.getLevel(instance.levelKey)) { "Expected runtime level for reward test" }
                        val returnPadPos = requireNotNull(run.spawnPos).below()
                        assertGenericSpawnPlatform(helper, runtimeLevel, returnPadPos)

                        waitUntil(helper, 120, "Expected reward test player to enter the initialized runtime instance", condition = {
                            client.pump(server)
                            player.serverLevel().dimension() == instance.levelKey &&
                                RunRegistry.get(runId)?.activePlayers?.contains(player.uuid) == true
                        }, onSuccess = {
                            liveObelisk.drainEnergy((liveObelisk.getMaxEnergyStored() * 0.15).toInt().coerceAtLeast(1))
                            RunRegistry.recordDamage(player.uuid, instance.levelKey, 40f)

                            waitUntil(helper, 80, "Expected low stability to create a boss bar for the run", condition = {
                                RunBossBarManager.hasBossBar(runId)
                            }, onSuccess = {
                                ModBlocks.RETURN_PAD.get().use(
                                    runtimeLevel.getBlockState(returnPadPos),
                                    runtimeLevel,
                                    returnPadPos,
                                    player,
                                    InteractionHand.MAIN_HAND,
                                    BlockHitResult(Vec3.atCenterOf(returnPadPos), Direction.UP, returnPadPos, false)
                                )

                                waitUntil(helper, 120, "Expected reward test return pad to move the player back to the origin dimension", condition = {
                                    client.pump(server)
                                    player.serverLevel().dimension() == originDimension
                                }, onSuccess = {
                                    waitUntil(helper, 360, failureMessage = {
                                        val runSnapshot = RunRegistry.get(runId)
                                        val instanceSnapshot = InstanceManager.getInstance(run.instanceId)
                                        val currentObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                                        buildString {
                                            append("Expected successful run cleanup to buffer emerald rewards and clear the boss bar")
                                            append(" | emeralds=")
                                            append(emeraldCount(currentObelisk))
                                            append(" run=")
                                            append(runSnapshot?.state ?: "null")
                                            append(" instance=")
                                            append(instanceSnapshot?.state ?: "null")
                                            append(" close=")
                                            append(InstanceManager.describeCloseState(server, run.instanceId))
                                            append(" bossBar=")
                                            append(RunBossBarManager.hasBossBar(runId))
                                            append(" cooldown=")
                                            append(currentObelisk?.isOnCooldown())
                                        }
                                    }, condition = {
                                        client.pump(server)
                                        emeraldCount(helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity) >= 2 &&
                                            RunRegistry.get(runId) == null &&
                                            !RunBossBarManager.hasBossBar(runId)
                                    }, onSuccess = {
                                        val cooledObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                                        helper.assertTrue(
                                            emeraldCount(cooledObelisk) >= 2,
                                            "Expected successful run to buffer emerald rewards inside the origin obelisk"
                                        )
                                        helper.assertTrue(!RunBossBarManager.hasBossBar(runId), "Expected finished run to clear its boss bar")
                                        client.close(server)
                                        helper.succeed()
                                    })
                                })
                            })
                        })
                    })
                })
            })
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun voidFallReturnsPlayerAndCleansUpRun(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val originDimension = player.serverLevel().dimension()
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 7))

        try {
            helper.level.setBlock(obeliskPos.below(), Blocks.OBSIDIAN.defaultBlockState(), 3)
            helper.level.setBlock(obeliskPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)

            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected placed obelisk block entity to exist")
            prepareTestObelisk(obelisk!!)
            obelisk.regenerateEnergy(obelisk.getMaxEnergyStored())

            val activationResult = ModBlocks.OBELISK.get().use(
                helper.level.getBlockState(obeliskPos),
                helper.level,
                obeliskPos,
                player,
                InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3.atCenterOf(obeliskPos), Direction.UP, obeliskPos, false)
            )
            helper.assertTrue(
                activationResult == InteractionResult.CONSUME || activationResult == InteractionResult.SUCCESS,
                "Expected void-fall test activation to consume the interaction"
            )

            waitUntil(helper, 320, "Expected void-fall test to create an active run and move the player into its instance", condition = {
                client.pump(server)
                val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                val run = activeRunId?.let(RunRegistry::get)
                val instance = run?.let { InstanceManager.getInstance(it.instanceId) }
                run?.spawnPos != null &&
                    run.state == dev.yourname.obelisks.runtime.run.RunState.ACTIVE &&
                    instance != null &&
                    player.serverLevel().dimension() == instance.levelKey
            }, onSuccess = {
                client.pump(server)
                val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                helper.assertTrue(liveObelisk != null, "Expected live obelisk block entity for void-fall test")
                val runId = requireNotNull(liveObelisk!!.activeRunId) { "Expected active run id for void-fall test" }
                val returnAnchor = requireNotNull(TravelManager.peekReturnAnchor(player.uuid)) { "Expected return anchor before void fall" }

                player.teleportTo(player.x, -80.0, player.z)

                waitUntil(helper, 120, "Expected void fall to return the player to the saved origin dimension", condition = {
                    client.pump(server)
                    player.serverLevel().dimension() == originDimension
                }, onSuccess = {
                    helper.assertTrue(player.serverLevel().dimension() == originDimension, "Expected void fall to restore the origin dimension")
                    val actualX = player.x
                    val actualY = player.y
                    val actualZ = player.z
                    helper.assertTrue(
                        horizontalDistanceSqr(actualX, actualZ, returnAnchor.x, returnAnchor.z) <= 64.0,
                        "Expected void fall to restore the player near the saved return anchor; actual=($actualX, $actualY, $actualZ) anchor=(${returnAnchor.x}, ${returnAnchor.y}, ${returnAnchor.z})"
                    )

                    waitUntil(helper, 360, "Expected void-fall cleanup to cooldown the origin obelisk and clear the run", condition = {
                        client.pump(server)
                        RunRegistry.get(runId) == null &&
                            (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId == null &&
                            ((helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.isOnCooldown() == true)
                    }, onSuccess = {
                        val cooledObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                        helper.assertTrue(cooledObelisk?.activeRunId == null, "Expected void-fall cleanup to clear the active run id")
                        helper.assertTrue(cooledObelisk?.isOnCooldown() == true, "Expected void-fall cleanup to start cooldown")
                        helper.assertTrue(RunRegistry.get(runId) == null, "Expected void-fall cleanup to remove the run")
                        client.close(server)
                        helper.succeed()
                    })
                })
            })
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun rewardTablesFollowDefinitionWithSharedTemplate(helper: GameTestHelper) {
        deleteTestConfigs()
        val server = helper.level.server
        val definitionA = ObeliskDefinition(
            id = "test_shared_template_a",
            displayName = "Shared Template A",
            instanceTemplateId = "end",
            rewardTableId = "test_shared_rewards_a"
        )
        val definitionB = ObeliskDefinition(
            id = "test_shared_template_b",
            displayName = "Shared Template B",
            instanceTemplateId = "end",
            rewardTableId = "test_shared_rewards_b"
        )
        writeDefinition(definitionA)
        writeDefinition(definitionB)
        writeRewardTable(
            RewardTableDefinition(
                id = "test_shared_rewards_a",
                baseRolls = 1,
                damagePerBonusRoll = 9999.0f,
                pools = listOf(poolOf("a", "minecraft:iron_ingot"))
            )
        )
        writeRewardTable(
            RewardTableDefinition(
                id = "test_shared_rewards_b",
                baseRolls = 1,
                damagePerBonusRoll = 9999.0f,
                pools = listOf(poolOf("b", "minecraft:gold_ingot"))
            )
        )
        reloadDataWithCommand(server)

        val obeliskPosA = helper.absolutePos(BlockPos(4, 2, 10))
        val obeliskPosB = helper.absolutePos(BlockPos(8, 2, 10))
        placeChargedDefinitionObelisk(helper, obeliskPosA, definitionA.id)
        placeChargedDefinitionObelisk(helper, obeliskPosB, definitionB.id)
        helper.level.setBlock(obeliskPosA.east(), Blocks.HOPPER.defaultBlockState(), 3)
        helper.level.setBlock(obeliskPosB.east(), Blocks.HOPPER.defaultBlockState(), 3)

        val runA = rewardOnlyRun(helper, obeliskPosA, definitionA)
        val runB = rewardOnlyRun(helper, obeliskPosB, definitionB)
        helper.assertTrue(RewardSystem.spawnRewards(server, runA), "Expected definition A reward spawn to succeed")
        helper.assertTrue(RewardSystem.spawnRewards(server, runB), "Expected definition B reward spawn to succeed")

        val obeliskA = helper.level.getBlockEntity(obeliskPosA) as? ObeliskBlockEntity
        val obeliskB = helper.level.getBlockEntity(obeliskPosB) as? ObeliskBlockEntity
        helper.assertTrue(countBufferedItem(obeliskA, Items.IRON_INGOT) > 0, "Expected definition A to use its iron reward table")
        helper.assertTrue(countBufferedItem(obeliskA, Items.GOLD_INGOT) == 0, "Expected definition A buffer to avoid definition B rewards")
        helper.assertTrue(countBufferedItem(obeliskB, Items.GOLD_INGOT) > 0, "Expected definition B to use its gold reward table")
        helper.assertTrue(countBufferedItem(obeliskB, Items.IRON_INGOT) == 0, "Expected definition B buffer to avoid definition A rewards")
        deleteTestConfigs()
        reloadData()
        helper.succeed()
    }

    fun reloadCommandRefreshesDefinitionData(helper: GameTestHelper) {
        deleteTestConfigs()
        val server = helper.level.server
        val definitionId = "test_reloadable_definition"
        val baseDefinition = ObeliskDefinition(
            id = definitionId,
            displayName = "Reload One",
            instanceTemplateId = "overworld",
            rewardTableId = "overworld"
        )
        writeDefinition(baseDefinition)
        reloadDataWithCommand(server)
        helper.assertTrue(
            ObeliskApi.getDefinition(definitionId)?.displayName == "Reload One",
            "Expected reload command to load the initial test definition"
        )

        writeDefinition(baseDefinition.copy(displayName = "Reload Two", rewardTableId = "end"))
        reloadDataWithCommand(server)
        val reloaded = ObeliskApi.getDefinition(definitionId)
        helper.assertTrue(reloaded?.displayName == "Reload Two", "Expected reload command to refresh the definition display name")
        helper.assertTrue(reloaded?.rewardTableId == "end", "Expected reload command to refresh the definition reward table id")
        deleteTestConfigs()
        reloadDataWithCommand(server)
        helper.succeed()
    }

    fun worldgenDefinitionsProduceCanonicalMeteorSites(helper: GameTestHelper) {
        deleteTestConfigs()
        val endDefinition = ObeliskDefinition(
            id = "test_end_visual_definition",
            displayName = "Test End Visual",
            instanceTemplateId = "end",
            rewardTableId = "end"
        )
        val netherDefinition = ObeliskDefinition(
            id = "test_nether_visual_definition",
            displayName = "Test Nether Visual",
            instanceTemplateId = "nether",
            rewardTableId = "nether"
        )
        writeDefinition(endDefinition)
        writeDefinition(netherDefinition)
        reloadData()

        val endCenter = helper.absolutePos(BlockPos(20, 3, 4))
        val netherCenter = helper.absolutePos(BlockPos(36, 3, 4))
        prepareGenerationSurface(helper, endCenter)
        prepareGenerationSurface(helper, netherCenter)

        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, endCenter, endDefinition.id, RandomSource.create(1234L)),
            "Expected end definition test generation to succeed"
        )
        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, netherCenter, netherDefinition.id, RandomSource.create(5678L)),
            "Expected nether definition test generation to succeed"
        )

        val endPos = endCenter.below()
        val netherPos = netherCenter.below()
        val endObelisk = helper.level.getBlockEntity(endPos) as? ObeliskBlockEntity
        val netherObelisk = helper.level.getBlockEntity(netherPos) as? ObeliskBlockEntity
        helper.assertTrue(endObelisk?.definitionId == endDefinition.id, "Expected generated end obelisk to keep its definition id")
        helper.assertTrue(netherObelisk?.definitionId == netherDefinition.id, "Expected generated nether obelisk to keep its definition id")
        helper.assertTrue(countNonAirRing(helper, endPos, 2) >= 6, "Expected end definition to generate as a canonical meteor")
        helper.assertTrue(countNonAirRing(helper, netherPos, 2) >= 6, "Expected nether definition to generate as a canonical meteor")
        helper.assertTrue(helper.level.getBlockState(endPos.east()).`is`(Blocks.STONE), "Expected generated end meteor to use stone")
        helper.assertTrue(helper.level.getBlockState(netherPos.east()).`is`(Blocks.STONE), "Expected generated nether meteor to use stone")
        deleteTestConfigs()
        reloadData()
        helper.succeed()
    }

    fun reloadSkipsDefinitionsWithMissingRequiredNamespace(helper: GameTestHelper) {
        deleteTestConfigs()
        val server = helper.level.server
        val missingId = "test_missing_namespace_definition"
        val presentId = "test_present_namespace_definition"

        writeDefinition(
            ObeliskDefinition(
                id = missingId,
                displayName = "Missing Namespace",
                instanceTemplateId = "overworld",
                requiredNamespace = "missing_test_namespace",
                rewardTableId = "overworld"
            )
        )
        writeDefinition(
            ObeliskDefinition(
                id = presentId,
                displayName = "Present Namespace",
                instanceTemplateId = "overworld",
                requiredNamespace = "minecraft",
                rewardTableId = "overworld"
            )
        )

        reloadDataWithCommand(server)
        helper.assertTrue(
            ObeliskApi.getDefinition(missingId) == null,
            "Expected reload to skip definitions whose required namespace is unavailable"
        )
        helper.assertTrue(
            ObeliskApi.getDefinition(presentId)?.displayName == "Present Namespace",
            "Expected reload to keep definitions whose required namespace is available"
        )

        deleteTestConfigs()
        reloadDataWithCommand(server)
        helper.succeed()
    }

    fun reloadSkipsDefinitionsWithMissingInstanceTemplate(helper: GameTestHelper) {
        deleteTestConfigs()
        val server = helper.level.server
        val missingId = "test_missing_template_definition"
        val presentId = "test_present_template_definition"

        writeDefinition(
            ObeliskDefinition(
                id = missingId,
                displayName = "Missing Template",
                instanceTemplateId = "missing_runtime_template",
                rewardTableId = "overworld"
            )
        )
        writeDefinition(
            ObeliskDefinition(
                id = presentId,
                displayName = "Present Template",
                instanceTemplateId = "end",
                rewardTableId = "overworld"
            )
        )

        reloadDataWithCommand(server)
        helper.assertTrue(
            ObeliskApi.getDefinition(missingId) == null,
            "Expected reload to skip definitions whose instance template is unavailable"
        )
        helper.assertTrue(
            ObeliskApi.getDefinition(presentId)?.displayName == "Present Template",
            "Expected reload to keep definitions whose instance template is available"
        )

        deleteTestConfigs()
        reloadDataWithCommand(server)
        helper.succeed()
    }

    fun instanceTemplateIdSelectsRuntimeInstanceTemplate(helper: GameTestHelper) {
        deleteTestConfigs()
        deleteTestInstanceTemplates()
        val server = helper.level.server
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 16))
        val definition = ObeliskDefinition(
            id = "test_instance_template_mapping",
            displayName = "Template Mapping",
            instanceTemplateId = "end",
            rewardTableId = "end"
        )
        writeDefinition(definition)
        reloadDataWithCommand(server)
        placeChargedDefinitionObelisk(helper, obeliskPos, definition.id)
        val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
        helper.assertTrue(obelisk != null, "Expected placed obelisk block entity for template-mapping test")
        helper.assertTrue(obelisk!!.targetTemplateId == "end", "Expected obelisk definition to resolve target template id to end")
        val template = requireNotNull(InstanceManager.getTemplate(obelisk.targetTemplateId)) {
            "Expected resolved obelisk template ${obelisk.targetTemplateId} to be registered"
        }
        helper.assertTrue(template.id == "end", "Expected resolved template id to remain end")
        helper.assertTrue(template.stem == "minecraft:the_end", "Expected end template stem to target minecraft:the_end")
        helper.assertTrue(template.requiredNamespace == "minecraft", "Expected built-in end template namespace requirement to remain minecraft")

        deleteTestConfigs()
        deleteTestInstanceTemplates()
        reloadDataWithCommand(server)
        InstanceManager.reloadTemplates()
        helper.succeed()
    }

    fun runtimeIncompatibleTemplateFailsObeliskActivationCleanly(helper: GameTestHelper) {
        deleteTestConfigs()
        deleteTestInstanceTemplates()
        val server = helper.level.server
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 19))
        val templateId = "test_runtime_incompatible_template"
        val definitionId = "test_runtime_incompatible_definition"
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val originDimension = player.serverLevel().dimension()

        writeInstanceTemplate(
            InstanceTemplate(
                id = templateId,
                stem = "examplemod:test_dimension",
                requiredNamespace = "minecraft",
                runtimeCompatible = false,
                description = "Should fail before runtime world creation"
            )
        )
        writeDefinition(
            ObeliskDefinition(
                id = definitionId,
                displayName = "Blocked Runtime Template",
                instanceTemplateId = templateId,
                rewardTableId = "default"
            )
        )

        try {
            InstanceManager.reloadTemplates()
            reloadDataWithCommand(server)
            placeChargedDefinitionObelisk(helper, obeliskPos, definitionId)
            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected placed obelisk block entity for runtime-incompatible template test")

            val message = RunRegistry.activateObelisk(player, obelisk!!, obeliskPos)
            helper.assertTrue(
                message?.startsWith("Cannot initialize Blocked Runtime Template run:") == true,
                "Expected incompatible template activation to fail immediately, got '$message'"
            )
            helper.assertTrue(
                message?.contains("disabled for template '$templateId'") == true,
                "Expected incompatible template activation message to explain the runtime block, got '$message'"
            )
            helper.assertTrue(obelisk.activeRunId == null, "Expected failed activation to leave the obelisk without an active run")
            helper.assertTrue(
                RunRegistry.snapshot().none { it.obeliskId == obelisk.obeliskId },
                "Expected failed activation to avoid registering a run"
            )
            helper.assertTrue(
                player.serverLevel().dimension() == originDimension,
                "Expected failed activation to keep the player in the origin dimension"
            )
            helper.succeed()
        } finally {
            client.close(server)
            deleteTestConfigs()
            deleteTestInstanceTemplates()
            reloadDataWithCommand(server)
            InstanceManager.reloadTemplates()
        }
    }

    fun secondPlayerJoinsExistingRunAndBothReturn(helper: GameTestHelper) {
        val server = helper.level.server
        val clientA = connectHeadlessPlayer(helper)
        val clientB = connectHeadlessPlayer(helper)
        val playerA = clientA.player
        val playerB = clientB.player
        val originDimension = playerA.serverLevel().dimension()
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 13))

        try {
            placeChargedDefinitionObelisk(helper, obeliskPos, "end")
            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected multiplayer test obelisk to exist")

            val messageA = RunRegistry.activateObelisk(playerA, obelisk!!, obeliskPos)
            helper.assertTrue(messageA?.startsWith("Initializing") == true, "Expected first player to initialize a run")

            waitUntil(helper, 320, "Expected first player to enter the runtime instance", condition = {
                clientA.pump(server)
                val runId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                val run = runId?.let(RunRegistry::get)
                val instance = run?.let { InstanceManager.getInstance(it.instanceId) }
                run?.activePlayers?.contains(playerA.uuid) == true && instance != null && playerA.serverLevel().dimension() == instance.levelKey
            }, onSuccess = {
                val activeRunId = requireNotNull((helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId)
                val activeRun = requireNotNull(RunRegistry.get(activeRunId))
                val instance = requireNotNull(InstanceManager.getInstance(activeRun.instanceId))
                val joinMessage = RunRegistry.activateObelisk(playerB, obelisk, obeliskPos)
                helper.assertTrue(joinMessage?.startsWith("Joining active") == true, "Expected second player to join the existing run")

                waitUntil(helper, 240, "Expected both players to share the same active runtime instance", condition = {
                    clientA.pump(server)
                    clientB.pump(server)
                    val run = RunRegistry.get(activeRunId)
                    run?.activePlayers?.containsAll(listOf(playerA.uuid, playerB.uuid)) == true &&
                        playerA.serverLevel().dimension() == instance.levelKey &&
                        playerB.serverLevel().dimension() == instance.levelKey
                }, onSuccess = {
                    helper.assertTrue(TravelManager.returnPlayer(playerA), "Expected first player return to succeed")
                    helper.assertTrue(TravelManager.returnPlayer(playerB), "Expected second player return to succeed")
                    waitUntil(helper, 320, "Expected both players to return and the shared run to clean up", condition = {
                        clientA.pump(server)
                        clientB.pump(server)
                        playerA.serverLevel().dimension() == originDimension &&
                            playerB.serverLevel().dimension() == originDimension &&
                            RunRegistry.get(activeRunId) == null &&
                            (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId == null
                    }, onSuccess = {
                        clientA.close(server)
                        clientB.close(server)
                        helper.succeed()
                    })
                })
            })
        } catch (t: Throwable) {
            clientA.close(server)
            clientB.close(server)
            throw t
        }
    }

    fun waitUntil(
        helper: GameTestHelper,
        remainingTicks: Int,
        failureMessage: String,
        condition: () -> Boolean,
        onSuccess: () -> Unit
    ) {
        waitUntil(helper, remainingTicks, { failureMessage }, condition, onSuccess)
    }

    fun waitUntil(
        helper: GameTestHelper,
        remainingTicks: Int,
        failureMessage: () -> String,
        condition: () -> Boolean,
        onSuccess: () -> Unit
    ) {
        if (condition()) {
            onSuccess()
            return
        }
        if (remainingTicks <= 0) {
            helper.fail(failureMessage())
            return
        }
        helper.runAfterDelay(1) {
            waitUntil(helper, remainingTicks - 1, failureMessage, condition, onSuccess)
        }
    }

    private fun connectHeadlessPlayer(helper: GameTestHelper): ConnectedTestClient {
        val server = helper.level.server
        val serverConnectionListener = requireNotNull(server.connection) { "Expected server connection listener to be available" }
        val existingConnections = serverConnectionListener.connections.toSet()
        val address = memoryChannels.computeIfAbsent(server) { serverConnectionListener.startMemoryChannel() }
        val clientConnection = Connection.connectToLocalServer(address)
        val recorder = HeadlessClientRecorder()
        clientConnection.setListener(recorder.listener)
        serverConnectionListener.tick()

        val serverConnection = serverConnectionListener.connections.firstOrNull { it !in existingConnections && it.isConnected }
            ?: error("Expected a new memory-channel server connection")
        NetworkHooks.registerServerLoginChannel(serverConnection, ClientIntentionPacket("localhost", 0, ConnectionProtocol.LOGIN))

        val player = server.playerList.getPlayerForLogin(GameProfile(UUID.randomUUID(), "test-obelisk-player"))
        val spawn = helper.absolutePos(BlockPos(1, 2, 1))
        player.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0.0F, 0.0F)
        server.playerList.placeNewPlayer(serverConnection, player)
        recorder.pump(clientConnection)
        return ConnectedTestClient(player, clientConnection, recorder)
    }

    private fun emeraldCount(obelisk: ObeliskBlockEntity?): Int {
        if (obelisk == null) return 0
        val handler = obelisk.getInternalItemHandler()
        var count = 0
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.`is`(Items.EMERALD)) {
                count += stack.count
            }
        }
        return count
    }

    private fun assertGenericSpawnPlatform(helper: GameTestHelper, level: Level, returnPadPos: BlockPos) {
        helper.assertTrue(level.getBlockState(returnPadPos).`is`(ModBlocks.RETURN_PAD.get()), "Expected generic spawn platform center to be a return pad")

        val floorCenter = returnPadPos.below()
        for (x in -1..1) {
            for (z in -1..1) {
                if (x == 0 && z == 0) {
                    continue
                }
                val framePos = returnPadPos.offset(x, 0, z)
                helper.assertTrue(
                    level.getBlockState(framePos).`is`(Blocks.OBSIDIAN),
                    "Expected generic spawn platform frame to use obsidian at $framePos"
                )
            }
        }

        for (x in -ObeliskConstants.PLATFORM_RADIUS..ObeliskConstants.PLATFORM_RADIUS) {
            for (z in -ObeliskConstants.PLATFORM_RADIUS..ObeliskConstants.PLATFORM_RADIUS) {
                val floorPos = floorCenter.offset(x, 0, z)
                helper.assertTrue(
                    level.getBlockState(floorPos).`is`(Blocks.BEDROCK),
                    "Expected generic spawn platform floor to use bedrock at $floorPos"
                )

                var supportPos = floorPos.below()
                while (supportPos.y >= level.minBuildHeight && level.getBlockState(supportPos).`is`(Blocks.BEDROCK)) {
                    supportPos = supportPos.below()
                }

                helper.assertTrue(
                    supportPos.y < level.minBuildHeight || level.getBlockState(supportPos).isSolidRender(level, supportPos),
                    "Expected bedrock-supported spawn platform column beneath $floorPos, found ${level.getBlockState(supportPos)} at $supportPos"
                )
            }
        }
    }

    private fun countBufferedItem(obelisk: ObeliskBlockEntity?, item: net.minecraft.world.item.Item): Int {
        if (obelisk == null) return 0
        val handler = obelisk.getInternalItemHandler()
        var count = 0
        for (slot in 0 until handler.slots) {
            val stack = handler.getStackInSlot(slot)
            if (stack.`is`(item)) {
                count += stack.count
            }
        }
        return count
    }

    private fun rewardOnlyRun(helper: GameTestHelper, obeliskPos: BlockPos, definition: ObeliskDefinition): RunRecord {
        val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            ?: error("Missing obelisk at $obeliskPos")
        return RunRecord(
            id = UUID.randomUUID(),
            instanceId = UUID.randomUUID(),
            obeliskId = obelisk.obeliskId,
            definitionId = definition.id,
            instanceTemplateId = definition.instanceTemplateId,
            originLevelKey = helper.level.dimension(),
            originObeliskPos = obeliskPos,
            totalDamageDealt = 40f,
            state = RunState.FINISHING
        )
    }

    private fun horizontalDistanceSqr(ax: Double, az: Double, bx: Double, bz: Double): Double {
        val dx = ax - bx
        val dz = az - bz
        return (dx * dx) + (dz * dz)
    }

    private fun prepareTestObelisk(obelisk: ObeliskBlockEntity) {
        obelisk.setActiveRun(null)
        obelisk.cooldownUntilGameTime = 0L
        obelisk.setTargetTemplate("end")
        val handler = obelisk.getInternalItemHandler()
        for (slot in 0 until handler.slots) {
            handler.setStackInSlot(slot, ItemStack.EMPTY)
        }
    }

    private fun placeChargedDefinitionObelisk(helper: GameTestHelper, pos: BlockPos, definitionId: String) {
        helper.level.setBlock(pos.below(), Blocks.OBSIDIAN.defaultBlockState(), 3)
        helper.level.setBlock(pos, ModBlocks.OBELISK.get().defaultBlockState(), 3)
        val obelisk = helper.level.getBlockEntity(pos) as? ObeliskBlockEntity
            ?: error("Expected placed obelisk block entity at $pos")
        obelisk.setDefinition(definitionId)
        obelisk.cooldownUntilGameTime = 0L
        obelisk.setActiveRun(null)
        obelisk.fillToCapacity()
        val handler = obelisk.getInternalItemHandler()
        for (slot in 0 until handler.slots) {
            handler.setStackInSlot(slot, ItemStack.EMPTY)
        }
    }

    private fun prepareGenerationSurface(helper: GameTestHelper, center: BlockPos) {
        for (dx in -8..8) {
            for (dz in -8..8) {
                helper.level.setBlock(center.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), 3)
                helper.level.setBlock(center.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), 3)
                helper.level.setBlock(center.offset(dx, 0, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 3)
                for (dy in 1..8) {
                    helper.level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }
    }

    private fun countNonAirColumn(helper: GameTestHelper, start: BlockPos, height: Int): Int {
        var count = 0
        for (dy in 0 until height) {
            if (!helper.level.getBlockState(start.above(dy)).isAir) {
                count++
            }
        }
        return count
    }

    private fun countNonAirRing(helper: GameTestHelper, center: BlockPos, radius: Int): Int {
        var count = 0
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (dx == 0 && dz == 0) continue
                if (kotlin.math.abs(dx) != radius && kotlin.math.abs(dz) != radius) continue
                if (!helper.level.getBlockState(center.offset(dx, 0, dz)).isAir) {
                    count++
                }
            }
        }
        return count
    }

    private fun writeDefinition(definition: ObeliskDefinition) {
        writeJsonFile(ObeliskDataManager.definitionsPath().resolve("${definition.id}.json"), definition)
    }

    private fun writeInstanceTemplate(template: InstanceTemplate) {
        writeJsonFile(InstanceTemplateDataManager.templatesPath().resolve("${template.id}.json"), template)
    }

    private fun writeRewardTable(table: RewardTableDefinition) {
        writeJsonFile(ObeliskDataManager.rewardsPath().resolve("${table.id}.json"), table)
    }

    @Suppress("unused")
    private fun writeWorldgenFamily(family: WorldgenFamilyDefinition) {
        writeJsonFile(ObeliskDataManager.worldgenFamiliesPath().resolve("${family.id}.json"), family)
    }

    private fun writeJsonFile(path: Path, value: Any) {
        Files.createDirectories(path.parent)
        Files.writeString(path, gson.toJson(value))
    }

    private fun deleteTestConfigs() {
        deleteMatching(ObeliskDataManager.definitionsPath(), "test_")
        deleteMatching(ObeliskDataManager.rewardsPath(), "test_")
        deleteMatching(ObeliskDataManager.worldgenFamiliesPath(), "test_")
    }

    private fun deleteTestInstanceTemplates() {
        deleteMatching(InstanceTemplateDataManager.templatesPath(), "test_")
    }

    private fun deleteMatching(dir: Path, prefix: String) {
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { paths ->
            paths
                .filter { path -> path.fileName.toString().startsWith(prefix) && path.fileName.toString().endsWith(".json") }
                .forEach(Files::deleteIfExists)
        }
    }

    private fun reloadData() {
        ObeliskDataManager.reload()
    }

    private fun reloadDataWithCommand(server: net.minecraft.server.MinecraftServer) {
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "obelisk reload_data")
    }

    private fun poolOf(id: String, item: String): RewardPoolDefinition {
        return RewardPoolDefinition(
            id = id,
            chance = 1.0,
            entries = listOf(RewardEntryDefinition(item = item, minCount = 1, maxCount = 1, weight = 1))
        )
    }

    private class ConnectedTestClient(
        val player: ServerPlayer,
        private val clientConnection: Connection,
        private val recorder: HeadlessClientRecorder
    ) {
        fun pump(server: net.minecraft.server.MinecraftServer) {
            requireNotNull(server.connection) { "Expected server connection listener to be available" }.tick()
            recorder.pump(clientConnection)
        }

        fun close(server: net.minecraft.server.MinecraftServer) {
            if (server.playerList.players.contains(player)) {
                server.playerList.remove(player)
            }
            clientConnection.disconnect(Component.literal("test complete"))
            pump(server)
        }
    }

    private class HeadlessClientRecorder {
        val knownLevels: MutableSet<ResourceKey<Level>> =
            Collections.newSetFromMap(ConcurrentHashMap<ResourceKey<Level>, Boolean>())
        val customPayloadChannels = CopyOnWriteArrayList<ResourceLocation>()
        val loginDimensions = CopyOnWriteArrayList<ResourceKey<Level>>()
        val respawnDimensions = CopyOnWriteArrayList<ResourceKey<Level>>()

        val listener: PacketListener = (Proxy.newProxyInstance(
            ClientGamePacketListener::class.java.classLoader,
            arrayOf(ClientLoginPacketListener::class.java, ClientGamePacketListener::class.java)
        ) { _, method, args ->
            when (method.name) {
                "isAcceptingMessages" -> true
                "onDisconnect" -> null
                "handleLogin" -> {
                    val packet = args!![0] as ClientboundLoginPacket
                    loginDimensions += packet.dimension
                    null
                }
                "handleRespawn" -> {
                    val packet = args!![0] as ClientboundRespawnPacket
                    respawnDimensions += packet.dimension
                    null
                }
                "handleCustomPayload" -> {
                    val packet = args!![0] as ClientboundCustomPayloadPacket
                    customPayloadChannels += packet.identifier
                    if (packet.identifier == runtimeChannel) {
                        val payload = packet.data
                        val messageId = payload.readVarInt()
                        if (messageId == 0) {
                            val additions = payload.readCollection(::ArrayList) { buf ->
                                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, buf.readResourceLocation())
                            }
                            val removals = payload.readCollection(::ArrayList) { buf ->
                                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, buf.readResourceLocation())
                            }
                            knownLevels += additions
                            knownLevels.removeAll(removals.toSet())
                        }
                    }
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }) as PacketListener

        fun pump(connection: Connection) {
            connection.tick()
            connection.handleDisconnection()
        }
    }
}
