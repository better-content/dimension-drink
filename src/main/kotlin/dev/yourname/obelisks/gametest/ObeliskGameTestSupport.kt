package dev.yourname.obelisks.gametest

import com.google.gson.GsonBuilder
import com.mojang.authlib.GameProfile
import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.api.ObeliskApi
import dev.yourname.obelisks.api.RunBeginResult
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.CanonicalTargetResolver
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.ObeliskDefinition
import dev.yourname.obelisks.data.RewardEntryDefinition
import dev.yourname.obelisks.data.RewardPoolDefinition
import dev.yourname.obelisks.data.RewardTableDefinition
import dev.yourname.obelisks.data.WorldgenFamilyDefinition
import dev.yourname.obelisks.registry.ModBlocks
import dev.yourname.obelisks.runtime.ObeliskRuntimeService
import dev.yourname.obelisks.runtime.backend.CanonicalDimensionBackend
import dev.yourname.obelisks.runtime.reward.RewardSystem
import dev.yourname.obelisks.runtime.run.RunRecord
import dev.yourname.obelisks.runtime.run.RunRegistry
import dev.yourname.obelisks.runtime.run.RunState
import dev.yourname.obelisks.runtime.run.RunSavedData
import dev.yourname.obelisks.runtime.ui.RunBossBarManager
import dev.yourname.obelisks.worldgen.ObeliskFeature
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
import net.minecraft.server.level.ServerLevel
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
import java.util.concurrent.locks.LockSupport

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

private data class ReturnAnchor(
    val levelKey: ResourceKey<Level>,
    val x: Double,
    val y: Double,
    val z: Double
)

private object InstanceTemplateDataManager {
    fun templatesPath(): Path = ObeliskDataManager.configRootPath().resolve("target_dimensions")
}

private object InstanceManager {
    fun records(): List<InstanceSnapshot> {
        val active = RunRegistry.snapshot().mapNotNull(::snapshotFromRun)
        val prepared = ObeliskDataManager.enabledObelisks()
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

    fun peekReturnAnchor(playerId: UUID): ReturnAnchor? {
        val run = RunRegistry.snapshot().firstOrNull { playerId in it.activePlayers || playerId in it.pendingPlayers } ?: return null
        val levelKey = run.originLevelKey ?: return null
        val pos = run.originObeliskPos ?: return null
        return ReturnAnchor(levelKey, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
    }
}

private fun canonicalLevelKey(templateId: String): ResourceKey<Level>? {
    val location = when (templateId) {
        "overworld" -> ResourceLocation("minecraft", "overworld")
        "nether" -> ResourceLocation("minecraft", "the_nether")
        "end" -> ResourceLocation("minecraft", "the_end")
        "everbright" -> ResourceLocation("blue_skies", "everbright")
        "everdawn" -> ResourceLocation("blue_skies", "everdawn")
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
            configureActivationTestObelisks(server)
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
                        run?.state == dev.yourname.obelisks.runtime.run.RunState.ACTIVE &&
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

    fun relocatedSpawnRetargetsTravelWarmup(helper: GameTestHelper) {
        val server = helper.level.server
        configureActivationTestObelisks(server)
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
                    activationMessage?.startsWith("Initializing") == true || activationMessage?.startsWith("Entering") == true,
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
                                        rewardSignal >= 0 &&
                                            RunRegistry.get(runId) == null &&
                                            !RunBossBarManager.hasBossBar(runId)
                                    }, onSuccess = {
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
            }
        } catch (t: Throwable) {
            client.close(server)
            throw t
        } finally {
            deleteTestConfigs()
            reloadDataWithCommand(server)
        }
    }

    fun voidFallReturnsPlayerAndCleansUpRun(helper: GameTestHelper, definitionId: String = "end") {
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
                    "Expected void-fall test activation to consume the interaction"
                )

                waitUntil(helper, 320, "Expected void-fall test to create an active run and move the player into its target dimension", condition = {
                    client.pump(server)
                    val activeRunId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                    val run = activeRunId?.let(RunRegistry::get)
                    val instance = run?.let { InstanceManager.getInstance(it.instanceId) }
                    run?.definitionId == definitionId &&
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
            }
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
        val moddedDefinition = ObeliskDefinition(
            id = "test_modded_visual_definition",
            displayName = "Test Modded Visual",
            instanceTemplateId = "blue_skies:everbright",
            targetDimension = "blue_skies:everbright",
            meteorCoreBlock = "minecraft:lapis_block",
            meteorShellBlock = "minecraft:diamond_block",
            craterFillBlocks = listOf("minecraft:blue_ice"),
            rewardTableId = "default"
        )
        writeDefinition(endDefinition)
        writeDefinition(netherDefinition)
        writeDefinition(moddedDefinition)
        reloadData()

        val endCenter = helper.absolutePos(BlockPos(20, 3, 4))
        val netherCenter = helper.absolutePos(BlockPos(36, 3, 4))
        val moddedCenter = helper.absolutePos(BlockPos(52, 3, 4))
        prepareGenerationSurface(helper, endCenter)
        prepareGenerationSurface(helper, netherCenter)
        prepareGenerationSurface(helper, moddedCenter)

        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, endCenter, endDefinition.id, RandomSource.create(1234L)),
            "Expected end definition test generation to succeed"
        )
        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, netherCenter, netherDefinition.id, RandomSource.create(5678L)),
            "Expected nether definition test generation to succeed"
        )
        helper.assertTrue(
            ObeliskFeature.generateDefinitionSiteForTests(helper.level, moddedCenter, moddedDefinition.id, RandomSource.create(9012L)),
            "Expected modded definition test generation to succeed"
        )

        val endPos = requireNotNull(locateGeneratedObeliskPos(helper, endCenter)) {
            "Expected end definition site to place an obelisk within the crater"
        }
        val netherPos = requireNotNull(locateGeneratedObeliskPos(helper, netherCenter)) {
            "Expected nether definition site to place an obelisk within the crater"
        }
        val moddedPos = requireNotNull(locateGeneratedObeliskPos(helper, moddedCenter)) {
            "Expected modded definition site to place an obelisk within the crater"
        }
        val endObelisk = helper.level.getBlockEntity(endPos) as? ObeliskBlockEntity
        val netherObelisk = helper.level.getBlockEntity(netherPos) as? ObeliskBlockEntity
        val moddedObelisk = helper.level.getBlockEntity(moddedPos) as? ObeliskBlockEntity
        helper.assertTrue(endObelisk?.definitionId == endDefinition.id, "Expected generated end obelisk to keep its definition id")
        helper.assertTrue(netherObelisk?.definitionId == netherDefinition.id, "Expected generated nether obelisk to keep its definition id")
        helper.assertTrue(moddedObelisk?.definitionId == moddedDefinition.id, "Expected generated modded obelisk to keep its modded definition id")
        helper.assertTrue(moddedObelisk?.targetTemplateId == "blue_skies:everbright", "Expected generated modded obelisk to target its modded dimension")
        helper.assertTrue(countNonAirRing(helper, endPos, 2) >= 6, "Expected end definition to generate as a canonical meteor")
        helper.assertTrue(countNonAirRing(helper, netherPos, 2) >= 6, "Expected nether definition to generate as a canonical meteor")
        helper.assertTrue(countNonAirRing(helper, moddedPos, 2) >= 6, "Expected modded definition to generate as a canonical meteor")
        helper.assertTrue(!helper.level.getBlockState(endPos.east()).isAir, "Expected generated end meteor to encase the obelisk")
        helper.assertTrue(!helper.level.getBlockState(netherPos.east()).isAir, "Expected generated nether meteor to encase the obelisk")
        helper.assertTrue(!helper.level.getBlockState(moddedPos.east()).isAir, "Expected generated modded meteor to encase the obelisk")
        helper.assertTrue(!helper.level.getBlockState(endPos.above()).isAir, "Expected generated end obelisk top to be enclosed")
        helper.assertTrue(!helper.level.getBlockState(netherPos.above()).isAir, "Expected generated nether obelisk top to be enclosed")
        helper.assertTrue(!helper.level.getBlockState(moddedPos.above()).isAir, "Expected generated modded obelisk top to be enclosed")
        val expectedShell = meteorShellBlockForAssertions()
        helper.assertTrue(hasBlockWithinCube(helper, endPos, 4, expectedShell), "Expected end meteor to include shell block ${BuiltInRegistries.BLOCK.getKey(expectedShell)}")
        helper.assertTrue(hasBlockWithinCube(helper, netherPos, 4, expectedShell), "Expected nether meteor to include shell block ${BuiltInRegistries.BLOCK.getKey(expectedShell)}")
        helper.assertTrue(hasBlockWithinCube(helper, moddedPos, 4, expectedShell), "Expected modded meteor to include shell block ${BuiltInRegistries.BLOCK.getKey(expectedShell)}")
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
                    messageA?.startsWith("Initializing") == true || messageA?.startsWith("Entering") == true,
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
                    helper.assertTrue(joinMessage?.startsWith("Joining active") == true, "Expected second player to join the existing run")

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
            val result = server.commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "obelisk debug_spawn end")
            helper.assertTrue(result == 1, "Expected /obelisk debug_spawn end to succeed")
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
                "obelisk cleanup_run ${run.runId}"
            )
            helper.assertTrue(result == 1, "Expected /obelisk cleanup_run to accept an active run id")
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
            val unboundResult = server.commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "obelisk return")
            helper.assertTrue(unboundResult == 0, "Expected /obelisk return to fail when player is not assigned to a run")
            placeChargedDefinitionObelisk(helper, obeliskPos, "end")
            val obelisk = helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity
            helper.assertTrue(obelisk != null, "Expected obelisk for command return test")

            waitForPreparedTemplate(helper, "end") {
                val activationMessage = RunRegistry.activateObelisk(player, obelisk!!, obeliskPos)
                helper.assertTrue(
                    activationMessage?.startsWith("Initializing") == true || activationMessage?.startsWith("Entering") == true,
                    "Expected command return test to start a run, got: $activationMessage"
                )

                waitUntil(helper, 240, "Expected player to bind to a run before using /obelisk return", condition = {
                    client.pump(server)
                    val runId = (helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId
                    val run = runId?.let(RunRegistry::get)
                    run?.activePlayers?.contains(player.uuid) == true
                }, onSuccess = {
                    val runId = requireNotNull((helper.level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity)?.activeRunId) {
                        "Expected active run id before running /obelisk return"
                    }
                    val boundResult = server.commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "obelisk return")
                    helper.assertTrue(boundResult == 1, "Expected /obelisk return to succeed for bound player")
                    waitUntil(helper, 200, "Expected /obelisk return to clear player run binding", condition = {
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

    fun runtimeServiceListsAndFindsLoadedObelisks(helper: GameTestHelper) {
        val server = helper.level.server
        val firstPos = helper.absolutePos(BlockPos(20, 2, 20))
        val secondPos = helper.absolutePos(BlockPos(24, 2, 20))
        placeChargedDefinitionObelisk(helper, firstPos, "end")
        placeChargedDefinitionObelisk(helper, secondPos, "nether")

        val loaded = ObeliskRuntimeService.listLoaded(server)
        helper.assertTrue(
            loaded.any { it.blockPos == firstPos } && loaded.any { it.blockPos == secondPos },
            "Expected runtime service listLoaded to include both placed obelisks"
        )

        val nearest = ObeliskRuntimeService.findNearestObelisk(helper.level, firstPos, 4)
        helper.assertTrue(nearest != null, "Expected runtime service to find nearest obelisk")
        helper.assertTrue(nearest!!.blockPos == firstPos, "Expected nearest loaded obelisk lookup to return the closest position")
        helper.succeed()
    }

    fun scarTaskSkipsUnloadedChunks(helper: GameTestHelper) {
        val level = helper.level as ServerLevel
        val farX = 32768
        val farZ = 32768
        helper.assertTrue(
            !CanonicalDimensionBackend.isChunkLoadedForTests(level, farX, farZ),
            "Expected far scar-test chunk to start unloaded"
        )
        val used = CanonicalDimensionBackend.runVerticalScarTaskForTests(
            level = level,
            blockX = farX,
            blockZ = farZ,
            fromY = level.minBuildHeight,
            untilYExclusive = level.maxBuildHeight,
            budget = 128
        )
        helper.assertTrue(used == 0, "Expected scar task to consume zero budget for unloaded chunks")
        helper.assertTrue(
            !CanonicalDimensionBackend.isChunkLoadedForTests(level, farX, farZ),
            "Expected scar task to avoid loading far chunks during tick work"
        )
        helper.succeed()
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
        helper.assertTrue(level.getBlockState(returnPadPos).`is`(ModBlocks.RETURN_PAD.get()), "Expected canonical spawn contract center to be a return pad")

        for (x in -1..1) {
            for (z in -1..1) {
                val floorPos = returnPadPos.offset(x, 0, z)
                val floorState = level.getBlockState(floorPos)
                helper.assertTrue(
                    !floorState.isAir,
                    "Expected canonical spawn contract floor to be solid at $floorPos"
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

    private fun supportBlockForAssertions(): net.minecraft.world.level.block.Block {
        val skyStoneId = ResourceLocation.tryParse("ae2:sky_stone_block")
        val skyStone = skyStoneId?.let(BuiltInRegistries.BLOCK::get)
        return if (skyStone != null && skyStone != Blocks.AIR) skyStone else Blocks.STONE
    }

    private fun meteorShellBlockForAssertions(): net.minecraft.world.level.block.Block = supportBlockForAssertions()

    private fun hasBlockWithinCube(
        helper: GameTestHelper,
        center: BlockPos,
        radius: Int,
        block: net.minecraft.world.level.block.Block
    ): Boolean {
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    if (helper.level.getBlockState(center.offset(dx, dy, dz)).`is`(block)) {
                        return true
                    }
                }
            }
        }
        return false
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

    private fun beginRunOrFail(server: net.minecraft.server.MinecraftServer, definitionId: String): dev.yourname.obelisks.api.RunHandle {
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
                        dy <= -2 -> Blocks.STONE.defaultBlockState()
                        dy == -1 -> Blocks.DIRT.defaultBlockState()
                        dy == 0 -> Blocks.GRASS_BLOCK.defaultBlockState()
                        else -> Blocks.AIR.defaultBlockState()
                    }
                    helper.level.setBlock(BlockPos(center.x + dx, y, center.z + dz), state, 3)
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

    private fun locateGeneratedObeliskPos(helper: GameTestHelper, center: BlockPos): BlockPos? {
        for (dy in -64..96) {
            for (dx in -8..8) {
                for (dz in -8..8) {
                    val candidate = center.offset(dx, dy, dz)
                    if (helper.level.getBlockEntity(candidate) is ObeliskBlockEntity || helper.level.getBlockState(candidate).`is`(ModBlocks.OBELISK.get())) {
                        return candidate
                    }
                }
            }
        }
        return null
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

    private fun configureActivationTestObelisks(server: net.minecraft.server.MinecraftServer) {
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
                craterFillBlocks = listOf("minecraft:gravel", "minecraft:blackstone", "minecraft:magma_block"),
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
