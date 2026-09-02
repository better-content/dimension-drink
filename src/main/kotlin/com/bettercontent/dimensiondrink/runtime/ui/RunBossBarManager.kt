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
import net.minecraftforge.event.server.ServerStoppedEvent
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
            if (run.state != RunState.ACTIVE) {
                removeBossBar(run.id)
                return@forEach
            }

            val originLevel = run.originLevelKey?.let(server::getLevel)
            val obelisk = run.originObeliskPos?.let { pos -> originLevel?.getBlockEntity(pos) as? ObeliskBlockEntity }
            if (obelisk == null) {
                removeBossBar(run.id)
                return@forEach
            }

            val presentation = presentation(obelisk.getChargePercent())
            if (presentation == null) {
                removeBossBar(run.id)
                return@forEach
            }

            val bossBar = activeBossBars.getOrPut(run.id) {
                ServerBossEvent(
                    Component.literal("Font Charge"),
                    BossEvent.BossBarColor.GREEN,
                    BossEvent.BossBarOverlay.PROGRESS
                )
            }

            bossBar.progress = presentation.progress
            bossBar.color = presentation.color
            bossBar.name = Component.literal(presentation.title)

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

    internal fun bossBarProgress(runId: UUID): Float? = activeBossBars[runId]?.progress

    internal fun bossBarTitle(runId: UUID): String? = activeBossBars[runId]?.name?.string

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        activeBossBars.keys.toList().forEach(::removeBossBar)
    }

    fun removeBossBar(runId: UUID) {
        activeBossBars.remove(runId)?.let { bossBar ->
            bossBar.players.toList().forEach(bossBar::removePlayer)
        }
    }

    internal fun presentation(chargeFraction: Double): FontBossBarPresentation? {
        val charge = chargeFraction.coerceIn(0.0, 1.0)
        if (charge >= ObeliskConstants.BOSS_BAR_SHOW_THRESHOLD) return null
        return FontBossBarPresentation(
            progress = charge.toFloat(),
            title = "Font Charge: ${(charge * 100.0).toInt()}%",
            color = when {
                charge > ObeliskConstants.BOSS_BAR_GREEN_THRESHOLD -> BossEvent.BossBarColor.GREEN
                charge > ObeliskConstants.BOSS_BAR_YELLOW_THRESHOLD -> BossEvent.BossBarColor.YELLOW
                else -> BossEvent.BossBarColor.RED
            }
        )
    }
}

internal data class FontBossBarPresentation(
    val progress: Float,
    val title: String,
    val color: BossEvent.BossBarColor
)
