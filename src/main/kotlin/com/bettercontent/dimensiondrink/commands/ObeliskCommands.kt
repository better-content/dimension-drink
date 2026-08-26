package com.bettercontent.dimensiondrink.commands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.logging.LogUtils
import com.bettercontent.dimensiondrink.api.RunBeginResult
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.runtime.ObeliskRuntimeService
import com.bettercontent.dimensiondrink.runtime.backend.RunBackendManager
import com.bettercontent.dimensiondrink.runtime.backend.RunSiteSavedData
import com.bettercontent.dimensiondrink.runtime.backend.SiteState
import com.bettercontent.dimensiondrink.runtime.run.RunRegistry
import com.bettercontent.dimensiondrink.runtime.run.RunSavedData
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.UuidArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import kotlin.math.sqrt

object ObeliskCommands {
    private val logger = LogUtils.getLogger()

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val root = Commands.literal("font").requires { it.hasPermission(2) }

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
                    ctx.source.sendSuccess({ Component.literal("No active or pending font runs.") }, false)
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
                    ctx.source.sendSuccess({ Component.literal("Returning to origin font.") }, false)
                    1
                } else {
                    ctx.source.sendFailure(Component.literal("Player is not bound to an active font run."))
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
                    ctx.source.sendSuccess({ Component.literal("Player is not assigned to a font run.") }, false)
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
                                "Font charge=${obelisk.chargeStored.toInt()}/${obelisk.getMaxCharge().toInt()} cooldown=${obelisk.getCooldownRemainingTicks()} activeRun=${obelisk.activeRunId}"
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
                    ctx.source.sendFailure(Component.literal("Not looking at a dimensional font."))
                    return@executes 0
                }

                ctx.source.sendSuccess({ Component.literal("Dimensional font ${obelisk.obeliskId} definition=${obelisk.definitionId} template=${obelisk.targetTemplateId}") }, false)
                obelisk.modifiers.forEachIndexed { index, modifier ->
                    ctx.source.sendSuccess({
                        Component.literal("${index + 1}. ${modifier.stat.name} +${modifier.bonusPercent}%")
                    }, false)
                }
                ctx.source.sendSuccess({
                    Component.literal(
                        "charge=${obelisk.chargeStored.toInt()}/${obelisk.getMaxCharge().toInt()} regen=${"%.3f".format(obelisk.getModifiedRegenRate())} baseDrain=${"%.3f".format(obelisk.getModifiedBaseDrain())} playerDrain=${"%.3f".format(obelisk.getModifiedPlayerDrain())}"
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
                    ctx.source.sendFailure(Component.literal("No loaded dimension drink found within 8 chunks."))
                    return@executes 0
                }

                val distance = sqrt(nearest.blockPos.distSqr(player.blockPosition()))
                ctx.source.sendSuccess({
                    Component.literal(
                        "Nearest loaded dimensional font is ${nearest.definitionId} -> ${nearest.targetTemplateId} at ${nearest.blockPos.x}, ${nearest.blockPos.y}, ${nearest.blockPos.z} (${distance.toInt()} blocks)"
                    )
                }, false)
                1
            }
        )

        root.then(
            Commands.literal("reload_data").executes { ctx ->
                ObeliskDataManager.reload()
                ctx.source.sendSuccess({ Component.literal("Reloaded dimensional font definitions and reward tables.") }, true)
                1
            }
        )

        val smoke = Commands.literal("smoke")
        smoke.then(
            Commands.literal("status").executes { ctx ->
                val sites = RunSiteSavedData.get(ctx.source.server).snapshot()
                val states = sites.groupingBy { it.state }.eachCount()
                ctx.source.sendSuccess({
                    Component.literal(
                        "FONT_SMOKE STATUS ${RunRegistry.describePreparedInstances()} " +
                            "sites=${sites.size} prepared=${states[SiteState.PREPARED] ?: 0} " +
                            "active=${states[SiteState.ACTIVE] ?: 0}"
                    )
                }, false)
                1
            }
        )
        smoke.then(
            Commands.literal("cleanup").executes { ctx ->
                val runIds = RunRegistry.snapshot().map { it.id }
                val cleaned = runIds.count { RunRegistry.finishRun(ctx.source.server, it) }
                ctx.source.sendSuccess({ Component.literal("FONT_SMOKE CLEANUP cleaned=$cleaned") }, true)
                cleaned.coerceAtLeast(1)
            }
        )
        smoke.then(
            Commands.literal("run")
                .executes { ctx -> runConsoleSmoke(ctx.source, defaultSmokeTemplate(ctx.source), 1) }
                .then(
                    Commands.argument("template", StringArgumentType.word())
                        .executes { ctx ->
                            runConsoleSmoke(ctx.source, StringArgumentType.getString(ctx, "template"), 1)
                        }
                        .then(
                            Commands.argument("cycles", IntegerArgumentType.integer(1, 100)).executes { ctx ->
                                runConsoleSmoke(
                                    ctx.source,
                                    StringArgumentType.getString(ctx, "template"),
                                    IntegerArgumentType.getInteger(ctx, "cycles")
                                )
                            }
                        )
                )
        )
        root.then(smoke)

        event.dispatcher.register(root)
    }

    private fun defaultSmokeTemplate(source: CommandSourceStack): String {
        return ObeliskDataManager.enabledDimensionDrinks()
            .firstOrNull { RunBackendManager.backend.validateTemplate(source.server, it.instanceTemplateId) == null }
            ?.id ?: "end"
    }

    private fun runConsoleSmoke(source: CommandSourceStack, definitionId: String, cycles: Int): Int {
        val createdRunIds = linkedSetOf<UUID>()
        val usedSiteIds = linkedSetOf<UUID>()
        val started = System.nanoTime()
        return try {
            repeat(cycles) { cycle ->
                val result = RunRegistry.beginRun(source.server, UUID.randomUUID(), definitionId)
                val handle = (result as? RunBeginResult.Accepted)?.run
                    ?: error("cycle ${cycle + 1}: ${(result as RunBeginResult.Rejected).reason}")
                createdRunIds += handle.runId
                usedSiteIds += handle.instanceId

                val record = RunRegistry.get(handle.runId) ?: error("cycle ${cycle + 1}: registry record missing")
                check(record.backendLevelKey?.let(source.server::getLevel) != null) {
                    "cycle ${cycle + 1}: target dimension is not loaded"
                }
                check(record.backendSiteBounds != null) { "cycle ${cycle + 1}: site bounds missing" }
                check(RunSavedData.get(source.server).snapshot().any { it.id == handle.runId }) {
                    "cycle ${cycle + 1}: persisted run record missing"
                }
                check(RunSiteSavedData.get(source.server).get(handle.instanceId)?.state == SiteState.ACTIVE) {
                    "cycle ${cycle + 1}: backend site is not active"
                }
                check(RunRegistry.finishRun(source.server, handle.runId)) {
                    "cycle ${cycle + 1}: cleanup rejected"
                }
                createdRunIds -= handle.runId
                check(RunRegistry.get(handle.runId) == null) { "cycle ${cycle + 1}: registry leak" }
                check(RunSavedData.get(source.server).snapshot().none { it.id == handle.runId }) {
                    "cycle ${cycle + 1}: saved-data leak"
                }
                check(RunSiteSavedData.get(source.server).get(handle.instanceId)?.state == SiteState.PREPARED) {
                    "cycle ${cycle + 1}: site was not returned to reusable state"
                }
            }
            check(usedSiteIds.size == 1) { "site reuse failed: allocated ${usedSiteIds.size} sites" }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            source.sendSuccess({
                Component.literal(
                    "FONT_SMOKE PASS template=$definitionId cycles=$cycles site=${usedSiteIds.single()} elapsedMs=$elapsedMs"
                )
            }, true)
            1
        } catch (failure: Throwable) {
            createdRunIds.forEach { RunRegistry.finishRun(source.server, it) }
            logger.error("FONT_SMOKE FAIL template={} cycles={}", definitionId, cycles, failure)
            source.sendFailure(Component.literal("FONT_SMOKE FAIL ${failure.message ?: failure.javaClass.simpleName}"))
            0
        }
    }

    private fun spawnDebugObelisk(player: ServerPlayer, requestedTemplate: String?): Int {
        val template = requestedTemplate?.takeIf { ObeliskDataManager.getObelisk(it) != null }
            ?: requestedTemplate?.let {
                player.sendSystemMessage(Component.literal("Unknown dimensional font definition '$it'."))
                return 0
            }
            ?: ObeliskDataManager.enabledDimensionDrinks()
                .firstOrNull { RunBackendManager.backend.validateTemplate(player.server, it.instanceTemplateId) == null }
                ?.id
            ?: return 0

        val level = player.serverLevel()
        val pos = debugSpawnPos(level, player.blockPosition())
        if (!placeObelisk(level, pos)) {
            player.sendSystemMessage(Component.literal("Failed to place debug dimensional font at $pos"))
            return 0
        }

        val obelisk = level.getBlockEntity(pos) as? ObeliskBlockEntity
        if (obelisk == null) {
            player.sendSystemMessage(Component.literal("Placed block but no dimensional font block entity was created"))
            return 0
        }

        obelisk.setTargetTemplate(template)
        obelisk.fillToCapacity()
        obelisk.setActiveRun(null)
        player.sendSystemMessage(Component.literal("Spawned filled $template dimensional font at $pos"))
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
        val floodedSite = hasWaterInColumnAbove(level, pos)
        for (offsetY in 0..3) {
            val clearPos = pos.above(offsetY)
            level.setBlock(clearPos, clearanceState(level, clearPos, floodedSite), 3)
        }
        val state: BlockState = com.bettercontent.dimensiondrink.registry.ModBlocks.OBELISK.get().defaultBlockState()
        return level.setBlock(pos, state, 3)
    }

    private fun clearanceState(level: ServerLevel, pos: BlockPos, floodedSite: Boolean): BlockState {
        return if (floodedSite && hasWaterInColumnAbove(level, pos)) {
            Blocks.WATER.defaultBlockState()
        } else {
            Blocks.AIR.defaultBlockState()
        }
    }

    private fun hasWaterInColumnAbove(level: ServerLevel, pos: BlockPos): Boolean {
        for (y in pos.y..(level.maxBuildHeight - 1)) {
            val state = level.getBlockState(BlockPos(pos.x, y, pos.z))
            if (isWater(state)) {
                return true
            }
            if (y > pos.y && state.isAir) {
                return false
            }
        }
        return false
    }

    private fun isWater(state: BlockState): Boolean {
        val fluidType = state.fluidState.type
        return fluidType == Fluids.WATER || fluidType == Fluids.FLOWING_WATER
    }

    private fun lookedAtObelisk(player: ServerPlayer): ObeliskBlockEntity? {
        val hit = player.pick(5.0, 0.0f, false) as? net.minecraft.world.phys.BlockHitResult ?: return null
        return player.serverLevel().getBlockEntity(hit.blockPos) as? ObeliskBlockEntity
    }
}
