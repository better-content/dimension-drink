package dev.yourname.obelisks.jaunt

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Manages boss bars for displaying FE levels during runs.
 * Phase 3: Boss bar shows when FE < 90%
 */
object RunBossBarManager {

    // Maps runId -> BossBar
    private val activeBossBars = mutableMapOf<Long, ServerBossEvent>()

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server
        val runManager = RunManager.get(server)

        // Update boss bars for all active runs
        for (runData in runManager.getAllRuns()) {
            // Get origin obelisk to read FE level
            val originLevel = server.getLevel(runData.originDimension) ?: continue
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? ObeliskBlockEntity ?: continue

            val fePercent = obeliskBE.getEnergyPercent()

            if (fePercent < ObelisksConstants.BOSS_BAR_SHOW_THRESHOLD) {
                // Show or update boss bar
                updateBossBar(runData, fePercent, server)
            } else {
                // Hide boss bar if FE is above threshold
                removeBossBar(runData.runId)
            }
        }

        // Clean up boss bars for runs that no longer exist
        val validRunIds = runManager.getAllRuns().map { it.runId }.toSet()
        val toRemove = activeBossBars.keys.filter { it !in validRunIds }
        toRemove.forEach { removeBossBar(it) }
    }

    @SubscribeEvent
    fun onPlayerChangeDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val server = player.server
        val runManager = RunManager.get(server)

        // Check if player entered a run dimension
        val runData = runManager.getRunByDimension(event.to)
        if (runData != null) {
            // Add player to boss bar if it exists
            activeBossBars[runData.runId]?.addPlayer(player)
        }

        // Check if player left a run dimension
        val previousRun = runManager.getRunByDimension(event.from)
        if (previousRun != null) {
            // Remove player from boss bar
            activeBossBars[previousRun.runId]?.removePlayer(player)
        }
    }

    private fun updateBossBar(runData: RunData, fePercent: Double, server: net.minecraft.server.MinecraftServer) {
        val bossBar = activeBossBars.getOrPut(runData.runId) {
            // Create new boss bar
            ServerBossEvent(
                Component.literal("Dimension Stability"),
                BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarOverlay.PROGRESS
            )
        }

        // Update progress
        bossBar.progress = fePercent.toFloat().coerceIn(0f, 1f)

        // Update color based on FE level
        bossBar.color = when {
            fePercent > ObelisksConstants.BOSS_BAR_GREEN_THRESHOLD -> BossEvent.BossBarColor.GREEN
            fePercent > ObelisksConstants.BOSS_BAR_YELLOW_THRESHOLD -> BossEvent.BossBarColor.YELLOW
            else -> BossEvent.BossBarColor.RED
        }

        // Update title with percentage
        val percentDisplay = (fePercent * 100).toInt()
        bossBar.name = Component.literal("Dimension Stability: $percentDisplay%")

        // Ensure all players in the run are added to the boss bar
        for (playerId in runData.activePlayers) {
            val player = server.playerList.getPlayer(playerId)
            if (player != null && !bossBar.players.contains(player)) {
                bossBar.addPlayer(player)
            }
        }

        // Remove players who are no longer in the run
        val playersToRemove = bossBar.players.filter { it.uuid !in runData.activePlayers }
        playersToRemove.forEach { bossBar.removePlayer(it) }
    }

    fun removeBossBar(runId: Long) {
        activeBossBars.remove(runId)?.let { bossBar ->
            // Remove all players from the boss bar
            bossBar.players.toList().forEach { bossBar.removePlayer(it) }
        }
    }

    /**
     * Called when a player logs out - remove them from all boss bars.
     */
    @SubscribeEvent
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        activeBossBars.values.forEach { it.removePlayer(player) }
    }
}
