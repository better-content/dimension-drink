package dev.yourname.obelisks.commands

import com.mojang.brigadier.arguments.LongArgumentType
import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.player.PlayerReturnHandler
import dev.yourname.obelisks.player.getRunInfo
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/**
 * Registers and builds all "/obelisk" debug/admin commands.
 *
 * Commands provided:
 * - /obelisk debug_spawn — spawns an obelisk near the command source
 * - /obelisk list_runs — lists all active runs on the server
 * - /obelisk return — returns the executing player to their origin obelisk
 * - /obelisk cleanup_run <runId> — force-cleans up a specific run
 * - /obelisk info — shows run/FE info for the executing player
 */
@Mod.EventBusSubscriber(modid = MOD_ID)
object ObeliskCommands {
    /**
     * Forge callback for command registration. Wires the root "obelisk" node and subcommands.
     */
    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val root = Commands.literal("obelisk").requires { it.hasPermission(2) }

        // debug_spawn
        root.then(
            Commands.literal("debug_spawn").executes { ctx ->
                val source = ctx.source
                val level = source.level
                val pos = BlockPos.containing(source.position)

                val success = dev.yourname.obelisks.util.ObeliskPlacer.placeObelisk(level, pos)
                if (success) {
                    var obeliskPos: BlockPos? = null
                    for (y in 0..64) {
                        val testPos = BlockPos(pos.x, pos.y + y, pos.z)
                        val be = level.getBlockEntity(testPos)
                        if (be is dev.yourname.obelisks.content.ObeliskBlockEntity) {
                            obeliskPos = testPos
                            break
                        }
                    }
                    if (obeliskPos != null) {
                        val blockEntity = level.getBlockEntity(obeliskPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
                        val type = blockEntity?.baseType?.name ?: "UNKNOWN"
                        source.sendSuccess({ Component.literal("Spawned $type obelisk at $obeliskPos (stem built upward from ground)") }, true)
                    } else {
                        source.sendSuccess({ Component.literal("Spawned obelisk (search nearby if not visible)") }, true)
                    }
                } else {
                    source.sendFailure(Component.literal("Failed to spawn obelisk - no solid ground found below"))
                }
                1
            }
        )

        // list_runs
        root.then(
            Commands.literal("list_runs").executes { ctx ->
                val source = ctx.source
                val runManager = RunManager.get(source.server)
                val runs = runManager.getAllRuns()
                if (runs.isEmpty()) {
                    source.sendSuccess({ Component.literal("No active runs") }, false)
                } else {
                    source.sendSuccess({ Component.literal("Active runs (${runs.size}):") }, false)
                    runs.forEach { run ->
                        source.sendSuccess({ Component.literal("  Run #${run.runId}: ${run.baseType} (${run.activePlayers.size} players)") }, false)
                    }
                }
                1
            }
        )

        // return
        root.then(
            Commands.literal("return").executes { ctx ->
                val source = ctx.source
                val player = source.playerOrException
                val runInfo = player.getRunInfo()
                if (runInfo?.isInRun() == true) {
                    PlayerReturnHandler.returnPlayer(player)
                    source.sendSuccess({ Component.literal("Returning to obelisk...") }, false)
                } else {
                    source.sendFailure(Component.literal("You are not in a run!"))
                }
                1
            }
        )

        // cleanup_run <runId>
        root.then(
            Commands.literal("cleanup_run").then(
                Commands.argument("runId", LongArgumentType.longArg(0)).executes { ctx ->
                    val source = ctx.source
                    val runId = LongArgumentType.getLong(ctx, "runId")
                    val runManager = RunManager.get(source.server)
                    val run = runManager.getAllRuns().find { it.runId == runId }
                    if (run != null) {
                        run.activePlayers.toList().forEach { playerId ->
                            source.server.playerList.getPlayer(playerId)?.let { player ->
                                PlayerReturnHandler.returnPlayerToOrigin(player, "Admin cleanup")
                            }
                        }
                        source.sendSuccess({ Component.literal("Cleaning up run #$runId...") }, true)
                    } else {
                        source.sendFailure(Component.literal("Run #$runId not found"))
                    }
                    1
                }
            )
        )

        // info
        root.then(
            Commands.literal("info").executes { ctx ->
                val source = ctx.source
                val player = source.playerOrException
                val runInfo = player.getRunInfo()
                if (runInfo?.isInRun() == true) {
                    source.sendSuccess({ Component.literal("In run #${runInfo.runId}") }, false)
                    source.sendSuccess({ Component.literal("Origin: ${runInfo.originPos} in ${runInfo.originDimension?.location()}") }, false)

                    val runManager = RunManager.get(source.server)
                    val obeliskId = runInfo.originObeliskId
                    val currentRunId = runInfo.runId
                    if (obeliskId == null || currentRunId == null) return@executes 1
                    val runData = runManager.getRun(obeliskId, currentRunId)
                    if (runData != null) {
                        val originLevel = source.server.getLevel(runData.originDimension)
                        val obeliskBE = originLevel?.getBlockEntity(runData.originObeliskPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
                        if (obeliskBE != null) {
                            val fePercent = (obeliskBE.getEnergyPercent() * 100).toInt()
                            source.sendSuccess({ Component.literal("Dimension Stability: $fePercent% (${obeliskBE.getEnergyStored()}/${obeliskBE.getMaxEnergyStored()} FE)") }, false)
                        }
                    }
                } else {
                    source.sendSuccess({ Component.literal("Not in a run") }, false)
                }
                1
            }
        )

        event.dispatcher.register(root)
    }
}
