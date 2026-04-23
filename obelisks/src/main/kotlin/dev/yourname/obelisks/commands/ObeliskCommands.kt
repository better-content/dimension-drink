package dev.yourname.obelisks.commands

import com.mojang.brigadier.arguments.StringArgumentType
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.runtime.ObeliskRuntimeService
import dev.yourname.obelisks.runtime.backend.RunBackendManager
import dev.yourname.obelisks.runtime.run.RunRegistry
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.UuidArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.sqrt

object ObeliskCommands {

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val root = Commands.literal("obelisk").requires { it.hasPermission(2) }

        root.then(
            Commands.literal("debug_spawn")
                .executes { ctx ->
                    spawnDebugObelisk(ctx.source.playerOrException, null)
                }
                .then(
                    Commands.argument("template", StringArgumentType.word()).executes { ctx ->
                        spawnDebugObelisk(
                            ctx.source.playerOrException,
                            StringArgumentType.getString(ctx, "template")
                        )
                    }
                )
        )

        root.then(
            Commands.literal("list_runs").executes { ctx ->
                val runs = RunRegistry.snapshot().sortedBy { it.createdGameTime }
                if (runs.isEmpty()) {
                    ctx.source.sendSuccess({ Component.literal("No active or pending rift anchor runs.") }, false)
                } else {
                    ctx.source.sendSuccess({ Component.literal("Runs (${runs.size}):") }, false)
                    runs.forEach { run ->
                        ctx.source.sendSuccess({
                            Component.literal(
                                "${run.id} definition=${run.definitionId} target=${run.instanceTemplateId} site=${run.instanceId} level=${run.backendLevelKey?.location() ?: "missing"} center=${run.backendSiteCenter ?: "missing"} state=${run.state} players=${run.activePlayers.size}/${run.pendingPlayers.size}"
                            )
                        }, false)
                    }
                }
                1
            }
        )

        root.then(
            Commands.literal("return").executes { ctx ->
                val player = ctx.source.playerOrException
                if (RunRegistry.returnPlayer(player)) {
                    ctx.source.sendSuccess({ Component.literal("Returning to origin rift anchor.") }, false)
                    1
                } else {
                    ctx.source.sendFailure(Component.literal("Player is not bound to an active rift anchor run."))
                    0
                }
            }
        )

        root.then(
            Commands.literal("cleanup_run")
                .then(
                    Commands.argument("runId", UuidArgument.uuid()).executes { ctx ->
                        val runId = UuidArgument.getUuid(ctx, "runId")
                        if (RunRegistry.finishRun(ctx.source.server, runId)) {
                            ctx.source.sendSuccess({ Component.literal("Requested cleanup for run $runId") }, true)
                            1
                        } else {
                            ctx.source.sendFailure(Component.literal("Run $runId was not found or is already finishing."))
                            0
                        }
                    }
                )
        )

        root.then(
            Commands.literal("info").executes { ctx ->
                val player = ctx.source.playerOrException
                val handle = RunRegistry.getRun(player.uuid)
                if (handle == null) {
                    ctx.source.sendSuccess({ Component.literal("Player is not assigned to a rift anchor run.") }, false)
                    return@executes 1
                }

                val record = RunRegistry.get(handle.runId)
                ctx.source.sendSuccess({
                    Component.literal("Run ${handle.runId} definition=${handle.definitionId} template=${handle.instanceTemplateId} state=${handle.state}")
                }, false)
                ctx.source.sendSuccess({
                    Component.literal("Site=${handle.instanceId} level=${record?.backendLevelKey?.location() ?: "missing"} center=${record?.backendSiteCenter ?: "missing"}")
                }, false)

                if (record != null) {
                    val obelisk = record.originLevelKey?.let(ctx.source.server::getLevel)
                        ?.getBlockEntity(record.originObeliskPos ?: BlockPos.ZERO) as? ObeliskBlockEntity
                    if (obelisk != null) {
                        ctx.source.sendSuccess({
                            Component.literal(
                                "Rift anchor FE=${obelisk.getEnergyStored()}/${obelisk.getMaxEnergyStored()} cooldown=${obelisk.getCooldownRemainingTicks()} activeRun=${obelisk.activeRunId}"
                            )
                        }, false)
                    }
                }
                1
            }
        )

        root.then(
            Commands.literal("check_modifiers").executes { ctx ->
                val player = ctx.source.playerOrException
                val obelisk = lookedAtObelisk(player)
                if (obelisk == null) {
                    ctx.source.sendFailure(Component.literal("Not looking at a rift anchor."))
                    return@executes 0
                }

                ctx.source.sendSuccess({ Component.literal("Rift anchor ${obelisk.obeliskId} definition=${obelisk.definitionId} template=${obelisk.targetTemplateId}") }, false)
                obelisk.modifiers.forEachIndexed { index, modifier ->
                    ctx.source.sendSuccess({
                        Component.literal("${index + 1}. ${modifier.stat.name} +${modifier.bonusPercent}%")
                    }, false)
                }
                ctx.source.sendSuccess({
                    Component.literal(
                        "storage=${obelisk.getModifiedMaxStorage()} regen=${obelisk.getModifiedRegenRate()} baseDrain=${obelisk.getModifiedBaseDrain()} playerDrain=${obelisk.getModifiedPlayerDrain()} factor=${"%.4f".format(obelisk.getModifiedDrainFactor())}"
                    )
                }, false)
                1
            }
        )

        root.then(
            Commands.literal("locate").executes { ctx ->
                val player = ctx.source.playerOrException
                val nearest = ObeliskRuntimeService.findNearestObelisk(player.serverLevel(), player.blockPosition(), radiusChunks = 8)
                if (nearest == null) {
                    ctx.source.sendFailure(Component.literal("No loaded rift anchors found within 8 chunks."))
                    return@executes 0
                }

                val distance = sqrt(nearest.blockPos.distSqr(player.blockPosition()))
                ctx.source.sendSuccess({
                    Component.literal(
                        "Nearest loaded rift anchor is ${nearest.definitionId} -> ${nearest.targetTemplateId} at ${nearest.blockPos.x}, ${nearest.blockPos.y}, ${nearest.blockPos.z} (${distance.toInt()} blocks)"
                    )
                }, false)
                1
            }
        )

        root.then(
            Commands.literal("reload_data").executes { ctx ->
                ObeliskDataManager.reload()
                ctx.source.sendSuccess({ Component.literal("Reloaded rift anchor definitions and reward tables.") }, true)
                1
            }
        )

        event.dispatcher.register(root)
    }

    private fun spawnDebugObelisk(player: ServerPlayer, requestedTemplate: String?): Int {
        val template = requestedTemplate?.takeIf { ObeliskDataManager.getObelisk(it) != null }
            ?: requestedTemplate?.let {
                player.sendSystemMessage(Component.literal("Unknown rift anchor definition '$it'."))
                return 0
            }
            ?: ObeliskDataManager.enabledObelisks()
                .firstOrNull { RunBackendManager.backend.validateTemplate(player.server, it.instanceTemplateId) == null }
                ?.id
            ?: return 0

        val level = player.serverLevel()
        val pos = debugSpawnPos(level, player.blockPosition())
        if (!placeObelisk(level, pos)) {
            player.sendSystemMessage(Component.literal("Failed to place debug rift anchor at $pos"))
            return 0
        }

        val obelisk = level.getBlockEntity(pos) as? ObeliskBlockEntity
        if (obelisk == null) {
            player.sendSystemMessage(Component.literal("Placed block but no rift anchor block entity was created"))
            return 0
        }

        obelisk.setTargetTemplate(template)
        obelisk.fillToCapacity()
        obelisk.setActiveRun(null)
        player.sendSystemMessage(Component.literal("Spawned charged $template rift anchor at $pos"))
        return 1
    }

    private fun debugSpawnPos(level: ServerLevel, origin: BlockPos): BlockPos {
        val x = origin.x + 2
        val z = origin.z + 2
        val y = topSolidY(level, x, z).coerceAtLeast(level.minBuildHeight) + 1
        return BlockPos(x, y, z)
    }

    private fun topSolidY(level: ServerLevel, x: Int, z: Int): Int {
        for (y in (level.maxBuildHeight - 1) downTo level.minBuildHeight) {
            val state = level.getBlockState(BlockPos(x, y, z))
            if (!state.isAir && state.fluidState.isEmpty) {
                return y
            }
        }
        return level.minBuildHeight - 1
    }

    private fun placeObelisk(level: ServerLevel, pos: BlockPos): Boolean {
        for (offsetY in 0..3) {
            level.setBlock(pos.above(offsetY), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)
        }
        val state: BlockState = dev.yourname.obelisks.registry.ModBlocks.OBELISK.get().defaultBlockState()
        return level.setBlock(pos, state, 3)
    }

    private fun lookedAtObelisk(player: ServerPlayer): ObeliskBlockEntity? {
        val hit = player.pick(5.0, 0.0f, false) as? net.minecraft.world.phys.BlockHitResult ?: return null
        return player.serverLevel().getBlockEntity(hit.blockPos) as? ObeliskBlockEntity
    }
}
