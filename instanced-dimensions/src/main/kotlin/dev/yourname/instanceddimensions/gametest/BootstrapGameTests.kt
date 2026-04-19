package dev.yourname.instanceddimensions.gametest

import dev.yourname.instanceddimensions.MOD_ID
import dev.yourname.instanceddimensions.NETWORK_CHANNEL
import dev.yourname.instanceddimensions.compat.RuntimeDimensionAccess
import dev.yourname.instanceddimensions.engine.levelsync.RuntimeLevelKeysPacket
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceSavedData
import dev.yourname.instanceddimensions.engine.instance.InstanceState
import dev.yourname.instanceddimensions.engine.instance.InstanceRecord
import dev.yourname.instanceddimensions.engine.instance.InstanceLevelState
import dev.yourname.instanceddimensions.engine.instance.InstanceTemplateDataManager
import dev.yourname.instanceddimensions.engine.travel.TravelManager
import dev.yourname.instanceddimensions.events.RuntimeDimensionTransitionEvent
import com.mojang.authlib.GameProfile
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.core.BlockPos
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
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.network.NetworkHooks
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.net.SocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

@GameTestHolder(MOD_ID)
class BootstrapGameTests {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(BootstrapGameTests::class.java)
        private val RUNTIME_CHANNEL = ResourceLocation.fromNamespaceAndPath(MOD_ID, NETWORK_CHANNEL)
        private val MEMORY_CHANNELS = ConcurrentHashMap<net.minecraft.server.MinecraftServer, SocketAddress>()
    }

    @GameTest(template = "bootstrap/empty", batch = "bootstrap")
    fun bootstrap_smoke_test(helper: GameTestHelper) {
        helper.assertTrue(InstanceManager.templates().isNotEmpty(), "Expected default instance templates to be loaded from data")
        helper.succeed()
    }

    @GameTest(template = "bootstrap/empty", batch = "bootstrap")
    fun template_data_skips_missing_required_namespace(helper: GameTestHelper) {
        val templatesDir = InstanceTemplateDataManager.templatesPath()
        val missingTemplate = templatesDir.resolve("test_missing_namespace_template.json")
        val presentTemplate = templatesDir.resolve("test_present_namespace_template.json")

        Files.createDirectories(templatesDir)
        Files.writeString(
            missingTemplate,
            """
            {
              "id": "test_missing_namespace_template",
              "stem": "minecraft:overworld",
              "requiredNamespace": "missing_test_namespace",
              "description": "Should be skipped"
            }
            """.trimIndent()
        )
        Files.writeString(
            presentTemplate,
            """
            {
              "id": "test_present_namespace_template",
              "stem": "minecraft:overworld",
              "requiredNamespace": "minecraft",
              "description": "Should load"
            }
            """.trimIndent()
        )

        try {
            InstanceManager.reloadTemplates()
            helper.assertTrue(
                InstanceManager.getTemplate("test_missing_namespace_template") == null,
                "Expected template reload to skip templates whose required namespace is unavailable"
            )
            helper.assertTrue(
                InstanceManager.getTemplate("test_present_namespace_template") != null,
                "Expected template reload to keep templates whose required namespace is available"
            )
            helper.succeed()
        } finally {
            Files.deleteIfExists(missingTemplate)
            Files.deleteIfExists(presentTemplate)
            InstanceManager.reloadTemplates()
        }
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_compat", timeoutTicks = 1600)
    fun runtime_dimension_access_tracks_runtime_levels(helper: GameTestHelper) {
        val server = helper.level.server
        val created = InstanceManager.createInstance(server, "overworld")

        waitUntil(helper, 30, "Expected runtime instance to become ACTIVE for compat queries", condition = {
            InstanceManager.getInstance(created.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val level = server.getLevel(created.levelKey)
            helper.assertTrue(level != null, "Expected runtime level to be loaded for compat queries")
            helper.assertTrue(RuntimeDimensionAccess.templates().isNotEmpty(), "Expected compat template list to be populated")
            helper.assertTrue(RuntimeDimensionAccess.getTemplate("overworld") != null, "Expected compat template lookup to resolve built-in template")
            helper.assertTrue(RuntimeDimensionAccess.getInstance(created.id)?.id == created.id, "Expected compat instance lookup by id to resolve the runtime instance")
            helper.assertTrue(RuntimeDimensionAccess.getInstance(created.levelKey)?.id == created.id, "Expected compat instance lookup by level key to resolve the runtime instance")
            helper.assertTrue(RuntimeDimensionAccess.getInstance(level!!)?.id == created.id, "Expected compat instance lookup by level to resolve the runtime instance")
            helper.assertTrue(RuntimeDimensionAccess.isRuntimeLevel(created.levelKey), "Expected compat runtime-level check by key to succeed")
            helper.assertTrue(RuntimeDimensionAccess.isRuntimeLevel(level), "Expected compat runtime-level check by level to succeed")
            helper.assertTrue(RuntimeDimensionAccess.allInstances().any { it.id == created.id }, "Expected compat instance listing to include the runtime instance")

            helper.assertTrue(InstanceManager.scheduleDestroy(server, created.id), "Expected compat test instance cleanup to be accepted")
            waitUntil(helper, 1200, failureMessage = {
                destroyFailureMessage(server, created.id, "Expected compat test runtime instance to be removed")
            }, condition = {
                RuntimeDimensionAccess.getInstance(created.id) == null
            }, onSuccess = {
                helper.assertTrue(!RuntimeDimensionAccess.isRuntimeLevel(created.levelKey), "Expected compat runtime-level check to clear after destroy")
                helper.succeed()
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_create", timeoutTicks = 1600)
    fun instance_create_and_destroy_roundtrip(helper: GameTestHelper) {
        val server = helper.level.server
        val created = InstanceManager.createInstance(server, "overworld")

        waitUntil(helper, 20, "Expected runtime instance to become ACTIVE after server registration", condition = {
            InstanceManager.getInstance(created.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val activeRecord = InstanceManager.getInstance(created.id)
            helper.assertTrue(activeRecord != null, "Expected created instance to remain registered after lifecycle tick")
            helper.assertTrue(activeRecord?.state == InstanceState.ACTIVE, "Expected runtime instance to become ACTIVE after server registration")
            helper.assertTrue(server.getLevel(created.levelKey) != null, "Expected runtime instance to be reachable from the server world map")

            val savedRecord = InstanceSavedData.get(server).snapshot().firstOrNull { it.id == created.id }
            helper.assertTrue(savedRecord != null, "Expected created instance to persist in saved data")
            helper.assertTrue(savedRecord?.state == InstanceState.ACTIVE, "Expected saved instance state to match the registered runtime level")

            helper.assertTrue(InstanceManager.scheduleDestroy(server, created.id), "Expected destroy request to be accepted")

            waitUntil(helper, 1200, failureMessage = {
                destroyFailureMessage(server, created.id, "Expected destroyed instance to be removed from the active registry")
            }, condition = {
                InstanceManager.getInstance(created.id) == null
            }, onSuccess = {
                helper.assertTrue(InstanceManager.getInstance(created.id) == null, "Expected destroyed instance to be removed from the active registry")
                helper.assertTrue(server.getLevel(created.levelKey) == null, "Expected destroyed instance to be removed from the server world map")
                val persisted = InstanceSavedData.get(server).snapshot().any { it.id == created.id }
                helper.assertTrue(!persisted, "Expected destroyed instance to be removed from saved data")
                helper.succeed()
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_multi", timeoutTicks = 1600)
    fun multiple_instances_activate_and_cleanup(helper: GameTestHelper) {
        val server = helper.level.server
        val overworldInstance = InstanceManager.createInstance(server, "overworld")
        val netherInstance = InstanceManager.createInstance(server, "nether")

        waitUntil(helper, 30, "Expected both runtime instances to become ACTIVE", condition = {
            InstanceManager.getInstance(overworldInstance.id)?.state == InstanceState.ACTIVE &&
                InstanceManager.getInstance(netherInstance.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val overworldLevel = server.getLevel(overworldInstance.levelKey)
            val netherLevel = server.getLevel(netherInstance.levelKey)

            helper.assertTrue(overworldLevel != null, "Expected overworld instance to register a runtime level")
            helper.assertTrue(netherLevel != null, "Expected nether instance to register a runtime level")
            helper.assertTrue(overworldLevel !== netherLevel, "Expected runtime instances to resolve to distinct ServerLevel objects")
            helper.assertTrue(
                netherLevel?.dimensionTypeRegistration()?.`is`(BuiltinDimensionTypes.NETHER) == true,
                "Expected nether template instance to use the nether dimension type"
            )
            helper.assertTrue(
                InstanceSavedData.get(server).snapshot().size >= 2,
                "Expected both runtime instances to be persisted while active"
            )

            helper.assertTrue(InstanceManager.scheduleDestroy(server, overworldInstance.id), "Expected overworld instance destroy request to be accepted")
            helper.assertTrue(InstanceManager.scheduleDestroy(server, netherInstance.id), "Expected nether instance destroy request to be accepted")

            waitUntil(helper, 240, failureMessage = {
                listOf(overworldInstance.id, netherInstance.id)
                    .joinToString(separator = " || ") { destroyFailureMessage(server, it, "Expected both runtime instances to enter background teardown after cleanup") }
            }, condition = {
                isQueuedForTeardown(server, overworldInstance.id) && isQueuedForTeardown(server, netherInstance.id)
            }, onSuccess = {
                helper.assertTrue(isQueuedForTeardown(server, overworldInstance.id), "Expected overworld instance to enter background teardown after cleanup")
                helper.assertTrue(isQueuedForTeardown(server, netherInstance.id), "Expected nether instance to enter background teardown after cleanup")
                helper.succeed()
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_state", timeoutTicks = 1600)
    fun instance_level_state_is_isolated_and_persisted(helper: GameTestHelper) {
        val server = helper.level.server
        val first = InstanceManager.createInstance(server, "overworld")
        val second = InstanceManager.createInstance(server, "overworld")

        waitUntil(helper, 30, "Expected both instance levels to become ACTIVE", condition = {
            InstanceManager.getInstance(first.id)?.state == InstanceState.ACTIVE &&
                InstanceManager.getInstance(second.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val firstLevel = server.getLevel(first.levelKey)
            val secondLevel = server.getLevel(second.levelKey)
            helper.assertTrue(firstLevel != null, "Expected first instance to be registered")
            helper.assertTrue(secondLevel != null, "Expected second instance to be registered")

            firstLevel!!.setDayTime(6000L)
            secondLevel!!.setDayTime(18000L)
            firstLevel.setWeatherParameters(0, 200, true, true)
            secondLevel.setWeatherParameters(100, 0, false, false)
            firstLevel.setDefaultSpawnPos(BlockPos(10, 90, 10), 45.0F)
            secondLevel.setDefaultSpawnPos(BlockPos(-10, 70, -10), 0.0F)
            helper.assertTrue(InstanceManager.snapshotInstance(server, first.id), "Expected first instance snapshot to succeed")
            helper.assertTrue(InstanceManager.snapshotInstance(server, second.id), "Expected second instance snapshot to succeed")

            val snapshot = InstanceSavedData.get(server).snapshot()
            val firstRecord = snapshot.firstOrNull { it.id == first.id }
            val secondRecord = snapshot.firstOrNull { it.id == second.id }

            helper.assertTrue(firstRecord?.levelState?.dayTime == 6000L, "Expected first instance day time to persist independently")
            helper.assertTrue(secondRecord?.levelState?.dayTime == 18000L, "Expected second instance day time to persist independently")
            helper.assertTrue(firstRecord?.levelState?.raining == true, "Expected first instance weather to persist independently")
            helper.assertTrue(secondRecord?.levelState?.raining == false, "Expected second instance weather to remain clear")
            helper.assertTrue(firstRecord?.levelState?.spawnX == 10, "Expected first instance spawn to persist independently")
            helper.assertTrue(secondRecord?.levelState?.spawnX == -10, "Expected second instance spawn to persist independently")

            helper.assertTrue(InstanceManager.scheduleDestroy(server, first.id), "Expected first instance cleanup to be accepted")
            helper.assertTrue(InstanceManager.scheduleDestroy(server, second.id), "Expected second instance cleanup to be accepted")

            waitUntil(helper, 1200, failureMessage = {
                listOf(first.id, second.id)
                    .joinToString(separator = " || ") { destroyFailureMessage(server, it, "Expected both instance levels to unload cleanly") }
            }, condition = {
                server.getLevel(first.levelKey) == null && server.getLevel(second.levelKey) == null
            }, onSuccess = {
                helper.assertTrue(server.getLevel(first.levelKey) == null, "Expected first instance to unload cleanly")
                helper.assertTrue(server.getLevel(second.levelKey) == null, "Expected second instance to unload cleanly")
                helper.succeed()
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_state", timeoutTicks = 1600)
    fun instance_seeds_are_randomized_per_instance(helper: GameTestHelper) {
        val server = helper.level.server
        val first = InstanceManager.createInstance(server, "overworld")
        val second = InstanceManager.createInstance(server, "overworld")

        waitUntil(helper, 30, "Expected both instance levels to become ACTIVE for seed checks", condition = {
            InstanceManager.getInstance(first.id)?.state == InstanceState.ACTIVE &&
                InstanceManager.getInstance(second.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val firstLevel = requireNotNull(server.getLevel(first.levelKey)) { "Expected first instance level to be loaded" }
            val secondLevel = requireNotNull(server.getLevel(second.levelKey)) { "Expected second instance level to be loaded" }
            val snapshot = InstanceSavedData.get(server).snapshot()
            val firstRecord = snapshot.firstOrNull { it.id == first.id }
            val secondRecord = snapshot.firstOrNull { it.id == second.id }

            helper.assertTrue(firstRecord != null, "Expected first instance seed to persist in saved data")
            helper.assertTrue(secondRecord != null, "Expected second instance seed to persist in saved data")
            helper.assertTrue(firstRecord?.instanceSeed != InstanceRecord.UNSET_SEED, "Expected first instance to store a resolved runtime seed")
            helper.assertTrue(secondRecord?.instanceSeed != InstanceRecord.UNSET_SEED, "Expected second instance to store a resolved runtime seed")
            helper.assertTrue(firstRecord?.instanceSeed != secondRecord?.instanceSeed, "Expected same-template instances to persist distinct seeds")
            helper.assertTrue(firstLevel.seed == firstRecord?.instanceSeed, "Expected first runtime level to expose its persisted instance seed")
            helper.assertTrue(secondLevel.seed == secondRecord?.instanceSeed, "Expected second runtime level to expose its persisted instance seed")
            helper.assertTrue(firstLevel.seed != secondLevel.seed, "Expected same-template runtime levels to use distinct world seeds")

            helper.assertTrue(InstanceManager.scheduleDestroy(server, first.id), "Expected first seeded instance cleanup to be accepted")
            helper.assertTrue(InstanceManager.scheduleDestroy(server, second.id), "Expected second seeded instance cleanup to be accepted")

            waitUntil(helper, 1200, failureMessage = {
                listOf(first.id, second.id)
                    .joinToString(separator = " || ") { destroyFailureMessage(server, it, "Expected seeded runtime instances to unload cleanly") }
            }, condition = {
                server.getLevel(first.levelKey) == null && server.getLevel(second.levelKey) == null
            }, onSuccess = {
                helper.succeed()
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_travel", timeoutTicks = 1600)
    fun player_can_enter_and_exit_runtime_instance(helper: GameTestHelper) {
        val server = helper.level.server
        val instance = InstanceManager.createInstance(server, "overworld")

        waitUntil(helper, 40, "Expected run-backed instance to become ACTIVE", condition = {
            InstanceManager.getInstance(instance.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            LOGGER.info("Travel test: establishing memory-channel player")
            val client = connectHeadlessPlayer(helper)
            val player = client.player
            val originDimension = player.serverLevel().dimension()

            waitUntil(helper, 20, "Expected late-joining client to learn the active runtime level key", condition = {
                client.pump(server)
                client.knownLevels.contains(instance.levelKey)
            }, onSuccess = {
                client.pump(server)
                helper.assertTrue(
                    client.knownLevels.contains(instance.levelKey),
                    "Expected late-joining client to learn the active runtime level key during login sync"
                )
                helper.assertTrue(
                    client.customPayloadChannels.contains(RUNTIME_CHANNEL),
                    "Expected login sync to send the runtime level-key payload channel"
                )

                LOGGER.info("Travel test: entering runtime instance")
                TravelManager.enterInstance(player, instance.id)
                waitUntil(helper, 80, "Expected player to enter the runtime instance level", condition = {
                    client.pump(server)
                    player.serverLevel().dimension() == instance.levelKey
                }, onSuccess = {
                    client.pump(server)
                    LOGGER.info("Travel test: entered runtime instance")

                    helper.assertTrue(player.serverLevel().dimension() == instance.levelKey, "Expected player to enter the runtime instance level")
                    helper.assertTrue(
                        client.respawnDimensions.contains(instance.levelKey),
                        "Expected connection-backed client to receive a respawn into the runtime instance"
                    )

                    LOGGER.info("Travel test: exiting runtime instance")
                    helper.assertTrue(TravelManager.returnPlayer(player), "Expected instance return travel to be accepted")
                    waitUntil(helper, 40, "Expected player to return to the origin dimension", condition = {
                        client.pump(server)
                        player.serverLevel().dimension() == originDimension
                    }, onSuccess = {
                        client.pump(server)
                        LOGGER.info("Travel test: exited runtime instance")

                        helper.assertTrue(player.serverLevel().dimension() == originDimension, "Expected player to return to the origin dimension")
                        helper.assertTrue(
                            client.respawnDimensions.lastOrNull() == originDimension,
                            "Expected connection-backed client to receive a respawn back to the origin dimension"
                        )
                        helper.assertTrue(
                            server.getLevel(instance.levelKey)?.players()?.isEmpty() == true,
                            "Expected runtime instance to be empty after the player exits"
                        )
                        helper.assertTrue(
                            server.getLevel(instance.levelKey)?.getEntity(player.uuid) == null,
                            "Expected runtime instance entity lookup to be empty after the player exits"
                        )
                        LOGGER.info("Travel test: finishing run")
                        helper.assertTrue(InstanceManager.scheduleDestroy(server, instance.id), "Expected runtime instance cleanup to be accepted")
                        waitUntil(helper, 240, failureMessage = {
                            destroyFailureMessage(server, instance.id, "Expected exiting the run to queue the owned runtime instance for teardown")
                        }, condition = {
                            client.pump(server)
                            isQueuedForTeardown(server, instance.id)
                        }, onSuccess = {
                            client.pump(server)
                            helper.assertTrue(isQueuedForTeardown(server, instance.id), "Expected exiting the runtime instance to queue the owned runtime level for teardown")
                            client.close(server)
                            helper.succeed()
                        })
                    })
                })
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_stress", timeoutTicks = 3200)
    fun repeated_runtime_lifecycle_cycles_cleanup_cleanly(helper: GameTestHelper) {
        val server = helper.level.server
        val client = connectHeadlessPlayer(helper)
        val player = client.player
        val originDimension = player.serverLevel().dimension()

        fun runCycle(cycle: Int) {
            if (cycle > 3) {
                client.close(server)
                helper.succeed()
                return
            }

            val instance = InstanceManager.createInstance(server, "overworld")
            waitUntil(helper, 60, "Expected stress-cycle instance $cycle to become ACTIVE", condition = {
                InstanceManager.getInstance(instance.id)?.state == InstanceState.ACTIVE
            }, onSuccess = {
                waitUntil(helper, 40, "Expected client to learn stress-cycle runtime level key $cycle", condition = {
                    client.pump(server)
                    client.knownLevels.contains(instance.levelKey)
                }, onSuccess = {
                    TravelManager.enterInstance(player, instance.id)
                    waitUntil(helper, 100, "Expected player to enter stress-cycle runtime level $cycle", condition = {
                        client.pump(server)
                        player.serverLevel().dimension() == instance.levelKey
                    }, onSuccess = {
                        helper.assertTrue(
                            TravelManager.returnPlayer(player),
                            "Expected stress-cycle return travel $cycle to be accepted"
                        )
                        waitUntil(helper, 100, "Expected player to return from stress-cycle runtime level $cycle", condition = {
                            client.pump(server)
                            player.serverLevel().dimension() == originDimension
                        }, onSuccess = {
                            helper.assertTrue(
                                InstanceManager.scheduleDestroy(server, instance.id),
                                "Expected stress-cycle instance cleanup $cycle to be accepted"
                            )
                            waitUntil(helper, 1200, failureMessage = {
                                destroyFailureMessage(server, instance.id, "Expected stress-cycle runtime level $cycle to be removed cleanly")
                            }, condition = {
                                client.pump(server)
                                InstanceManager.getInstance(instance.id) == null &&
                                    !client.knownLevels.contains(instance.levelKey)
                            }, onSuccess = {
                                runCycle(cycle + 1)
                            })
                        })
                    })
                })
            })
        }

        try {
            runCycle(1)
        } catch (t: Throwable) {
            client.close(server)
            throw t
        }
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_intercept", timeoutTicks = 1600)
    fun direct_dimension_change_is_intercepted_and_tracks_return_anchor(helper: GameTestHelper) {
        val server = helper.level.server
        val instance = InstanceManager.createInstance(server, "overworld")

        waitUntil(helper, 40, "Expected interception test instance to become ACTIVE", condition = {
            InstanceManager.getInstance(instance.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val targetLevel = server.getLevel(instance.levelKey)
            helper.assertTrue(targetLevel != null, "Expected interception test instance to be loaded")

            val client = connectHeadlessPlayer(helper)
            val player = client.player
            val originDimension = player.serverLevel().dimension()
            val originX = player.x
            val originY = player.y
            val originZ = player.z

            waitUntil(helper, 20, "Expected client to know the runtime level before direct travel", condition = {
                client.pump(server)
                client.knownLevels.contains(instance.levelKey)
            }, onSuccess = {
                val gate = object {
                    var cancelExternalEntry = true

                    @SubscribeEvent
                    fun onPreTransition(event: RuntimeDimensionTransitionEvent.Pre) {
                        if (event.player.uuid == player.uuid && event.toInstance?.id == instance.id && cancelExternalEntry) {
                            event.isCanceled = true
                        }
                    }
                }
                MinecraftForge.EVENT_BUS.register(gate)

                try {
                    player.teleportTo(targetLevel!!, 0.5, 80.0, 0.5, player.yRot, player.xRot)

                    waitUntil(helper, 40, "Expected intercepted direct travel to keep the player in the origin dimension", condition = {
                        client.pump(server)
                        player.serverLevel().dimension() == originDimension
                    }, onSuccess = {
                        helper.assertTrue(
                            player.serverLevel().dimension() == originDimension,
                            "Expected direct travel cancel hook to keep the player out of the runtime level"
                        )
                        helper.assertTrue(
                            !RuntimeDimensionAccess.hasReturnAnchor(player.uuid),
                            "Expected canceled direct travel to avoid creating a return anchor"
                        )

                        waitUntil(helper, 80, "Expected runtime instance to become travel-ready before uncanceled direct entry", condition = {
                            InstanceManager.isTravelReady(instance.id)
                        }, onSuccess = {
                            gate.cancelExternalEntry = false
                            player.teleportTo(targetLevel, 0.5, 80.0, 0.5, player.yRot, player.xRot)

                            waitUntil(helper, 80, "Expected direct travel into runtime level after compat hook is released", condition = {
                                client.pump(server)
                                player.serverLevel().dimension() == instance.levelKey
                            }, onSuccess = {
                                helper.assertTrue(
                                    player.serverLevel().dimension() == instance.levelKey,
                                    "Expected direct dimension change to enter the runtime level"
                                )
                                helper.assertTrue(
                                    RuntimeDimensionAccess.hasReturnAnchor(player.uuid),
                                    "Expected compat orchestration to capture a return anchor for external runtime travel"
                                )
                                helper.assertTrue(
                                    RuntimeDimensionAccess.getReturnAnchor(player.uuid)?.levelKey == originDimension,
                                    "Expected captured return anchor to point back to the origin dimension"
                                )

                                player.teleportTo(server.overworld(), originX, originY, originZ, player.yRot, player.xRot)

                                waitUntil(helper, 80, "Expected direct exit from runtime level to clear the return anchor", condition = {
                                    client.pump(server)
                                    player.serverLevel().dimension() == originDimension && !RuntimeDimensionAccess.hasReturnAnchor(player.uuid)
                                }, onSuccess = {
                                    helper.assertTrue(
                                        player.serverLevel().dimension() == originDimension,
                                        "Expected direct exit to return the player to the origin dimension"
                                    )
                                    helper.assertTrue(
                                        !RuntimeDimensionAccess.hasReturnAnchor(player.uuid),
                                        "Expected compat orchestration to clear the return anchor after leaving the runtime level"
                                    )

                                    helper.assertTrue(InstanceManager.scheduleDestroy(server, instance.id), "Expected interception test instance cleanup to be accepted")
                                    waitUntil(helper, 240, failureMessage = {
                                        destroyFailureMessage(server, instance.id, "Expected interception test instance to enter background teardown")
                                    }, condition = {
                                        isQueuedForTeardown(server, instance.id)
                                    }, onSuccess = {
                                        MinecraftForge.EVENT_BUS.unregister(gate)
                                        client.close(server)
                                        helper.succeed()
                                    })
                                })
                            })
                        })
                    })
                } catch (t: Throwable) {
                    MinecraftForge.EVENT_BUS.unregister(gate)
                    client.close(server)
                    throw t
                }
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_owner", timeoutTicks = 800)
    fun instance_creation_persists_owner_metadata(helper: GameTestHelper) {
        val server = helper.level.server
        val ownerId = UUID.randomUUID()
        val instance = InstanceManager.createInstance(server, "overworld", ownerId = ownerId)

        waitUntil(helper, 30, "Expected owner-tagged instance to become ACTIVE", condition = {
            InstanceManager.getInstance(instance.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            val activeInstance = InstanceManager.getInstance(instance.id)
            helper.assertTrue(activeInstance != null, "Expected created instance to be registered")
            helper.assertTrue(activeInstance?.ownerId == ownerId, "Expected runtime instance owner metadata to persist on the live handle")
            helper.assertTrue(
                InstanceSavedData.get(server).snapshot().any { it.id == instance.id && it.ownerId == ownerId },
                "Expected instance owner metadata to persist in saved data while active"
            )
            helper.assertTrue(InstanceManager.scheduleDestroy(server, instance.id), "Expected owner-tagged instance cleanup to be accepted")
            waitUntil(helper, 240, failureMessage = {
                destroyFailureMessage(server, instance.id, "Expected owned instance cleanup to leave the runtime world queued for teardown")
            }, condition = {
                val current = InstanceManager.getInstance(instance.id)
                current == null || current.state == InstanceState.DRAINING || current.state == InstanceState.UNLOADING || current.state == InstanceState.CLOSING
            }, onSuccess = {
                val current = InstanceManager.getInstance(instance.id)
                helper.assertTrue(
                    current == null || current.state == InstanceState.DRAINING || current.state == InstanceState.UNLOADING || current.state == InstanceState.CLOSING,
                    "Expected owner-tagged instance cleanup to leave the runtime world queued for teardown"
                )
                helper.succeed()
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_restore", timeoutTicks = 1600)
    fun saved_instances_restore_after_manager_reset(helper: GameTestHelper) {
        val server = helper.level.server
        val created = InstanceManager.createInstance(server, "overworld")

        waitUntil(helper, 40, "Expected restore test instance to become ACTIVE", condition = {
            InstanceManager.getInstance(created.id)?.state == InstanceState.ACTIVE
        }, onSuccess = {
            helper.assertTrue(InstanceSavedData.get(server).snapshot().any { it.id == created.id }, "Expected restore test instance to persist in saved data")
            InstanceManager.clearInstances()
            helper.assertTrue(InstanceManager.getInstance(created.id) == null, "Expected manager reset to clear the live registry")
            InstanceManager.restoreFromSavedData(server)
            waitUntil(helper, 60, "Expected saved instance to restore into the live registry after manager reset", condition = {
                InstanceManager.getInstance(created.id)?.state == InstanceState.ACTIVE
            }, onSuccess = {
                helper.assertTrue(server.getLevel(created.levelKey) != null, "Expected restored instance level to remain reachable after registry rebuild")
                helper.assertTrue(InstanceManager.scheduleDestroy(server, created.id), "Expected restored instance cleanup to be accepted")
                waitUntil(helper, 1200, failureMessage = {
                    destroyFailureMessage(server, created.id, "Expected restored instance to clean up after the recovery test")
                }, condition = {
                    InstanceManager.getInstance(created.id) == null
                }, onSuccess = {
                    helper.succeed()
                })
            })
        })
    }

    @GameTest(template = "bootstrap/empty", batch = "instance_restore", timeoutTicks = 120)
    fun missing_template_records_are_pruned_on_restore(helper: GameTestHelper) {
        val server = helper.level.server
        val invalid = InstanceRecord(
            id = UUID.randomUUID(),
            templateId = "missing_template",
            levelKey = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "instance/missing/${UUID.randomUUID().toString().replace("-", "")}")
            ),
            state = InstanceState.ALLOCATED,
            levelState = InstanceLevelState.createDefaultPlaceholder(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "instance/missing/test")
            )
        )

        InstanceSavedData.get(server).replaceAll(listOf(invalid))
        InstanceManager.clearInstances()
        InstanceManager.restoreFromSavedData(server)

        helper.runAfterDelay(5) {
            helper.assertTrue(InstanceManager.getInstance(invalid.id) == null, "Expected missing-template saved instance to be pruned during restore")
            helper.assertTrue(
                InstanceSavedData.get(server).snapshot().none { it.id == invalid.id },
                "Expected missing-template saved instance to be removed from saved data during restore"
            )
            helper.succeed()
        }
    }

    private fun waitUntil(
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

    private fun waitUntil(
        helper: GameTestHelper,
        remainingTicks: Int,
        failureMessage: String,
        condition: () -> Boolean,
        onSuccess: () -> Unit
    ) {
        waitUntil(helper, remainingTicks, { failureMessage }, condition, onSuccess)
    }

    private fun destroyFailureMessage(server: net.minecraft.server.MinecraftServer, instanceId: UUID, prefix: String): String {
        val current = InstanceManager.getInstance(instanceId)
        val saved = InstanceSavedData.get(server).snapshot().firstOrNull { it.id == instanceId }
        return buildString {
            append(prefix)
            append(" | instance=")
            append(current?.state ?: "null")
            append(" levelLoaded=")
            append(current?.levelKey?.let(server::getLevel) != null)
            append(" close=")
            append(InstanceManager.describeCloseState(server, instanceId))
            append(" saved=")
            append(saved?.state ?: "null")
        }
    }

    private fun isQueuedForTeardown(server: net.minecraft.server.MinecraftServer, instanceId: UUID): Boolean {
        val current = InstanceManager.getInstance(instanceId)
        return current == null || current.state == InstanceState.DRAINING || current.state == InstanceState.UNLOADING || current.state == InstanceState.CLOSING
    }

    private fun connectHeadlessPlayer(helper: GameTestHelper): ConnectedTestClient {
        val server = helper.level.server
        val serverConnectionListener = requireNotNull(server.connection) { "Expected server connection listener to be available" }
        val existingConnections = serverConnectionListener.connections.toSet()
        val address = MEMORY_CHANNELS.computeIfAbsent(server) { serverConnectionListener.startMemoryChannel() }
        val clientConnection = Connection.connectToLocalServer(address)
        val recorder = HeadlessClientRecorder()
        clientConnection.setListener(recorder.listener)
        serverConnectionListener.tick()

        val serverConnection = serverConnectionListener.connections.firstOrNull { it !in existingConnections && it.isConnected }
            ?: error("Expected a new memory-channel server connection")
        NetworkHooks.registerServerLoginChannel(serverConnection, ClientIntentionPacket("localhost", 0, ConnectionProtocol.LOGIN))

        val player = server.playerList.getPlayerForLogin(GameProfile(UUID.randomUUID(), "test-runtime-player"))
        val spawn = helper.absolutePos(BlockPos(1, 2, 1))
        player.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0.0F, 0.0F)
        server.playerList.placeNewPlayer(serverConnection, player)
        recorder.pump(clientConnection)
        return ConnectedTestClient(player, clientConnection, recorder)
    }

    private class ConnectedTestClient(
        val player: ServerPlayer,
        private val clientConnection: Connection,
        private val recorder: HeadlessClientRecorder
    ) {
        val knownLevels: Set<ResourceKey<Level>>
            get() = recorder.knownLevels

        val customPayloadChannels: List<ResourceLocation>
            get() = recorder.customPayloadChannels

        val respawnDimensions: List<ResourceKey<Level>>
            get() = recorder.respawnDimensions

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

        val listener: PacketListener = Proxy.newProxyInstance(
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
                    if (packet.identifier == RUNTIME_CHANNEL) {
                        val payload = packet.data
                        val messageId = payload.readVarInt()
                        if (messageId == 0) {
                            val update = RuntimeLevelKeysPacket.decode(payload)
                            knownLevels += update.additions
                            knownLevels.removeAll(update.removals.toSet())
                        }
                    }
                    null
                }
                "shouldPropagateHandlingExceptions" -> true
                else -> null
            }
        } as PacketListener

        fun pump(connection: Connection) {
            connection.tick()
        }
    }
}
