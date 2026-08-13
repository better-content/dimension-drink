package com.bettercontent.dimensiondrink.gametest

import com.google.gson.GsonBuilder
import com.mojang.authlib.GameProfile
import com.bettercontent.dimensiondrink.MOD_ID
import com.bettercontent.dimensiondrink.ObeliskConstants
import com.bettercontent.dimensiondrink.api.ObeliskApi
import com.bettercontent.dimensiondrink.api.RunBeginResult
import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.data.CanonicalTargetResolver
import com.bettercontent.dimensiondrink.data.CultivationPaletteDefinition
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.data.ObeliskDefinition
import com.bettercontent.dimensiondrink.data.RewardEntryDefinition
import com.bettercontent.dimensiondrink.data.RewardPoolDefinition
import com.bettercontent.dimensiondrink.data.RewardTableDefinition
import com.bettercontent.dimensiondrink.registry.ModBlocks
import com.bettercontent.dimensiondrink.runtime.ObeliskRuntimeService
import com.bettercontent.dimensiondrink.runtime.reward.RewardSystem
import com.bettercontent.dimensiondrink.runtime.run.RunRecord
import com.bettercontent.dimensiondrink.runtime.run.RunRegistry
import com.bettercontent.dimensiondrink.runtime.run.RunState
import com.bettercontent.dimensiondrink.runtime.run.RunSavedData
import com.bettercontent.dimensiondrink.runtime.ui.RunBossBarManager
import com.bettercontent.dimensiondrink.worldgen.ObeliskFeature
import com.bettercontent.dimensiondrink.worldgen.structure.DimensionalFontSiteGenerator
import com.bettercontent.dimensiondrink.worldgen.structure.DimensionalFontStructurePiece
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.PacketListener
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.handshake.ClientIntentionPacket
import net.minecraft.network.protocol.login.ClientLoginPacketListener
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.network.NetworkHooks
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.registries.ForgeRegistries
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.net.SocketAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

private enum class InstanceState {
    PREPARED,
    ACTIVE
}

private data class InstanceTemplate(
    val id: String,
    val stem: String = canonicalStem(id),
    val requiredNamespace: String = stem.substringBefore(':', "minecraft")
)

private data class InstanceSnapshot(
    val id: UUID,
    val templateId: String,
    val ownerId: UUID?,
    val state: InstanceState,
    val levelKey: ResourceKey<Level>,
    val preparedSpawnPos: BlockPos?,
    val updatedGameTime: Long = 0L
)

private data class ArrivalStatus(
    val center: BlockPos?,
    val completedChunks: Int = 1,
    val totalChunks: Int = 1,
    val failureReason: String? = null
)

private object InstanceTemplateDataManager {
    fun templatesPath(): Path = ObeliskDataManager.configRootPath().resolve("target_dimensions")
}

private object InstanceManager {
    fun records(): List<InstanceSnapshot> {
        val active = RunRegistry.snapshot().mapNotNull(::snapshotFromRun)
        val prepared = ObeliskDataManager.enabledDimensionDrinks()
            .filter { definition -> active.none { it.templateId == definition.instanceTemplateId } }
            .mapNotNull { definition ->
                val levelKey = canonicalLevelKey(definition.instanceTemplateId) ?: return@mapNotNull null
                InstanceSnapshot(
                    id = UUID.nameUUIDFromBytes("prepared:${definition.instanceTemplateId}".toByteArray()),
                    templateId = definition.instanceTemplateId,
                    ownerId = null,
                    state = InstanceState.PREPARED,
                    levelKey = levelKey,
                    preparedSpawnPos = BlockPos.ZERO
                )
            }
        return active + prepared
    }

    fun getInstance(siteId: UUID): InstanceSnapshot? {
        return RunRegistry.snapshot().firstOrNull { it.instanceId == siteId }?.let(::snapshotFromRun)
    }

    fun isTravelReady(@Suppress("UNUSED_PARAMETER") siteId: UUID): Boolean = true

    fun arrivalStatus(siteId: UUID): ArrivalStatus {
        val run = RunRegistry.snapshot().firstOrNull { it.instanceId == siteId }
        return ArrivalStatus(center = run?.spawnPos)
    }

    fun describeCloseState(@Suppress("UNUSED_PARAMETER") server: net.minecraft.server.MinecraftServer, siteId: UUID): String {
        return RunRegistry.snapshot().firstOrNull { it.instanceId == siteId }?.state?.name ?: "closed"
    }

    fun getTemplate(templateId: String): InstanceTemplate? {
        return canonicalLevelKey(templateId)?.let { InstanceTemplate(templateId) }
    }

    fun reloadTemplates() = Unit

    private fun snapshotFromRun(run: RunRecord): InstanceSnapshot? {
        val levelKey = run.backendLevelKey ?: return null
        return InstanceSnapshot(
            id = run.instanceId,
            templateId = run.instanceTemplateId,
            ownerId = run.id,
            state = InstanceState.ACTIVE,
            levelKey = levelKey,
            preparedSpawnPos = run.spawnPos,
            updatedGameTime = run.updatedGameTime
        )
    }
}

private object TravelManager {
    fun returnPlayer(player: ServerPlayer): Boolean = RunRegistry.returnPlayer(player)
}

private fun canonicalLevelKey(templateId: String): ResourceKey<Level>? {
    val location = when (templateId) {
        "overworld" -> ResourceLocation("minecraft", "overworld")
        "nether" -> ResourceLocation("minecraft", "the_nether")
        "end" -> ResourceLocation("minecraft", "the_end")
        "otherside" -> ResourceLocation("deeperdarker", "otherside")
        "undergarden" -> ResourceLocation("undergarden", "undergarden")
        else -> runCatching { ResourceLocation(templateId) }.getOrNull()
    } ?: return null
    return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location)
}

private fun canonicalStem(templateId: String): String {
    return canonicalLevelKey(templateId)?.location()?.toString() ?: templateId
}

object ObeliskGameTestSupport {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val runtimeChannel = ResourceLocation.fromNamespaceAndPath(MOD_ID, "run_backend")
    private val memoryChannels = ConcurrentHashMap<net.minecraft.server.MinecraftServer, SocketAddress>()
    private val testDataMutationLock = Any()

    private inline fun <T> withSerializedTestDataMutation(action: () -> T): T = synchronized(testDataMutationLock) {
        action()
    }

    fun runCreationPersistsOwnedInstanceMetadata(helper: GameTestHelper) {
        val server = helper.level.server
        waitForPreparedTemplate(helper, "end") {
            val run = beginRunOrFail(server, "end")

            waitUntil(helper, 40, "Expected canonical run site to become ACTIVE", condition = {
                InstanceManager.getInstance(run.instanceId)?.state == InstanceState.ACTIVE
            }, onSuccess = {
                val activeRun = RunRegistry.get(run.runId)
                val activeInstance = InstanceManager.getInstance(run.instanceId)
                helper.assertTrue(activeRun != null, "Expected created run to be registered")
                helper.assertTrue(activeInstance?.ownerId == run.runId, "Expected canonical site to be owned by the created run")
                helper.assertTrue(activeRun?.backendLevelKey?.location()?.toString() == "minecraft:the_end", "Expected end run to target the real End dimension")
                helper.assertTrue(activeRun?.backendSiteCenter != null, "Expected canonical run site center to be persisted")
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
                        append("Expected run cleanup to remove the run record and keep canonical world state")
                        append(" | run=")
                        append(runSnapshot?.state ?: "null")
                        append(" site=")
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
    }

    fun chargedObeliskActivatesRunAndReturnsPlayer(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 1))

        try {
            configureActivationTestDimensionDrink(server)
            helper.level.setBlock(obeliskPos.below(), Blocks.OBSIDIAN.defaultBlockState(), 3)
            helper.level.setBlock(obeliskPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)

            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected placed obelisk block entity to exist")
            prepareTestObelisk(obelisk!!)
            obelisk.regenerateEnergy(obelisk.getMaxEnergyStored())

            waitForPreparedTemplate(helper, "end") {
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

                waitUntil(helper, 220, "Expected charged obelisk activation to create a run record", condition = {
                    client.pump(server)
                    val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                    if (liveObelisk != null) {
                        liveObelisk.setEnergyStoredForDebug(liveObelisk.getMaxEnergyStored())
                    }
                    val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                    val run = activeRunId?.let(RunRegistry::get)
                    run != null
                }, onSuccess = {
                    waitUntil(helper, 600, "Expected charged obelisk activation to produce an active canonical run", condition = {
                        client.pump(server)
                        val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                        if (liveObelisk != null) {
                            liveObelisk.setEnergyStoredForDebug(liveObelisk.getMaxEnergyStored())
                        }
                        val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                        val run = activeRunId?.let(RunRegistry::get)
                        run?.state == com.bettercontent.dimensiondrink.runtime.run.RunState.ACTIVE &&
                            run.backendLevelKey == Level.END &&
                            run.backendSiteCenter != null
                    }, onSuccess = {
                        val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                        helper.assertTrue(liveObelisk != null, "Expected origin obelisk block entity to remain loaded")
                        val runId = requireNotNull(liveObelisk!!.activeRunId) { "Expected active run id after activation" }
                        val run = requireNotNull(RunRegistry.get(runId)) { "Expected run registry entry after activation" }
                        helper.assertTrue(run.originLevelKey == helper.level.dimension(), "Expected activated run to keep origin dimension")
                        helper.assertTrue(RunRegistry.finishRun(server, runId), "Expected activation test cleanup to finish the run")
                        waitUntil(helper, 1200, "Expected activation test cleanup to remove the run", condition = {
                            client.pump(server)
                            RunRegistry.get(runId) == null
                        }, onSuccess = {
                            client.close(server)
                            helper.succeed()
                        })
                    })
                })
            }
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun fontRequiresVisibleBloodToStartRun(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 6))

        try {
            configureActivationTestDimensionDrink(server)
            helper.level.setBlock(obeliskPos.below(), Blocks.OBSIDIAN.defaultBlockState(), 3)
            helper.level.setBlock(obeliskPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)
            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                ?: error("Expected placed obelisk block entity to exist")
            prepareTestObelisk(obelisk)

            waitForPreparedTemplate(helper, "end") {
                obelisk.setEnergyStoredForDebug(obelisk.getBloodStartCost().toInt() - 1)
                val rejected = RunRegistry.activateObelisk(player, obelisk, obeliskPos)
                helper.assertTrue(
                    rejected?.contains("500 mB") == true,
                    "Expected font activation below 500 mB to reject, got: $rejected"
                )
                helper.assertTrue(obelisk.activeRunId == null, "Expected low-blood font not to create a run")

                obelisk.setEnergyStoredForDebug(obelisk.getBloodStartCost().toInt())
                val accepted = RunRegistry.activateObelisk(player, obelisk, obeliskPos)
                helper.assertTrue(
                    accepted?.startsWith("Drinking from ") == true,
                    "Expected font activation at 500 mB to start drinking warmup, got: $accepted"
                )
                val runId = obelisk.activeRunId
                helper.assertTrue(runId != null && RunRegistry.get(runId) != null, "Expected 500 mB font to create a run")
                runId?.let { RunRegistry.finishRun(server, it) }
                client.close(server)
                helper.succeed()
            }
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun relocatedSpawnRetargetsTravelWarmup(helper: GameTestHelper) {
        val server = helper.level.server
        configureActivationTestDimensionDrink(server)
        waitForPreparedTemplate(helper, "end") {
            val run = beginRunOrFail(server, "end")

            waitUntil(helper, 400, "Expected end run to target canonical End dimension", condition = {
                val record = RunRegistry.get(run.runId)
                val levelKey = record?.backendLevelKey
                levelKey == Level.END && record?.backendSiteCenter != null
            }, onSuccess = {
                helper.assertTrue(RunRegistry.finishRun(server, run.runId), "Expected canonical target test cleanup to finish the run")
                waitUntil(helper, 1200, failureMessage = {
                    val runSnapshot = RunRegistry.get(run.runId)
                    val instanceSnapshot = InstanceManager.getInstance(run.instanceId)
                    buildString {
                        append("Expected canonical target test cleanup to remove the run")
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
    }

    fun successfulRunBuffersRewardsAndShowsBossBar(helper: GameTestHelper) {
        deleteTestConfigs()
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val obeliskPos = helper.absolutePos(BlockPos(4, 2, 4))
        val originDimension = player.serverLevel().dimension()
        val initialPlayerEmeralds = countPlayerItems(player, Items.EMERALD)
        val definitionId = "test_reward_success_definition"

        try {
            writeDefinition(
                ObeliskDefinition(
                    id = definitionId,
                    displayName = "Reward Success",
                    instanceTemplateId = "end",
                    rewardTableId = "test_reward_success_rewards"
                )
            )
            writeRewardTable(
                RewardTableDefinition(
                    id = "test_reward_success_rewards",
                    baseRolls = 2,
                    damagePerBonusRoll = 9999.0f,
                    pools = listOf(poolOf("reward_items", "minecraft:emerald"))
                )
            )
            reloadDataWithCommand(server)
            placeChargedDefinitionObelisk(helper, obeliskPos, definitionId)
            helper.level.setBlock(obeliskPos.east(), Blocks.CHEST.defaultBlockState(), 3)

            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected placed obelisk block entity to exist")

            waitForPreparedTemplate(helper, "end") {
                val activationMessage = RunRegistry.activateObelisk(player, obelisk!!, obeliskPos)
                helper.assertTrue(
                    activationMessage?.startsWith("Drinking from ") == true,
                    "Expected reward test activation to start a run, got: $activationMessage"
                )

                waitUntil(helper, 120, "Expected reward test obelisk to create an active run id", condition = {
                    client.pump(server)
                    val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                    activeRunId != null && RunRegistry.get(activeRunId) != null
                }, onSuccess = {
                val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                helper.assertTrue(liveObelisk != null, "Expected live obelisk block entity after reward-test activation")
                val runId = requireNotNull(liveObelisk!!.activeRunId) { "Expected active run id for reward test" }

                waitUntil(helper, 120, "Expected reward test run to allocate a canonical target site", condition = {
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
                        val instance = requireNotNull(InstanceManager.getInstance(run.instanceId)) { "Expected canonical target site for reward test" }
                        val runtimeLevel = requireNotNull(server.getLevel(instance.levelKey)) { "Expected canonical target level for reward test" }
                        val returnPadPos = requireNotNull(run.spawnPos).below()
                        assertGenericSpawnPlatform(helper, runtimeLevel, returnPadPos)

                        waitUntil(helper, 120, "Expected reward test player to enter the canonical target dimension", condition = {
                            client.pump(server)
                            player.serverLevel().dimension() == instance.levelKey &&
                                RunRegistry.get(runId)?.activePlayers?.contains(player.uuid) == true
                        }, onSuccess = {
                            liveObelisk.drainEnergy((liveObelisk.getMaxEnergyStored() * 0.15).toInt().coerceAtLeast(1))
                            RunRegistry.recordDamage(player.uuid, instance.levelKey, 40f)

                            waitUntil(helper, 80, "Expected low blood to create a boss bar for the run", condition = {
                                RunBossBarManager.hasBossBar(runId)
                            }, onSuccess = {
                                helper.assertTrue(RunRegistry.finishRun(server, runId), "Expected reward test finish to succeed")
                                    waitUntil(helper, 360, failureMessage = {
                                        val runSnapshot = RunRegistry.get(runId)
                                        val instanceSnapshot = InstanceManager.getInstance(run.instanceId)
                                        val currentObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                                        val nearbyEmeralds = countNearbyItems(helper.level, obeliskPos, Items.EMERALD, 3.0)
                                        val inventoryEmeralds = countPlayerItems(player, Items.EMERALD) - initialPlayerEmeralds
                                        buildString {
                                            append("Expected successful run cleanup to complete and clear the boss bar")
                                            append(" | bufferedEmeralds=")
                                            append(emeraldCount(currentObelisk))
                                            append(" nearbyEmeralds=")
                                            append(nearbyEmeralds)
                                            append(" inventoryEmeralds=")
                                            append(inventoryEmeralds)
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
                                        val bufferedEmeralds = emeraldCount(helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)
                                        val nearbyEmeralds = countNearbyItems(helper.level, obeliskPos, Items.EMERALD, 3.0)
                                        val inventoryEmeralds = countPlayerItems(player, Items.EMERALD) - initialPlayerEmeralds
                                        val rewardSignal = bufferedEmeralds + nearbyEmeralds + inventoryEmeralds
                                        player.serverLevel().dimension() == originDimension &&
                                            rewardSignal > 0 &&
                                            RunRegistry.get(runId) == null &&
                                            !RunBossBarManager.hasBossBar(runId)
                                    }, onSuccess = {
                                        helper.assertTrue(player.serverLevel().dimension() == originDimension, "Expected finished reward run to return the survivor")
                                        helper.assertTrue(!RunBossBarManager.hasBossBar(runId), "Expected finished run to clear its boss bar")
                                        client.close(server)
                                        deleteTestConfigs()
                                        reloadDataWithCommand(server)
                                        helper.succeed()
                                    })
                            })
                        })
                    })
                })
                })
            }
        } catch (t: Throwable) {
            client.close(server)
            deleteTestConfigs()
            reloadDataWithCommand(server)
            throw t
        }
    }

    fun deathDisqualifiesPlayerAndRespawnReturnsToFont(helper: GameTestHelper, definitionId: String = "end") {
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
            obelisk.setDefinition(definitionId)
            obelisk.regenerateEnergy(obelisk.getMaxEnergyStored())

            waitForPreparedTemplate(helper, definitionId) {
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
                    "Expected death-return test activation to consume the interaction"
                )

                waitUntil(helper, 320, "Expected death-return test to create an active run and move the player into its target dimension", condition = {
                    client.pump(server)
                    val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                    val run = activeRunId?.let(RunRegistry::get)
                    val instance = run?.let { InstanceManager.getInstance(it.instanceId) }
                    run?.definitionId == definitionId &&
                    run?.spawnPos != null &&
                        run.state == com.bettercontent.dimensiondrink.runtime.run.RunState.ACTIVE &&
                        instance != null &&
                        player.serverLevel().dimension() == instance.levelKey
                }, onSuccess = {
                client.pump(server)
                val liveObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                helper.assertTrue(liveObelisk != null, "Expected live font block entity for death-return test")
                val runId = requireNotNull(liveObelisk!!.activeRunId) { "Expected active run id for death-return test" }

                val deathEvent = LivingDeathEvent(player, player.damageSources().generic())
                RunRegistry.onLivingDeath(deathEvent)
                helper.assertTrue(!deathEvent.isCanceled, "Expected font run death handling to leave normal death uncanceled")

                val afterDeath = requireNotNull(RunRegistry.get(runId)) { "Expected run to remain registered after one participant death" }
                helper.assertTrue(player.uuid !in afterDeath.activePlayers, "Expected dead player to be removed from active players")
                helper.assertTrue(player.uuid !in afterDeath.pendingPlayers, "Expected dead player to be removed from pending players")
                helper.assertTrue(player.uuid !in afterDeath.survivors, "Expected dead player to lose survivor reward eligibility")
                helper.assertTrue(player.uuid in afterDeath.disqualifiedPlayers, "Expected dead player to be marked disqualified")

                RunRegistry.onPlayerRespawn(PlayerEvent.PlayerRespawnEvent(player, false))
                client.pump(server)
                helper.assertTrue(player.serverLevel().dimension() == originDimension, "Expected respawn handler to move player back to the font dimension")
                helper.assertTrue(
                    player.blockPosition().closerThan(obeliskPos, 4.0),
                    "Expected respawn handler to place player outside the origin font"
                )

                helper.assertTrue(RunRegistry.finishRun(server, runId), "Expected death-return cleanup to finish the run")
                waitUntil(helper, 360, "Expected death-return cleanup to clear the origin font run", condition = {
                    client.pump(server)
                    RunRegistry.get(runId) == null &&
                        (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId == null
                }, onSuccess = {
                    val cooledObelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
                    helper.assertTrue(cooledObelisk?.activeRunId == null, "Expected death-return cleanup to clear the active run id")
                    helper.assertTrue(cooledObelisk?.isOnCooldown() == false, "Expected death-return cleanup to leave font usable without cooldown")
                    helper.assertTrue(RunRegistry.get(runId) == null, "Expected death-return cleanup to remove the run")
                    client.close(server)
                    helper.succeed()
                })
                })
            }
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun rewardTablesFollowDefinitionWithSharedTemplate(helper: GameTestHelper) {
        withSerializedTestDataMutation {
            deleteTestConfigs()
            val server = helper.level.server
            val clientA = connectHeadlessPlayer(helper)
            val clientB = connectHeadlessPlayer(helper)
            val playerA = clientA.player
            val playerB = clientB.player

            try {
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

                val runA = rewardOnlyRun(helper, obeliskPosA, definitionA, playerA.uuid)
                val runB = rewardOnlyRun(helper, obeliskPosB, definitionB, playerB.uuid)
                helper.assertTrue(RewardSystem.spawnRewards(server, runA), "Expected definition A reward spawn to succeed")
                helper.assertTrue(RewardSystem.spawnRewards(server, runB), "Expected definition B reward spawn to succeed")

                helper.assertTrue(countPlayerItems(playerA, Items.IRON_INGOT) > 0, "Expected definition A survivor to receive its iron reward table")
                helper.assertTrue(countPlayerItems(playerA, Items.GOLD_INGOT) == 0, "Expected definition A survivor to avoid definition B rewards")
                helper.assertTrue(countPlayerItems(playerB, Items.GOLD_INGOT) > 0, "Expected definition B survivor to receive its gold reward table")
                helper.assertTrue(countPlayerItems(playerB, Items.IRON_INGOT) == 0, "Expected definition B survivor to avoid definition A rewards")
                helper.succeed()
            } catch (t: Throwable) {
                throw t
            } finally {
                clientA.close(server)
                clientB.close(server)
                deleteTestConfigs()
                reloadData()
            }
        }
    }

    fun reloadCommandRefreshesDefinitionData(helper: GameTestHelper) {
        withSerializedTestDataMutation {
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
    }

    fun worldgenDefinitionsProduceFontAltarSites(helper: GameTestHelper) {
        deleteTestConfigs()
        val moddedTemplateId = "otherside"
        val moddedTargetDimension = "deeperdarker:otherside"
        val endDefinition = ObeliskDefinition(
            id = "test_end_visual_definition",
            displayName = "Test End Visual",
            instanceTemplateId = "end",
            rewardTableId = "end",
            cultivationPalette = CultivationPaletteDefinition(trophyBlocks = listOf("minecraft:white_candle"))
        )
        val netherDefinition = ObeliskDefinition(
            id = "test_nether_visual_definition",
            displayName = "Test Nether Visual",
            instanceTemplateId = "nether",
            rewardTableId = "nether",
            cultivationPalette = CultivationPaletteDefinition(trophyBlocks = listOf("undergarden:shard_torch"))
        )
        val moddedDefinition = ObeliskDefinition(
            id = "test_modded_visual_definition",
            displayName = "Test Modded Visual",
            instanceTemplateId = moddedTemplateId,
            targetDimension = moddedTargetDimension,
            rewardTableId = "default",
            cultivationPalette = CultivationPaletteDefinition(trophyBlocks = listOf("minecraft:magenta_candle"))
        )
        writeDefinition(endDefinition)
        writeDefinition(netherDefinition)
        writeDefinition(moddedDefinition)
        reloadData()

        val endSurfaceCenter = helper.absolutePos(BlockPos(20, 3, 4))
        val endCenter = endSurfaceCenter.above(12)
        val netherCenter = helper.absolutePos(BlockPos(140, 3, 4))
        val moddedCenter = helper.absolutePos(BlockPos(260, 3, 4))
        val leafCanopyCenter = helper.absolutePos(BlockPos(380, 3, 4))
        val foliageCenter = helper.absolutePos(BlockPos(500, 3, 4))
        prepareGenerationSurface(helper, endSurfaceCenter)
        prepareGenerationSurface(helper, netherCenter)
        prepareCliffsideGenerationSurface(helper, moddedCenter)
        prepareGenerationSurface(helper, leafCanopyCenter)
        prepareGenerationSurface(helper, foliageCenter)
        placeLeafPlacementNoise(helper, leafCanopyCenter)
        placeFoliagePlacementNoise(helper, foliageCenter)

        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, endCenter, endDefinition.id, RandomSource.create(1234L)),
            "Expected end definition test generation to succeed"
        )
        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, netherCenter, netherDefinition.id, RandomSource.create(5678L)),
            "Expected nether definition test generation to succeed"
        )
        val moddedGenerated = ObeliskFeature.generateDefinitionSiteForTests(helper.level, moddedCenter.above(18), moddedDefinition.id, RandomSource.create(9012L))
        helper.assertTrue(
            moddedGenerated,
            "Expected modded definition test generation to succeed"
        )
        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, leafCanopyCenter.above(12), endDefinition.id, RandomSource.create(3456L)),
            "Expected leaf-canopy test generation to ignore leaves and choose the sturdy surface"
        )
        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, foliageCenter.above(12), endDefinition.id, RandomSource.create(4567L)),
            "Expected foliage test generation to treat tall grass and bushes as replaceable airspace"
        )

        val endPos = requireNotNull(locateGeneratedObeliskPos(helper, endCenter, endDefinition.id)) {
            "Expected end definition site to place a font on the altar"
        }
        val netherPos = requireNotNull(locateGeneratedObeliskPos(helper, netherCenter, netherDefinition.id)) {
            "Expected nether definition site to place a font on the altar"
        }
        val moddedPos = requireNotNull(locateGeneratedObeliskPos(helper, moddedCenter, moddedDefinition.id)) {
            "Expected modded definition site to place a font on the altar"
        }
        val leafCanopyPos = requireNotNull(locateGeneratedObeliskPos(helper, leafCanopyCenter, endDefinition.id)) {
            "Expected leaf-canopy definition site to place a font on the altar"
        }
        val foliagePos = requireNotNull(locateGeneratedObeliskPos(helper, foliageCenter, endDefinition.id)) {
            "Expected foliage definition site to place a font on the altar"
        }
        val endObelisk = helper.level.getBlockEntity(endPos) as? ObeliskBlockEntity
        val netherObelisk = helper.level.getBlockEntity(netherPos) as? ObeliskBlockEntity
        val moddedObelisk = helper.level.getBlockEntity(moddedPos) as? ObeliskBlockEntity
        val leafCanopyObelisk = helper.level.getBlockEntity(leafCanopyPos) as? ObeliskBlockEntity
        val foliageObelisk = helper.level.getBlockEntity(foliagePos) as? ObeliskBlockEntity
        helper.assertTrue(endObelisk?.definitionId == endDefinition.id, "Expected generated end obelisk to keep its definition id")
        helper.assertTrue(netherObelisk?.definitionId == netherDefinition.id, "Expected generated nether obelisk to keep its definition id")
        helper.assertTrue(moddedObelisk?.definitionId == moddedDefinition.id, "Expected generated modded obelisk to keep its modded definition id")
        helper.assertTrue(leafCanopyObelisk?.definitionId == endDefinition.id, "Expected generated leaf-canopy obelisk to keep its definition id")
        helper.assertTrue(foliageObelisk?.definitionId == endDefinition.id, "Expected generated foliage obelisk to keep its definition id")
        helper.assertTrue(moddedObelisk?.targetTemplateId == moddedTargetDimension, "Expected generated modded obelisk to target its selected runtime dimension")
        assertGeneratedAltar(helper, endPos, "end")
        assertGeneratedAltar(helper, netherPos, "nether")
        assertGeneratedAltar(
            helper,
            moddedPos,
            "modded",
            expectedTrophyOverride = Blocks.MAGENTA_CANDLE
        )
        assertGeneratedAltar(helper, leafCanopyPos, "leaf-canopy")
        assertGeneratedAltar(helper, foliagePos, "foliage")
        deleteTestConfigs()
        reloadData()
        helper.succeed()
    }

    fun structurePieceWorldgenProducesCompleteFontAltarSites(helper: GameTestHelper) {
        deleteTestConfigs()
        val definition = ObeliskDefinition(
            id = "test_structure_piece_visual_definition",
            displayName = "Structure Piece Visual",
            instanceTemplateId = "otherside",
            targetDimension = "deeperdarker:otherside",
            rewardTableId = "default",
            cultivationPalette = CultivationPaletteDefinition(trophyBlocks = listOf("minecraft:white_candle"))
        )
        writeDefinition(definition)
        reloadData()

        val center = chunkInteriorTestAnchor(helper.absolutePos(BlockPos(260, 3, 4)))
        prepareCliffsideGenerationSurface(helper, center)
        val altarCenter = center.below()
        val piece = DimensionalFontStructurePiece(
            altarCenter,
            9012L,
            definition.id,
            (definition.maxBlood ?: ObeliskConstants.MAX_BLOOD_STORAGE) * 1.5
        )
        val centerChunk = ChunkPos(altarCenter)
        val sentinelColumn = altarCenter.offset(0, 0, 20)
        val sentinel = BlockPos(
            sentinelColumn.x,
            helper.level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, sentinelColumn.x, sentinelColumn.z) - 1,
            sentinelColumn.z
        )
        helper.level.setBlock(sentinel, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3)
        val centerBox = BoundingBox(
            centerChunk.minBlockX,
            helper.level.minBuildHeight,
            centerChunk.minBlockZ,
            centerChunk.maxBlockX,
            helper.level.maxBuildHeight - 1,
            centerChunk.maxBlockZ
        )
        piece.postProcess(
            helper.level,
            helper.level.structureManager(),
            helper.level.chunkSource.generator,
            RandomSource.create(9012L),
            centerBox,
            ChunkPos(centerChunk.x + 128, centerChunk.z - 128),
            BlockPos.ZERO
        )
        helper.assertTrue(
            helper.level.getBlockState(sentinel).`is`(Blocks.DIAMOND_BLOCK),
            "Expected the structure BoundingBox to prevent writes into a neighboring chunk"
        )
        for (chunkX in centerChunk.x - 2..centerChunk.x + 2) {
            for (chunkZ in centerChunk.z - 2..centerChunk.z + 2) {
                val slice = ChunkPos(chunkX, chunkZ)
                val box = BoundingBox(
                    slice.minBlockX,
                    helper.level.minBuildHeight,
                    slice.minBlockZ,
                    slice.maxBlockX,
                    helper.level.maxBuildHeight - 1,
                    slice.maxBlockZ
                )
                // Deliberately disagree with the slice to model the callback bookkeeping observed
                // under C2ME. The vanilla BoundingBox must remain the only placement authority.
                val mismatchedCallbackChunk = ChunkPos(chunkX + 128, chunkZ - 128)
                piece.postProcess(
                    helper.level,
                    helper.level.structureManager(),
                    helper.level.chunkSource.generator,
                    RandomSource.create(9012L),
                    box,
                    mismatchedCallbackChunk,
                    BlockPos.ZERO
                )
            }
        }

        val fontPos = requireNotNull(locateGeneratedObeliskPosInArea(helper, center, 40)) {
            "Expected structure-piece generation to place a font on the altar"
        }
        val obelisk = helper.level.getBlockEntity(fontPos) as? ObeliskBlockEntity
        helper.assertTrue(
            obelisk?.definitionId == definition.id,
            "Expected structure-piece generated obelisk to keep its definition id"
        )
        assertGeneratedAltar(helper, fontPos, "structure-piece", requireReliquaryLandscaping = false)
        var pathBlocks = 0
        for (dx in -DimensionalFontSiteGenerator.SITE_RADIUS..DimensionalFontSiteGenerator.SITE_RADIUS) {
            for (dz in -DimensionalFontSiteGenerator.SITE_RADIUS..DimensionalFontSiteGenerator.SITE_RADIUS) {
                for (dy in -20..20) {
                    if (helper.level.getBlockState(altarCenter.offset(dx, dy, dz)).`is`(Blocks.PACKED_MUD)) {
                        pathBlocks++
                    }
                }
            }
        }
        helper.assertTrue(pathBlocks >= 8, "Expected structure-piece generation to include bounded approach paths")
        helper.assertTrue(
            DimensionalFontSiteGenerator.centerFitsStartChunk(altarCenter),
            "Expected the complete center court to remain inside its start chunk"
        )

        deleteTestConfigs()
        reloadData()
        helper.succeed()
    }

    fun structurePieceWorldgenPlacesLiteralDimensionalFont(helper: GameTestHelper) {
        deleteTestConfigs()
        val definition = ObeliskDefinition(
            id = "test_literal_worldgen_font",
            displayName = "Literal Worldgen Font",
            instanceTemplateId = "overworld",
            targetDimension = "minecraft:overworld",
            rewardTableId = "default"
        )
        writeDefinition(definition)
        reloadData()

        val surfaceCenter = chunkInteriorTestAnchor(helper.absolutePos(BlockPos(20, 3, 20)))
        prepareGenerationSurface(helper, surfaceCenter)
        val altarCenter = surfaceCenter.below()
        val piece = DimensionalFontStructurePiece(
            altarCenter,
            0x4c49544552414cL,
            definition.id,
            (definition.maxBlood ?: ObeliskConstants.MAX_BLOOD_STORAGE) * 1.5
        )
        val startChunk = ChunkPos(altarCenter)
        val startChunkBox = BoundingBox(
            startChunk.minBlockX,
            helper.level.minBuildHeight,
            startChunk.minBlockZ,
            startChunk.maxBlockX,
            helper.level.maxBuildHeight - 1,
            startChunk.maxBlockZ
        )
        piece.postProcess(
            helper.level,
            helper.level.structureManager(),
            helper.level.chunkSource.generator,
            RandomSource.create(0x4c49544552414cL),
            startChunkBox,
            ChunkPos(startChunk.x + 128, startChunk.z - 128),
            BlockPos.ZERO
        )

        val expectedFontPos = altarCenter.above(3)
        val generatedState = helper.level.getBlockState(expectedFontPos)
        val generatedId = BuiltInRegistries.BLOCK.getKey(generatedState.block)
        helper.assertTrue(
            generatedId == ResourceLocation(MOD_ID, "dimensional_font"),
            "Expected the production structure piece to generate literal dimension_drink:dimensional_font at $expectedFontPos, found $generatedId"
        )
        helper.assertTrue(
            generatedState.`is`(ModBlocks.OBELISK.get()),
            "Expected the generated literal font to use the registered dimensional font block"
        )
        val generatedFont = helper.level.getBlockEntity(expectedFontPos) as? ObeliskBlockEntity
        helper.assertTrue(generatedFont != null, "Expected the literal generated font to create its block entity")
        helper.assertTrue(
            generatedFont?.definitionId == definition.id,
            "Expected the literal generated font block entity to retain its serialized definition"
        )
        helper.assertTrue(
            generatedFont?.bloodStored == generatedFont?.getModifiedMaxStorage()?.toDouble(),
            "Expected the literal generated font block entity to be initialized and filled"
        )

        deleteTestConfigs()
        reloadData()
        helper.succeed()
    }

    fun generatedOverworldChunksProduceCultivationCenters(helper: GameTestHelper) {
        deleteTestConfigs()
        reloadData()

        val minChunkX = 24
        val maxChunkX = 120
        val minChunkZ = 24
        val maxChunkZ = 120
        val anchors = ObeliskFeature.generatedSiteAnchorsForChunkRangeForTests(minChunkX, maxChunkX, minChunkZ, maxChunkZ)
            .filter { anchor -> anchor.x in (minChunkX * 16)..(maxChunkX * 16 + 15) && anchor.z in (minChunkZ * 16)..(maxChunkZ * 16 + 15) }
            .take(12)

        helper.assertTrue(anchors.isNotEmpty(), "Expected overworld terrain test window to include deterministic candidate site anchors")

        anchors.forEach { anchor ->
            prepareGenerationSurface(helper, anchor.atY(4))
            val anchorChunkX = anchor.x shr 4
            val anchorChunkZ = anchor.z shr 4
            for (chunkX in anchorChunkX - 6..anchorChunkX + 6) {
                for (chunkZ in anchorChunkZ - 6..anchorChunkZ + 6) {
                    helper.level.getChunk(chunkX, chunkZ)
                }
            }
        }

        anchors.forEach { anchor ->
            val anchorChunkX = anchor.x shr 4
            val anchorChunkZ = anchor.z shr 4
            ObeliskFeature.generatePlacedSitesForChunkRangeForTests(
                helper.level,
                anchorChunkX - 6,
                anchorChunkX + 6,
                anchorChunkZ - 6,
                anchorChunkZ + 6
            )
        }
        val generatedFonts = anchors.mapNotNull { anchor ->
            locateGeneratedObeliskPosInArea(helper, anchor.atY(32), 48)
        }.distinct()

        helper.assertTrue(generatedFonts.isNotEmpty(), "Expected generated overworld chunks to accept dimensional font cultivation center placement in sampled chunks")
        generatedFonts.distinct().forEachIndexed { index, fontPos ->
            assertGeneratedAltar(helper, fontPos, "generated-overworld-$index", requireBroadLowerStep = false)
        }
        helper.succeed()
    }

    fun underwaterWorldgenDoesNotPlaceFonts(helper: GameTestHelper) {
        withSerializedTestDataMutation {
            deleteTestConfigs()
            val definition = ObeliskDefinition(
                id = "test_underwater_worldgen_definition",
                displayName = "Underwater Worldgen",
                instanceTemplateId = "end",
                rewardTableId = "end"
            )
            val secondary = ObeliskDefinition(
                id = "test_underwater_worldgen_secondary",
                displayName = "Underwater Worldgen Secondary",
                instanceTemplateId = "nether",
                targetDimension = "minecraft:the_nether",
                rewardTableId = "nether"
            )
            writeDefinition(definition)
            writeDefinition(secondary)
            reloadData()

            val center = helper.absolutePos(BlockPos(20, 8, 20))
            prepareUnderwaterGenerationSurface(helper, center, waterDepth = 6)
            helper.assertTrue(
                !ObeliskFeature.generateDefinitionSiteForTests(helper.level, center, definition.id, RandomSource.create(2468L)),
                "Expected underwater altar generation to be rejected"
            )
            helper.assertTrue(
                locateGeneratedObeliskPos(helper, center) == null,
                "Expected underwater altar generation not to place a font"
            )

            deleteTestConfigs()
            reloadData()
            helper.succeed()
        }
    }

    fun reloadSkipsDefinitionsWithMissingRequiredNamespace(helper: GameTestHelper) {
        withSerializedTestDataMutation {
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
    }

    fun reloadSkipsDefinitionsWithMissingInstanceTemplate(helper: GameTestHelper) {
        withSerializedTestDataMutation {
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
                ObeliskApi.getDefinition(missingId) != null,
                "Expected reload to keep canonical target definitions; backend validates target dimensions at activation time"
            )
            helper.assertTrue(
                ObeliskApi.getDefinition(presentId)?.targetDimension == "minecraft:the_end",
                "Expected reload to normalize legacy end target to minecraft:the_end"
            )

            deleteTestConfigs()
            reloadDataWithCommand(server)
            helper.succeed()
        }
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
        helper.assertTrue(obelisk!!.targetTemplateId == "minecraft:the_end", "Expected obelisk definition to resolve target dimension to minecraft:the_end")
        val template = requireNotNull(InstanceManager.getTemplate(obelisk.targetTemplateId)) {
            "Expected resolved obelisk target ${obelisk.targetTemplateId} to be canonical"
        }
        helper.assertTrue(template.id == "minecraft:the_end", "Expected resolved target id to be minecraft:the_end")
        helper.assertTrue(template.stem == "minecraft:the_end", "Expected target stem to be minecraft:the_end")
        helper.assertTrue(template.requiredNamespace == "minecraft", "Expected vanilla target namespace to be minecraft")

        deleteTestConfigs()
        deleteTestInstanceTemplates()
        reloadDataWithCommand(server)
        InstanceManager.reloadTemplates()
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

            waitForPreparedTemplate(helper, "end") {
                val messageA = RunRegistry.activateObelisk(playerA, obelisk!!, obeliskPos)
                helper.assertTrue(
                    messageA?.startsWith("Drinking from ") == true,
                    "Expected first player to start a run"
                )

                waitUntil(helper, 320, "Expected first player to enter the canonical target dimension", condition = {
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
                    helper.assertTrue(joinMessage?.startsWith("Drinking from active") == true, "Expected second player to join the existing run")

                    waitUntil(helper, 240, "Expected both players to share the same canonical target site", condition = {
                        clientA.pump(server)
                        clientB.pump(server)
                        val run = RunRegistry.get(activeRunId)
                        run?.activePlayers?.containsAll(listOf(playerA.uuid, playerB.uuid)) == true &&
                            playerA.serverLevel().dimension() == instance.levelKey &&
                            playerB.serverLevel().dimension() == instance.levelKey
                    }, onSuccess = {
                        helper.assertTrue(TravelManager.returnPlayer(playerA), "Expected first player return to succeed")
                        helper.assertTrue(TravelManager.returnPlayer(playerB), "Expected second player return to succeed")
                        listOf(playerA, playerB).forEach { player ->
                            val slowness = player.getEffect(MobEffects.MOVEMENT_SLOWDOWN)
                            val darkness = player.getEffect(MobEffects.DARKNESS)
                            helper.assertTrue(
                                slowness?.duration == 20 && slowness.amplifier == 3,
                                "Expected returning player to receive one second of Slowness IV"
                            )
                            helper.assertTrue(
                                darkness?.duration == 20 && darkness.amplifier == 0,
                                "Expected returning player to receive one second of Darkness I"
                            )
                        }
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
            }
        } catch (t: Throwable) {
            clientA.close(server)
            clientB.close(server)
            throw t
        }
    }

    fun commandDebugSpawnCreatesChargedObelisk(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        try {
            val result = server.commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "font debug_spawn end")
            helper.assertTrue(result == 1, "Expected /font debug_spawn end to succeed")
            client.close(server)
            helper.succeed()
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun commandCleanupRunRemovesActiveRun(helper: GameTestHelper) {
        val server = helper.level.server
        waitForPreparedTemplate(helper, "end") {
            val run = beginRunOrFail(server, "end")
            helper.assertTrue(RunRegistry.get(run.runId) != null, "Expected command cleanup test run to exist before cleanup command")
            val result = server.commands.performPrefixedCommand(
                server.createCommandSourceStack().withPermission(4),
                "font cleanup_run ${run.runId}"
            )
            helper.assertTrue(result == 1, "Expected /font cleanup_run to accept an active run id")
            waitUntil(helper, 200, "Expected cleanup_run command to remove run from registry", condition = {
                RunRegistry.get(run.runId) == null
            }, onSuccess = {
                helper.succeed()
            })
        }
    }

    fun commandReturnValidatesPlayerBinding(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val obeliskPos = helper.absolutePos(BlockPos(12, 2, 12))

        try {
            val unboundResult = server.commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "font return")
            helper.assertTrue(unboundResult == 0, "Expected /font return to fail when player is not assigned to a run")
            placeChargedDefinitionObelisk(helper, obeliskPos, "end")
            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected obelisk for command return test")

            waitForPreparedTemplate(helper, "end") {
                val activationMessage = RunRegistry.activateObelisk(player, obelisk!!, obeliskPos)
                helper.assertTrue(
                    activationMessage?.startsWith("Drinking from ") == true,
                    "Expected command return test to start a run, got: $activationMessage"
                )

                waitUntil(helper, 240, "Expected player to bind to a run before using /font return", condition = {
                    client.pump(server)
                    val runId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                    val run = runId?.let(RunRegistry::get)
                    run?.activePlayers?.contains(player.uuid) == true
                }, onSuccess = {
                    val runId = requireNotNull((helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId) {
                        "Expected active run id before running /font return"
                    }
                    val boundResult = server.commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "font return")
                    helper.assertTrue(boundResult == 1, "Expected /font return to succeed for bound player")
                    waitUntil(helper, 200, "Expected /font return to clear player run binding", condition = {
                        client.pump(server)
                        val run = RunRegistry.get(runId)
                        run == null || (player.uuid !in run.activePlayers && player.uuid !in run.pendingPlayers)
                    }, onSuccess = {
                        client.close(server)
                        helper.succeed()
                    })
                })
            }
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    fun runtimeServiceListsAndFindsLoadedDimensionDrink(helper: GameTestHelper) {
        val server = helper.level.server
        val firstPos = helper.absolutePos(BlockPos(20, 2, 20))
        val secondPos = helper.absolutePos(BlockPos(24, 2, 20))
        placeChargedDefinitionObelisk(helper, firstPos, "end")
        placeChargedDefinitionObelisk(helper, secondPos, "nether")

        val loaded = ObeliskRuntimeService.listLoaded(server)
        helper.assertTrue(
            loaded.any { it.blockPos == firstPos } && loaded.any { it.blockPos == secondPos },
            "Expected runtime service listLoaded to include both placed dimension_drink"
        )

        val nearest = ObeliskRuntimeService.findNearestObelisk(helper.level, firstPos, 4)
        helper.assertTrue(nearest != null, "Expected runtime service to find nearest obelisk")
        helper.assertTrue(nearest!!.blockPos == firstPos, "Expected nearest loaded obelisk lookup to return the closest position")
        helper.succeed()
    }

    fun terrainClearingTaskIsRemoved(helper: GameTestHelper) {
        val center = helper.absolutePos(BlockPos(20, 3, 20))
        prepareGenerationSurface(helper, center)
        val obstructionLayerY = center.y + 1
        for (dx in -6..6) {
            for (dz in -6..6) {
                helper.level.setBlock(BlockPos(center.x + dx, obstructionLayerY, center.z + dz), Blocks.OBSIDIAN.defaultBlockState(), 3)
            }
        }

        ObeliskFeature.generateDefinitionSiteForTests(helper.level, center.above(12), "end", RandomSource.create(24680L))
        helper.succeed()
    }

    fun fontFluidTankAcceptsOnlyBloodMagicLifeEssence(helper: GameTestHelper) {
        val obeliskPos = helper.absolutePos(BlockPos(20, 2, 20))
        placeChargedDefinitionObelisk(helper, obeliskPos, "end")
        val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            ?: error("Expected obelisk block entity for fluid tank test")
        obelisk.setEnergyStoredForDebug(0)

        val handler = obelisk.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP).resolve().orElse(null)
        helper.assertTrue(handler != null, "Expected font to expose Forge fluid handler capability")
        val tank = handler ?: error("Expected non-null fluid handler after assertion")
        val lifeEssenceId = ResourceLocation("bloodmagic", "life_essence_fluid")
        val lifeEssence = ForgeRegistries.FLUIDS.getValue(lifeEssenceId)
            ?.takeIf { ForgeRegistries.FLUIDS.getKey(it) == lifeEssenceId || it.fluidType.descriptionId == "fluid.bloodmagic.life_essence_fluid" }
        val water = FluidStack(Fluids.WATER, 1_000)

        helper.assertTrue(tank.tanks == 1, "Expected font fluid handler to expose one internal tank")
        helper.assertTrue(tank.getTankCapacity(0) == obelisk.getModifiedMaxStorage(), "Expected fluid tank capacity to match font blood capacity")
        helper.assertTrue(!tank.isFluidValid(0, water), "Expected font fluid tank to reject water")
        helper.assertTrue(tank.fill(water, IFluidHandler.FluidAction.EXECUTE) == 0, "Expected water fill to be rejected")
        helper.assertTrue(obelisk.bloodStored.toInt() == 0, "Expected rejected fluid not to change font blood")
        if (lifeEssence == null) {
            helper.assertTrue(tank.getFluidInTank(0).isEmpty, "Expected missing Blood Magic runtime to report an empty fluid tank")
            helper.succeed()
            return
        }

        val blood = FluidStack(lifeEssence, 1_000)
        helper.assertTrue(tank.isFluidValid(0, blood), "Expected font fluid tank to accept Blood Magic life essence")
        helper.assertTrue(tank.fill(blood, IFluidHandler.FluidAction.SIMULATE) == 1_000, "Expected simulated life essence fill amount")
        helper.assertTrue(obelisk.bloodStored.toInt() == 0, "Expected simulated fill not to mutate font blood")
        helper.assertTrue(tank.fill(blood, IFluidHandler.FluidAction.EXECUTE) == 1_000, "Expected executed life essence fill")
        helper.assertTrue(obelisk.bloodStored.toInt() == 1_000, "Expected life essence fill to update font blood")
        helper.assertTrue(tank.getFluidInTank(0).fluid == lifeEssence, "Expected tank contents to report life essence")
        helper.assertTrue(tank.getFluidInTank(0).amount == 1_000, "Expected tank contents to mirror font blood")

        val simulatedDrain = tank.drain(400, IFluidHandler.FluidAction.SIMULATE)
        helper.assertTrue(simulatedDrain.fluid == lifeEssence && simulatedDrain.amount == 400, "Expected simulated drain to return life essence")
        helper.assertTrue(obelisk.bloodStored.toInt() == 1_000, "Expected simulated drain not to mutate font blood")
        val drained = tank.drain(FluidStack(lifeEssence, 400), IFluidHandler.FluidAction.EXECUTE)
        helper.assertTrue(drained.fluid == lifeEssence && drained.amount == 400, "Expected executed drain to return life essence")
        helper.assertTrue(obelisk.bloodStored.toInt() == 600, "Expected drain to reduce font blood")
        helper.assertTrue(tank.getFluidInTank(0).amount == 600, "Expected fluid tank amount to mirror drained font blood")
        helper.assertTrue(obelisk.drainBlood(125.0), "Expected run blood drain to consume life essence tank")
        helper.assertTrue(tank.getFluidInTank(0).amount == 475, "Expected run drain to reduce the same fluid tank")
        helper.assertTrue(obelisk.drainBlood(0.5), "Expected fractional run drain to be accepted")
        helper.assertTrue(tank.getFluidInTank(0).amount == 475, "Expected fractional run drain carry not to round up early")
        helper.assertTrue(obelisk.drainBlood(0.5), "Expected accumulated fractional run drain to be accepted")
        helper.assertTrue(tank.getFluidInTank(0).amount == 474, "Expected accumulated fractional run drain to reduce the fluid tank")
        helper.succeed()
    }

    fun fontRegenIgnoresAltarCopperOxidation(helper: GameTestHelper) {
        val obeliskPos = helper.absolutePos(BlockPos(20, 2, 20))
        val pedestalPos = obeliskPos.below()
        placeChargedDefinitionObelisk(helper, obeliskPos, "end")
        val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            ?: error("Expected obelisk block entity for cosmetic oxidation test")
        obelisk.setEnergyStoredForDebug(0)

        helper.level.setBlock(pedestalPos, Blocks.COPPER_BLOCK.defaultBlockState(), 3)
        val freshRate = obelisk.getModifiedRegenRate()
        val freshMultiplier = obelisk.getOxidationRegenMultiplier()

        helper.level.setBlock(pedestalPos, Blocks.WEATHERED_COPPER.defaultBlockState(), 3)
        val weatheredRate = obelisk.getModifiedRegenRate()
        val weatheredMultiplier = obelisk.getOxidationRegenMultiplier()

        helper.level.setBlock(pedestalPos, Blocks.OXIDIZED_COPPER.defaultBlockState(), 3)
        val oxidizedRate = obelisk.getModifiedRegenRate()
        val oxidizedMultiplier = obelisk.getOxidationRegenMultiplier()

        helper.assertTrue(freshMultiplier == 1.0, "Expected fresh copper altar support to be neutral")
        helper.assertTrue(weatheredMultiplier == 1.0, "Expected weathered copper to be cosmetic only")
        helper.assertTrue(oxidizedMultiplier == 1.0, "Expected oxidized copper to be cosmetic only")
        helper.assertTrue(freshRate > 0.0, "Expected font to have base regen")
        helper.assertTrue(weatheredRate == freshRate, "Expected weathered copper altar support not to change regen")
        helper.assertTrue(oxidizedRate == freshRate, "Expected oxidized copper altar support not to change regen")
        helper.succeed()
    }

    fun fontAxeScrapesAltarCopperOxidation(helper: GameTestHelper) {
        val server = helper.level.server
        val obeliskPos = helper.absolutePos(BlockPos(20, 2, 20))
        val pedestalPos = obeliskPos.below()
        placeChargedDefinitionObelisk(helper, obeliskPos, "end")
        helper.level.setBlock(pedestalPos, Blocks.OXIDIZED_COPPER.defaultBlockState(), 3)

        val client = connectHeadlessPlayer(helper)
        val player = client.player
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_AXE))
            val result = ModBlocks.OBELISK.get().use(
                helper.level.getBlockState(obeliskPos),
                helper.level,
                obeliskPos,
                player,
                InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3.atCenterOf(obeliskPos), Direction.UP, obeliskPos, false)
            )

            helper.assertTrue(result == InteractionResult.CONSUME, "Expected axe use on font to scrape altar copper")
            helper.assertTrue(helper.level.getBlockState(pedestalPos).`is`(Blocks.WEATHERED_COPPER), "Expected oxidized copper to scrape back to weathered copper")
            helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).damageValue == 1, "Expected successful scraping to damage the axe")
            helper.succeed()
        } finally {
            client.close(server)
        }
    }

    fun fontPassivelyRenewsNearbyCopperOxidation(helper: GameTestHelper) {
        val obeliskPos = helper.absolutePos(BlockPos(20, 2, 20))
        val pedestalPos = obeliskPos.below()
        placeChargedDefinitionObelisk(helper, obeliskPos, "end")
        val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            ?: error("Expected obelisk block entity for passive copper renewal test")
        helper.level.setBlock(pedestalPos, Blocks.OXIDIZED_COPPER.defaultBlockState(), 3)

        waitUntil(
            helper,
            220,
            "Expected charged font to passively renew a nearby oxidized copper block",
            condition = {
                if (helper.level.getBlockState(pedestalPos).`is`(Blocks.WEATHERED_COPPER)) {
                    true
                } else {
                    obelisk.renewNearbyCopperOxidation(helper.level) != null &&
                        helper.level.getBlockState(pedestalPos).`is`(Blocks.WEATHERED_COPPER)
                }
            }
        ) {
            helper.succeed()
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
        val recorder = HeadlessClientRecorder()
        val initialAddress = memoryChannels.computeIfAbsent(server) { serverConnectionListener.startMemoryChannel() }
        val initialAttempt = openLocalClient(serverConnectionListener, existingConnections, initialAddress, recorder)
        val connected = initialAttempt ?: run {
            memoryChannels.remove(server, initialAddress)
            val fallbackAddress = serverConnectionListener.startMemoryChannel()
            memoryChannels[server] = fallbackAddress
            openLocalClient(serverConnectionListener, existingConnections, fallbackAddress, recorder)
        } ?: error("Expected a new memory-channel server connection")
        val (clientConnection, serverConnection) = connected
        NetworkHooks.registerServerLoginChannel(serverConnection, ClientIntentionPacket("localhost", 0, ConnectionProtocol.LOGIN))

        val player = server.playerList.getPlayerForLogin(
            GameProfile(
                UUID.randomUUID(),
                "test-obelisk-${UUID.randomUUID().toString().substring(0, 8)}"
            )
        )
        val spawn = helper.absolutePos(BlockPos(1, 2, 1))
        player.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0.0F, 0.0F)
        server.playerList.placeNewPlayer(serverConnection, player)
        recorder.pump(clientConnection)
        return ConnectedTestClient(player, clientConnection, recorder)
    }

    private fun openLocalClient(
        serverConnectionListener: net.minecraft.server.network.ServerConnectionListener,
        existingConnections: Set<Connection>,
        address: SocketAddress,
        recorder: HeadlessClientRecorder
    ): Pair<Connection, Connection>? {
        val clientConnection = Connection.connectToLocalServer(address)
        clientConnection.setListener(recorder.listener)
        val serverConnection = waitForServerConnection(serverConnectionListener, existingConnections, clientConnection, recorder)
        if (serverConnection != null) {
            return clientConnection to serverConnection
        }
        clientConnection.disconnect(Component.literal("Headless GameTest connection bootstrap timed out"))
        clientConnection.handleDisconnection()
        return null
    }

    private fun waitForServerConnection(
        serverConnectionListener: net.minecraft.server.network.ServerConnectionListener,
        existingConnections: Set<Connection>,
        clientConnection: Connection,
        recorder: HeadlessClientRecorder
    ): Connection? {
        repeat(80) {
            serverConnectionListener.tick()
            recorder.pump(clientConnection)
            val serverConnection = serverConnectionListener.connections.firstOrNull { it !in existingConnections && it.isConnected }
            if (serverConnection != null) {
                return serverConnection
            }
            LockSupport.parkNanos(2_000_000L)
        }
        return null
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

    private fun countPlayerItems(player: ServerPlayer, item: net.minecraft.world.item.Item): Int {
        return player.inventory.items.filter { it.`is`(item) }.sumOf { it.count }
    }

    private fun countNearbyItems(level: Level, center: BlockPos, item: net.minecraft.world.item.Item, radius: Double): Int {
        val bounds = net.minecraft.world.phys.AABB(center).inflate(radius)
        return level.getEntitiesOfClass(ItemEntity::class.java, bounds)
            .filter { it.item.`is`(item) }
            .sumOf { it.item.count }
    }

    private fun assertGenericSpawnPlatform(helper: GameTestHelper, level: Level, returnPadPos: BlockPos) {
        helper.assertTrue(level.getBlockState(returnPadPos).`is`(ModBlocks.RETURN_FONT.get()), "Expected canonical spawn contract center to be a return font")
        helper.assertTrue(
            level.getBlockState(returnPadPos.below()).`is`(Blocks.OXIDIZED_COPPER),
            "Expected canonical spawn contract center support to remain an oxidized copper plate below the return font"
        )

        for (x in -2..2) {
            for (z in -2..2) {
                val floorPos = returnPadPos.below().offset(x, 0, z)
                val floorState = level.getBlockState(floorPos)
                helper.assertTrue(
                    floorState.`is`(Blocks.OXIDIZED_COPPER),
                    "Expected canonical spawn contract floor to stay fully oxidized around $floorPos"
                )

                for (dy in 1..3) {
                    val clearancePos = returnPadPos.offset(x, dy, z)
                    helper.assertTrue(
                        level.getBlockState(clearancePos).isAir,
                        "Expected canonical spawn contract clearance at $clearancePos"
                    )
                }
            }
        }
    }

    private fun assertGeneratedAltar(
        helper: GameTestHelper,
        fontPos: BlockPos,
        label: String,
        requireBroadLowerStep: Boolean = true,
        expectedTrophyOverride: net.minecraft.world.level.block.Block? = null,
        requireDimensionalTrophy: Boolean = false,
        requireReliquaryLandscaping: Boolean = true
    ) {
        val baseCenter = fontPos.below()
        val middleTierCenter = baseCenter.below()
        val lowerTierCenter = baseCenter.below(2)
        val cultivationFloorCenter = lowerTierCenter.below()
        val obelisk = helper.level.getBlockEntity(fontPos) as? ObeliskBlockEntity
        helper.assertTrue(helper.level.getBlockState(fontPos).`is`(ModBlocks.OBELISK.get()), "Expected $label cultivation center to place a dimensional font")
        val definitionBaseCapacity = obelisk?.definitionId?.let { ObeliskDataManager.getObelisk(it)?.maxBlood } ?: 15_000.0
        helper.assertTrue(
            (obelisk?.getMaxBlood() ?: 0.0) > definitionBaseCapacity,
            "Expected $label generated cultivation center font capacity to exceed definition base capacity"
        )
        helper.assertTrue(
            obelisk?.bloodStored == obelisk?.getModifiedMaxStorage()?.toDouble(),
            "Expected $label generated cultivation center font to be filled to its effective capacity"
        )
        helper.assertTrue(
            helper.level.getBlockState(baseCenter).`is`(Blocks.RAW_COPPER_BLOCK) ||
                helper.level.getBlockState(baseCenter).`is`(Blocks.COPPER_BLOCK) ||
                helper.level.getBlockState(baseCenter).`is`(Blocks.EXPOSED_COPPER) ||
                helper.level.getBlockState(baseCenter).`is`(Blocks.WEATHERED_COPPER) ||
                helper.level.getBlockState(baseCenter).`is`(Blocks.CUT_COPPER) ||
                helper.level.getBlockState(baseCenter).`is`(Blocks.EXPOSED_CUT_COPPER) ||
                helper.level.getBlockState(baseCenter).`is`(Blocks.WEATHERED_CUT_COPPER),
            "Expected $label font to sit on a copper-family pedestal, found ${helper.level.getBlockState(baseCenter)} at $baseCenter"
        )
        helper.assertTrue(!helper.level.getBlockState(baseCenter).isAir, "Expected $label altar cap not to float")
        helper.assertTrue(!helper.level.getBlockState(middleTierCenter).isAir, "Expected $label font to sit on an elevated altar middle tier")
        helper.assertTrue(!helper.level.getBlockState(lowerTierCenter).isAir, "Expected $label font to sit on an elevated altar lower tier")
        if (requireBroadLowerStep) {
            helper.assertTrue(!helper.level.getBlockState(lowerTierCenter.offset(3, 0, 0)).isAir, "Expected $label elevated altar to have a broad lower step")
            helper.assertTrue(!helper.level.getBlockState(lowerTierCenter.offset(-3, 0, 0)).isAir, "Expected $label elevated altar to have a broad lower step")
            helper.assertTrue(!helper.level.getBlockState(lowerTierCenter.offset(0, 0, 3)).isAir, "Expected $label elevated altar to have a broad lower step")
            helper.assertTrue(!helper.level.getBlockState(lowerTierCenter.offset(0, 0, -3)).isAir, "Expected $label elevated altar to have a broad lower step")
        }
        for (dy in 1..3) {
            helper.assertTrue(helper.level.getBlockState(fontPos.above(dy)).isAir, "Expected $label font to keep clear space above it")
        }
        val altarCenter = middleTierCenter
        val shardTorch = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation("undergarden", "shard_torch")
        )
        val exposedCopperLantern = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation("everythingcopper", "exposed_copper_lantern")
        )
        val weatheredCopperLantern = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation("everythingcopper", "weathered_copper_lantern")
        )
        val sconce = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation("supplementaries", "sconce")
        )
        val wallSconce = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation("supplementaries", "sconce_wall")
        )
        fun isLantern(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            state.`is`(Blocks.SOUL_LANTERN) || state.`is`(Blocks.LANTERN) || state.`is`(exposedCopperLantern) || state.`is`(weatheredCopperLantern)
        fun isAltarLight(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            isLantern(state) || state.`is`(sconce) || state.`is`(wallSconce)
        val copperShingleSlab = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation("create", "exposed_copper_shingle_slab")
        )
        val copperShingleStairs = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation("create", "exposed_copper_shingle_stairs")
        )
        fun isAltarRoofBlock(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            state.`is`(Blocks.CUT_COPPER_SLAB) ||
                state.`is`(Blocks.CUT_COPPER_STAIRS) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER_SLAB) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER_STAIRS) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER_SLAB) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER_STAIRS) ||
                state.`is`(copperShingleSlab) ||
                state.`is`(copperShingleStairs)
        listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2).forEach { (dx, dz) ->
            val supportPos = altarCenter.offset(dx, 3, dz)
            helper.assertTrue(
                helper.level.getBlockState(supportPos).`is`(Blocks.STRIPPED_WARPED_STEM),
                "Expected $label altar corner supports to use warped cultivation posts at $supportPos"
            )
            helper.assertTrue(
                helper.level.getBlockState(supportPos.below()).`is`(Blocks.STRIPPED_WARPED_STEM),
                "Expected $label altar corner supports to continue down to the font surround at ${supportPos.below()}"
            )
        }
        for (dx in -2..2) {
            for (dz in -2..2) {
                if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dz)) != 2) continue
                val roof = altarCenter.offset(dx, 4, dz)
                helper.assertTrue(
                    isAltarRoofBlock(helper.level.getBlockState(roof)),
                    "Expected $label altar to have a copper roof ring at $roof"
                )
            }
        }
        var sideLights = 0
        listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2).forEach { (dx, dz) ->
            val outwardX = if (dx < 0) -1 else 1
            val outwardZ = if (dz < 0) -1 else 1
            val xFace = helper.level.getBlockState(altarCenter.offset(dx + outwardX, 3, dz))
            val zFace = helper.level.getBlockState(altarCenter.offset(dx, 3, dz + outwardZ))
            if (isAltarLight(xFace)) {
                sideLights++
            }
            if (isAltarLight(zFace)) {
                sideLights++
            }
        }
        helper.assertTrue(
            sideLights >= 4,
            "Expected $label altar to keep visible outer-face lighting around the top cultivation supports"
        )
        if (!requireReliquaryLandscaping) return
        var cultivationSignals = 0
        var pathSignals = 0
        var trophySignals = 0
        var trophyGroundSignals = 0
        var cappedTrophySignals = 0
        var forbiddenSignals = 0
        var unlitCandleSignals = 0
        var structureSignals = 0
        var slabStepSignals = 0
        val pathDirections = mutableSetOf<Direction>()
        val generatedFootprint = mutableSetOf<Pair<Int, Int>>()
        val generatedTerrainLevels = mutableSetOf<Int>()
        val expectedTrophy = expectedTrophyOverride ?: when (label) {
            "end" -> Blocks.WHITE_CANDLE
            "nether" -> shardTorch
            "modded" -> Blocks.MAGENTA_CANDLE
            else -> null
        }
        fun isGeneratedCultivationMarker(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            state.`is`(Blocks.COPPER_BLOCK) ||
                state.`is`(Blocks.EXPOSED_COPPER) ||
                state.`is`(Blocks.WEATHERED_COPPER) ||
                state.`is`(Blocks.RAW_COPPER_BLOCK) ||
                state.`is`(Blocks.CUT_COPPER) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER) ||
                state.`is`(Blocks.DARK_OAK_WALL_SIGN)
        fun isPalettedCultivationMarker(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            isGeneratedCultivationMarker(state) && !state.`is`(Blocks.MUD)
        fun isValidCultivationBed(pos: BlockPos): Boolean =
            Direction.Plane.HORIZONTAL.any { direction ->
                val forwardBody = helper.level.getBlockState(pos.relative(direction)).`is`(Blocks.MUD) &&
                    isPalettedCultivationMarker(helper.level.getBlockState(pos.relative(direction, 2).above()))
                val middleBody = helper.level.getBlockState(pos.relative(direction.opposite)).`is`(Blocks.MUD) &&
                    isPalettedCultivationMarker(helper.level.getBlockState(pos.relative(direction).above()))
                forwardBody || middleBody
            }
        fun isGeneratedStructureSignal(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            isGeneratedCultivationMarker(state) ||
                state.`is`(Blocks.COPPER_BLOCK) ||
                state.`is`(Blocks.EXPOSED_COPPER) ||
                state.`is`(Blocks.WEATHERED_COPPER) ||
                state.`is`(Blocks.RAW_COPPER_BLOCK) ||
                state.`is`(Blocks.CUT_COPPER) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER) ||
                state.`is`(Blocks.CUT_COPPER_SLAB) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER_SLAB) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER_SLAB) ||
                state.`is`(Blocks.CUT_COPPER_STAIRS) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER_STAIRS) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER_STAIRS) ||
                state.`is`(Blocks.STRIPPED_WARPED_STEM) ||
                state.`is`(Blocks.SOUL_LANTERN) ||
                state.`is`(Blocks.LANTERN)
        fun isGeneratedPathOrFloor(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            state.`is`(Blocks.PACKED_MUD) ||
                state.`is`(Blocks.CUT_COPPER) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER) ||
                state.`is`(Blocks.COPPER_BLOCK) ||
                state.`is`(Blocks.EXPOSED_COPPER) ||
                state.`is`(Blocks.WEATHERED_COPPER) ||
                state.`is`(Blocks.RAW_COPPER_BLOCK)
        fun isCopperCourtFloor(state: net.minecraft.world.level.block.state.BlockState): Boolean =
            state.`is`(Blocks.CUT_COPPER) ||
                state.`is`(Blocks.EXPOSED_CUT_COPPER) ||
                state.`is`(Blocks.WEATHERED_CUT_COPPER) ||
                state.`is`(Blocks.COPPER_BLOCK) ||
                state.`is`(Blocks.EXPOSED_COPPER) ||
                state.`is`(Blocks.WEATHERED_COPPER) ||
                state.`is`(Blocks.RAW_COPPER_BLOCK)
        fun isLivingCourtPot(state: net.minecraft.world.level.block.state.BlockState): Boolean {
            if (state.`is`(Blocks.FLOWER_POT)) return false
            val path = BuiltInRegistries.BLOCK.getKey(state.block).path
            return path == "potted_dead_bush" || path.startsWith("potted_")
        }
        fun columnHas(dx: Int, dz: Int, minDy: Int, maxDy: Int, predicate: (net.minecraft.world.level.block.state.BlockState) -> Boolean): Boolean =
            (minDy..maxDy).any { dy -> predicate(helper.level.getBlockState(cultivationFloorCenter.offset(dx, dy, dz))) }
        fun detailWeight(pos: BlockPos): Int {
            val state = helper.level.getBlockState(pos)
            var weight = 0
            if (isGeneratedCultivationMarker(state)) weight++
            if (state.`is`(Blocks.WHITE_CANDLE) || state.`is`(Blocks.LIME_CANDLE) || state.`is`(shardTorch) || state.`is`(Blocks.OAK_LOG) || state.`is`(Blocks.SPRUCE_LOG)) weight++
            if (expectedTrophy != null && state.`is`(expectedTrophy)) weight += 2
            return weight
        }
        for (dx in -20..20) {
            for (dz in -20..20) {
                for (dy in -12..16) {
	                    val pos = cultivationFloorCenter.offset(dx, dy, dz)
	                    val state = helper.level.getBlockState(pos)
	                    val generatedMarker = isGeneratedCultivationMarker(state)
	                    val generatedFloor = isGeneratedPathOrFloor(state)
		                    val generatedCultivationBed = state.`is`(Blocks.MUD) && isValidCultivationBed(pos)
	                    val generatedTrophy = expectedTrophy != null && state.`is`(expectedTrophy)
	                    if (generatedMarker || generatedFloor || generatedTrophy || generatedCultivationBed) {
	                        generatedFootprint += dx to dz
	                        generatedTerrainLevels += pos.y
	                    }
	                    if (isGeneratedStructureSignal(state)) structureSignals++
	                    if (state.`is`(Blocks.CUT_COPPER_SLAB) || state.`is`(Blocks.EXPOSED_CUT_COPPER_SLAB) || state.`is`(Blocks.WEATHERED_CUT_COPPER_SLAB)) slabStepSignals++
	                    if (generatedMarker) {
	                        cultivationSignals++
	                    }
	                    if (generatedCultivationBed) {
	                        cultivationSignals++
	                    }
	                    if (generatedFloor) {
	                        pathSignals++
	                        if (abs(dx) > abs(dz)) {
                            pathDirections += if (dx > 0) Direction.EAST else Direction.WEST
                        } else if (dz != 0) {
                            pathDirections += if (dz > 0) Direction.SOUTH else Direction.NORTH
                        }
                    }
                    if (generatedTrophy) {
                        trophySignals++
                        if (pos.y <= cultivationFloorCenter.y) trophyGroundSignals++
                        val aboveTrophy = helper.level.getBlockState(pos.above())
                        if (
                            aboveTrophy.`is`(Blocks.WAXED_EXPOSED_CUT_COPPER) ||
                            aboveTrophy.`is`(Blocks.CUT_COPPER) ||
                            aboveTrophy.`is`(Blocks.EXPOSED_CUT_COPPER) ||
                            aboveTrophy.`is`(Blocks.WEATHERED_CUT_COPPER)
                        ) {
                            cappedTrophySignals++
                        }
                    }
                    if (state.`is`(Blocks.WITHER_ROSE)) {
                        forbiddenSignals++
                    }
                    if ((state.`is`(Blocks.WHITE_CANDLE) || state.`is`(Blocks.LIME_CANDLE) || state.`is`(Blocks.CANDLE)) && !state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
                        unlitCandleSignals++
                    }
                }
            }
        }
        val tileDetailCounts = mutableListOf<Int>()
        for (sx in -20..20 step 4) {
            for (sz in -20..20 step 4) {
                var localDetails = 0
                for (dx in sx - 2..sx + 2) {
                    for (dz in sz - 2..sz + 2) {
                        for (dy in -1..3) {
                            localDetails += detailWeight(cultivationFloorCenter.offset(dx, dy, dz))
                        }
                    }
                }
                tileDetailCounts += localDetails
            }
        }
        var courtCopperInterior = 0
        var courtMudInterior = 0
        var courtCornerSignals = 0
        var courtEntrySignals = 0
        var perimeterPotSignals = 0
        var centralPotSignals = 0
        var livingPotSignals = 0
        for (dx in -6..6) {
            for (dz in -6..6) {
                if (maxOf(abs(dx), abs(dz)) <= 5) {
                    if (columnHas(dx, dz, -1, 4, ::isCopperCourtFloor)) courtCopperInterior++
                    if (columnHas(dx, dz, -1, 4) { it.`is`(Blocks.PACKED_MUD) }) courtMudInterior++
                }
                if (abs(dx) == 5 && abs(dz) == 5 && columnHas(dx, dz, -1, 4, ::isCopperCourtFloor)) {
                    courtCornerSignals++
                }
                if (maxOf(abs(dx), abs(dz)) <= 2 && columnHas(dx, dz, 0, 4, ::isLivingCourtPot)) {
                    centralPotSignals++
                }
                if (maxOf(abs(dx), abs(dz)) in 4..6 && columnHas(dx, dz, 0, 4, ::isLivingCourtPot)) {
                    perimeterPotSignals++
                }
                if (columnHas(dx, dz, 0, 4, ::isLivingCourtPot)) livingPotSignals++
            }
        }
        Direction.Plane.HORIZONTAL.forEach { direction ->
            val hasInteriorCopper = (4..6).any { step ->
                val probe = when (direction) {
                    Direction.NORTH -> 0 to -step
                    Direction.SOUTH -> 0 to step
                    Direction.WEST -> -step to 0
                    Direction.EAST -> step to 0
                    else -> 0 to 0
                }
                listOf(-1, 0, 1).any { side ->
                    val sample = if (direction.axis == Direction.Axis.X) probe.first to probe.second + side else probe.first + side to probe.second
                    columnHas(sample.first, sample.second, -1, 4, ::isCopperCourtFloor)
                }
            }
            val hasExteriorMud = (7..9).any { step ->
                val probe = when (direction) {
                    Direction.NORTH -> 0 to -step
                    Direction.SOUTH -> 0 to step
                    Direction.WEST -> -step to 0
                    Direction.EAST -> step to 0
                    else -> 0 to 0
                }
                listOf(-1, 0, 1).any { side ->
                    val sample = if (direction.axis == Direction.Axis.X) probe.first to probe.second + side else probe.first + side to probe.second
                    columnHas(sample.first, sample.second, -1, 3) { it.`is`(Blocks.PACKED_MUD) }
                }
            }
            if (hasInteriorCopper && hasExteriorMud) courtEntrySignals++
        }
        helper.assertTrue(pathSignals >= 8, "Expected $label reliquary to include readable processional path/floor tiles")
        helper.assertTrue(cultivationSignals >= 6, "Expected $label reliquary to include generated cultivation markers")
        helper.assertTrue(structureSignals >= 8, "Expected $label reliquary to include altar and ritual structure signals")
        helper.assertTrue(courtCopperInterior >= 36, "Expected $label reliquary court interior to be copper-dominant")
        helper.assertTrue(courtCopperInterior > courtMudInterior, "Expected $label reliquary court to read as a built square instead of a mud crossroads")
        helper.assertTrue(courtCornerSignals >= 3, "Expected $label reliquary court to keep a strong framed edge at the corners")
        helper.assertTrue(courtEntrySignals >= 2, "Expected $label reliquary court to blend at least two real path entries from mud into copper")
        if (label != "modded") {
            helper.assertTrue(perimeterPotSignals >= 1, "Expected $label reliquary court to reserve decorative pots for perimeter pockets")
        }
        helper.assertTrue(centralPotSignals == 0, "Expected $label reliquary court center to stay clear of decorative pots")
        if (label == "modded") {
            helper.assertTrue(generatedTerrainLevels.size >= 1, "Expected modded reliquary to occupy generated terrain")
        }
        if (generatedFootprint.size >= 24) {
            val minX = generatedFootprint.minOf { it.first }
            val maxX = generatedFootprint.maxOf { it.first }
            val minZ = generatedFootprint.minOf { it.second }
            val maxZ = generatedFootprint.maxOf { it.second }
            val boxArea = (maxX - minX + 1) * (maxZ - minZ + 1)
            val fillRatio = generatedFootprint.size.toDouble() / boxArea.toDouble()
            val spansBroadCultivationCenter = (maxX - minX + 1) >= 24 && (maxZ - minZ + 1) >= 24
            if (spansBroadCultivationCenter && label != "nether") {
                helper.assertTrue(
                    fillRatio <= 0.62,
                    "Expected $label cultivation center footprint to be labyrinthine/organic, not a filled square mask " +
                        "(fillRatio=$fillRatio footprint=${generatedFootprint.size} boxArea=$boxArea bounds=[$minX,$maxX]x[$minZ,$maxZ])"
                )
            }
        }
        helper.assertTrue(tileDetailCounts.maxOrNull() ?: 0 >= 3, "Expected $label reliquary to include at least one readable detail cluster")
        helper.assertTrue(pathDirections.size >= 2, "Expected $label reliquary paths to reach multiple directions")
        if (requireDimensionalTrophy && expectedTrophy != null) {
            helper.assertTrue(trophySignals >= 1, "Expected $label cultivation center to display dimensional trophy blocks")
        }
        if (expectedTrophy != null && trophySignals > 0) {
            helper.assertTrue(cappedTrophySignals == 0, "Expected $label dimensional trophy blocks to remain uncapped")
        }
        helper.assertTrue(forbiddenSignals == 0, "Expected $label cultivation center to avoid wither roses")
        helper.assertTrue(unlitCandleSignals == 0, "Expected $label generated cultivation center candles to be lit")
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

    private fun rewardOnlyRun(helper: GameTestHelper, obeliskPos: BlockPos, definition: ObeliskDefinition, survivorId: UUID): RunRecord {
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
            participants = linkedSetOf(survivorId),
            survivors = linkedSetOf(survivorId),
            state = RunState.FINISHING
        )
    }

    private fun waitForPreparedTemplate(helper: GameTestHelper, templateId: String, onReady: () -> Unit) {
        waitUntil(
            helper = helper,
            remainingTicks = 1200,
            failureMessage = {
                "Expected canonical target for $templateId | prepared=${RunRegistry.describePreparedInstances()}"
            },
            condition = {
                RunRegistry.isPreparedInstanceReady(templateId)
            },
            onSuccess = onReady
        )
    }

    private fun beginRunOrFail(server: net.minecraft.server.MinecraftServer, definitionId: String): com.bettercontent.dimensiondrink.api.RunHandle {
        return when (val result = RunRegistry.beginRun(server, UUID.randomUUID(), definitionId)) {
            is RunBeginResult.Accepted -> result.run
            is RunBeginResult.Rejected -> error("Expected beginRun to succeed for '$definitionId': ${result.reason}")
        }
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
        helper.level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3)
        helper.level.setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), 3)
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
        for (dx in -20..20) {
            for (dz in -20..20) {
                for (y in helper.level.minBuildHeight..(center.y + 12)) {
                    val dy = y - center.y
                    val state = when {
                        dy <= -3 -> Blocks.STONE.defaultBlockState()
                        dy == -2 -> Blocks.DIRT.defaultBlockState()
                        dy == -1 -> Blocks.GRASS_BLOCK.defaultBlockState()
                        else -> Blocks.AIR.defaultBlockState()
                    }
                    helper.level.setBlock(BlockPos(center.x + dx, y, center.z + dz), state, 3)
                }
            }
        }
    }

    private fun chunkInteriorTestAnchor(pos: BlockPos): BlockPos {
        val chunkMinX = Math.floorDiv(pos.x, 16) * 16
        val chunkMinZ = Math.floorDiv(pos.z, 16) * 16
        return BlockPos(chunkMinX + 8, pos.y, chunkMinZ + 8)
    }

    private fun prepareCliffsideGenerationSurface(helper: GameTestHelper, center: BlockPos) {
        for (dx in -28..28) {
            for (dz in -28..28) {
                val ridge = Math.floorDiv(dx + 28, 8)
                val contour = Math.floorDiv(dz + 16, 12)
                val notch = if (Math.floorMod(dx + dz, 11) < 4) -1 else 0
                val distanceFromCenter = maxOf(abs(dx), abs(dz))
                val cliffSurfaceY = center.y - 1 + ridge + contour + notch
                val surfaceY = when {
                    distanceFromCenter <= 4 -> center.y - 1
                    distanceFromCenter <= 7 -> center.y + Math.floorDiv(distanceFromCenter - 5, 2)
                    else -> cliffSurfaceY
                }
                for (y in helper.level.minBuildHeight..(center.y + 24)) {
                    val state = when {
                        y < surfaceY - 2 -> Blocks.STONE.defaultBlockState()
                        y < surfaceY -> Blocks.DIRT.defaultBlockState()
                        y == surfaceY -> Blocks.GRASS_BLOCK.defaultBlockState()
                        else -> Blocks.AIR.defaultBlockState()
                    }
                    helper.level.setBlock(BlockPos(center.x + dx, y, center.z + dz), state, 3)
                }
            }
        }
    }

    private fun placeLeafPlacementNoise(helper: GameTestHelper, center: BlockPos) {
        for (dx in -1..1) {
            for (dz in -1..1) {
                helper.level.setBlock(center.offset(dx, 0, dz), Blocks.OAK_LEAVES.defaultBlockState(), 3)
            }
        }
    }

    private fun placeFoliagePlacementNoise(helper: GameTestHelper, center: BlockPos) {
        val foliage = listOf(Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH)
        for (dx in -1..1) {
            for (dz in -1..1) {
                val block = foliage[Math.floorMod(dx * 5 + dz, foliage.size)]
                helper.level.setBlock(center.offset(dx, 0, dz), block.defaultBlockState(), 3)
            }
        }
    }

    private fun prepareUnderwaterGenerationSurface(helper: GameTestHelper, center: BlockPos, waterDepth: Int): Int {
        val waterTopY = center.y + waterDepth
        for (dx in -20..20) {
            for (dz in -20..20) {
                for (y in helper.level.minBuildHeight..(center.y + 12)) {
                    val dy = y - center.y
                    val state = when {
                        dy <= -3 -> Blocks.STONE.defaultBlockState()
                        dy == -2 -> Blocks.DIRT.defaultBlockState()
                        dy == -1 -> Blocks.SAND.defaultBlockState()
                        y <= waterTopY -> Blocks.WATER.defaultBlockState()
                        else -> Blocks.AIR.defaultBlockState()
                    }
                    helper.level.setBlock(BlockPos(center.x + dx, y, center.z + dz), state, 3)
                }
            }
        }
        return waterTopY
    }

    private fun assertNoGeneratedSkyBlocks(helper: GameTestHelper, center: BlockPos, radius: Int, minY: Int, label: String) {
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                for (y in minY..(helper.level.maxBuildHeight - 1)) {
                    val pos = BlockPos(center.x + dx, y, center.z + dz)
                    val state = helper.level.getBlockState(pos)
                    helper.assertTrue(state.isAir, "Expected $label generation not to leave stray sky blocks at $pos, found ${state.block.descriptionId}")
                }
            }
        }
    }

    private fun countAirBelowWaterline(
        helper: GameTestHelper,
        center: BlockPos,
        radius: Int,
        minY: Int,
        waterTopY: Int
    ): Int {
        var count = 0
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if ((dx * dx) + (dz * dz) > radius * radius) continue
                for (y in minY.coerceAtLeast(helper.level.minBuildHeight)..waterTopY) {
                    val pos = BlockPos(center.x + dx, y, center.z + dz)
                    if (helper.level.getBlockState(pos).isAir) {
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun locateGeneratedObeliskPos(helper: GameTestHelper, center: BlockPos, definitionId: String? = null): BlockPos? {
        var best: BlockPos? = null
        var bestScore = Int.MAX_VALUE
        for (dy in -64..96) {
            for (dx in -8..8) {
                for (dz in -8..8) {
                    val candidate = center.offset(dx, dy, dz)
                    val blockEntity = helper.level.getBlockEntity(candidate) as? ObeliskBlockEntity
                    val isObelisk = blockEntity != null || helper.level.getBlockState(candidate).`is`(ModBlocks.OBELISK.get())
                    val matchesDefinition = definitionId == null || blockEntity?.definitionId == definitionId
                    if (isObelisk && matchesDefinition) {
                        val score = dx * dx + dz * dz + abs(dy)
                        if (score < bestScore) {
                            best = candidate
                            bestScore = score
                        }
                    }
                }
            }
        }
        return best
    }

    private fun locateGeneratedObeliskPosInArea(helper: GameTestHelper, center: BlockPos, horizontalRadius: Int, definitionId: String? = null): BlockPos? {
        var best: BlockPos? = null
        var bestScore = Int.MAX_VALUE
        for (dy in -96..96) {
            for (dx in -horizontalRadius..horizontalRadius) {
                for (dz in -horizontalRadius..horizontalRadius) {
                    val candidate = center.offset(dx, dy, dz)
                    val blockEntity = helper.level.getBlockEntity(candidate) as? ObeliskBlockEntity
                    val isObelisk = blockEntity != null || helper.level.getBlockState(candidate).`is`(ModBlocks.OBELISK.get())
                    val matchesDefinition = definitionId == null || blockEntity?.definitionId == definitionId
                    if (isObelisk && matchesDefinition) {
                        val score = dx * dx + dz * dz + abs(dy)
                        if (score < bestScore) {
                            best = candidate
                            bestScore = score
                        }
                    }
                }
            }
        }
        return best
    }

    private fun clearGeneratedDimensionDrinkInArea(helper: GameTestHelper, center: BlockPos, horizontalRadius: Int) {
        for (dy in -96..96) {
            for (dx in -horizontalRadius..horizontalRadius) {
                for (dz in -horizontalRadius..horizontalRadius) {
                    val candidate = center.offset(dx, dy, dz)
                    if (helper.level.getBlockState(candidate).`is`(ModBlocks.OBELISK.get())) {
                        helper.level.setBlock(candidate, Blocks.AIR.defaultBlockState(), 3)
                        helper.level.removeBlockEntity(candidate)
                    }
                }
            }
        }
    }

    private fun writeDefinition(definition: ObeliskDefinition) {
        writeJsonFile(ObeliskDataManager.definitionsPath().resolve("${definition.id}.json"), definition)
    }

    private fun writeDefinitionOverride(fileName: String, definition: ObeliskDefinition) {
        writeJsonFile(ObeliskDataManager.definitionsPath().resolve(fileName), definition)
    }

    private fun writeInstanceTemplate(template: InstanceTemplate) {
        writeJsonFile(InstanceTemplateDataManager.templatesPath().resolve("${template.id}.json"), template)
    }

    private fun writeRewardTable(table: RewardTableDefinition) {
        writeJsonFile(ObeliskDataManager.rewardsPath().resolve("${table.id}.json"), table)
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

    private fun configureActivationTestDimensionDrink(server: net.minecraft.server.MinecraftServer) {
        deleteTestConfigs()
        writeDefinitionOverride(
            "test_activation_nether.json",
            ObeliskDefinition(
                id = "nether",
                displayName = "Nether Obelisk",
                instanceTemplateId = "nether",
                requiredNamespace = "minecraft",
                enabled = false,
                worldgenWeight = 0.0,
                rewardTableId = "nether"
            )
        )
        reloadDataWithCommand(server)
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
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "font reload_data")
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
        @Volatile
        private var clientConnection: Connection? = null
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
                "handleKeepAlive" -> {
                    val packet = args!![0] as ClientboundKeepAlivePacket
                    clientConnection?.send(ServerboundKeepAlivePacket(packet.id))
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
                "shouldPropagateHandlingExceptions" -> true
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
            clientConnection = connection
            connection.tick()
        }
    }
}
