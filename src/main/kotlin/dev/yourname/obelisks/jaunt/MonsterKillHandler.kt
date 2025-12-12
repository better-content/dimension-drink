package dev.yourname.obelisks.jaunt

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.MobCategory
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Tracks monster kills during runs for emerald reward calculation.
 */
object MonsterKillHandler {

    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val victim = event.entity

        // Only process server-side
        if (victim.level().isClientSide) return

        // Only count monsters
        if (victim.type.category != MobCategory.MONSTER) return

        // Get the killer
        val damageSource = event.source
        val killer = damageSource.entity

        // Must be killed by a player
        if (killer !is ServerPlayer) return

        // Check if the player is in an active run
        val server = killer.server
        val runManager = RunManager.get(server)
        val runData = runManager.getPlayerRun(killer.uuid) ?: return

        // Verify the kill happened in the run dimension
        if (victim.level().dimension() != runData.runDimensionKey) return

        // Increment monster kill counter
        runData.monstersKilled++
        runManager.setDirty()

        // Debug logging
        if (runData.monstersKilled % 10 == 0) {
            println("[Obelisks] Run ${runData.runId}: ${runData.monstersKilled} monsters killed")
        }
    }
}
