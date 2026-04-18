package dev.yourname.obelisks.gametest

import com.google.gson.GsonBuilder
import com.mojang.authlib.GameProfile
import dev.yourname.instanceddimensions.MOD_ID as INSTANCED_DIMENSIONS_MOD_ID
import dev.yourname.instanceddimensions.NETWORK_CHANNEL
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceState
import dev.yourname.instanceddimensions.engine.travel.TravelManager
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
                val run = requireNotNull(RunRegistry.get(runId)) { "Expected run record for void-fall test" }
                val instance = requireNotNull(InstanceManager.getInstance(run.instanceId)) { "Expected instance handle for void-fall test" }
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
        val server = helper.level.server
        val definitionA = ObeliskDefinition(
            id = "test_shared_template_a",
            displayName = "Shared Template A",
            instanceTemplateId = "end",
            rewardTableId = "test_shared_rewards_a",
            worldgenFamilyId = "meteor"
        )
        val definitionB = ObeliskDefinition(
            id = "test_shared_template_b",
            displayName = "Shared Template B",
            instanceTemplateId = "end",
            rewardTableId = "test_shared_rewards_b",
            worldgenFamilyId = "meteor"
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
        helper.succeed()
    }

    fun reloadCommandRefreshesDefinitionData(helper: GameTestHelper) {
        val server = helper.level.server
        val definitionId = "test_reloadable_definition"
        val baseDefinition = ObeliskDefinition(
            id = definitionId,
            displayName = "Reload One",
            instanceTemplateId = "overworld",
            rewardTableId = "overworld",
            worldgenFamilyId = "meteor"
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
        helper.succeed()
    }

    fun worldgenFamiliesProduceDistinctSiteShapes(helper: GameTestHelper) {
        val spireDefinition = ObeliskDefinition(
            id = "test_spire_definition",
            displayName = "Test Spire",
            instanceTemplateId = "end",
            rewardTableId = "end",
            worldgenFamilyId = "spire"
        )
        val ruinDefinition = ObeliskDefinition(
            id = "test_ruin_definition",
            displayName = "Test Ruin",
            instanceTemplateId = "nether",
            rewardTableId = "nether",
            worldgenFamilyId = "ruin"
        )
        writeDefinition(spireDefinition)
        writeDefinition(ruinDefinition)
        reloadData()

        val spireCenter = helper.absolutePos(BlockPos(20, 3, 4))
        val ruinCenter = helper.absolutePos(BlockPos(36, 3, 4))
        prepareGenerationSurface(helper, spireCenter)
        prepareGenerationSurface(helper, ruinCenter)

        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, spireCenter, spireDefinition.id, RandomSource.create(1234L)),
            "Expected spire family test generation to succeed"
        )
        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, ruinCenter, ruinDefinition.id, RandomSource.create(5678L)),
            "Expected ruin family test generation to succeed"
        )

        val spirePos = spireCenter.below()
        val ruinPos = ruinCenter.below()
        val spireObelisk = helper.level.getBlockEntity(spirePos) as? ObeliskBlockEntity
        val ruinObelisk = helper.level.getBlockEntity(ruinPos) as? ObeliskBlockEntity
        helper.assertTrue(spireObelisk?.definitionId == spireDefinition.id, "Expected generated spire obelisk to keep its definition id")
        helper.assertTrue(ruinObelisk?.definitionId == ruinDefinition.id, "Expected generated ruin obelisk to keep its definition id")
        helper.assertTrue(countNonAirColumn(helper, spirePos.above(1), 7) >= 4, "Expected spire family to create a tall vertical structure")
        helper.assertTrue(countNonAirRing(helper, ruinPos, 4) >= 6, "Expected ruin family to create surrounding debris or pillars")
        helper.succeed()
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
