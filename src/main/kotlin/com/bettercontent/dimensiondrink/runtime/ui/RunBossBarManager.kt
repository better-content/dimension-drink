package com.bettercontent.dimensiondrink.runtime.ui

import com.bettercontent.dimensiondrink.ObeliskConstants
import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.runtime.run.RunRegistry
import com.bettercontent.dimensiondrink.runtime.run.RunState
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

object RunBossBarManager {

    private val activeBossBars = linkedMapOf<UUID, ServerBossEvent>()

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }

        val server = event.server
        val activeRuns = RunRegistry.currentRuns()
        val validRunIds = activeRuns.map { it.id }.toSet()

        activeRuns.forEach { run ->
            if (run.state != RunState.ACTIVE && run.state != RunState.WARMING_UP) {
                removeBossBar(run.id)
                return@forEach
            }

            val originLevel = run.originLevelKey?.let(server::getLevel)
            val obelisk = run.originObeliskPos?.let { pos -> originLevel?.getBlockEntity(pos) as? ObeliskBlockEntity }
            if (obelisk == null) {
                removeBossBar(run.id)
                return@forEach
            }

            val blood = obelisk.getBloodPercent()
            if (blood >= ObeliskConstants.BOSS_BAR_SHOW_THRESHOLD) {
                removeBossBar(run.id)
                return@forEach
            }

            val bossBar = activeBossBars.getOrPut(run.id) {
                ServerBossEvent(
                    Component.literal("Font Trip Juice"),
                    BossEvent.BossBarColor.GREEN,
                    BossEvent.BossBarOverlay.PROGRESS
                )
            }

            bossBar.progress = blood.toFloat().coerceIn(0f, 1f)
            bossBar.color = when {
                blood > ObeliskConstants.BOSS_BAR_GREEN_THRESHOLD -> BossEvent.BossBarColor.GREEN
                blood > ObeliskConstants.BOSS_BAR_YELLOW_THRESHOLD -> BossEvent.BossBarColor.YELLOW
                else -> BossEvent.BossBarColor.RED
            }
            bossBar.name = Component.literal("Font Trip Juice: ${(blood * 100.0).toInt()}%")

            val livePlayers = run.activePlayers.mapNotNull(server.playerList::getPlayer).toSet()
            livePlayers.forEach { player ->
                if (!bossBar.players.contains(player)) {
                    bossBar.addPlayer(player)
                }
            }
            bossBar.players.toList()
                .filter { it !in livePlayers }
                .forEach(bossBar::removePlayer)
        }

        activeBossBars.keys
            .filter { it !in validRunIds }
            .toList()
            .forEach(::removeBossBar)
    }

    @SubscribeEvent
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        activeBossBars.values.forEach { it.removePlayer(player) }
    }

    fun hasBossBar(runId: UUID): Boolean = activeBossBars.containsKey(runId)

    fun removeBossBar(runId: UUID) {
        activeBossBars.remove(runId)?.let { bossBar ->
            bossBar.players.toList().forEach(bossBar::removePlayer)
        }
    }
}
