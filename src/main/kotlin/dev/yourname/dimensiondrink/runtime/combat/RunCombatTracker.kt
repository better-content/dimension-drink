package dev.yourname.dimensiondrink.runtime.combat

import dev.yourname.dimensiondrink.runtime.run.RunRegistry
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.projectile.Projectile
import net.minecraftforge.event.entity.living.LivingDamageEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object RunCombatTracker {

    @SubscribeEvent
    fun onLivingDamage(event: LivingDamageEvent) {
        val victim = event.entity
        if (victim.level().isClientSide || victim.type.category != MobCategory.MONSTER) {
            return
        }

        val attacker = resolvePlayer(event.source) ?: return
        RunRegistry.recordDamage(attacker.uuid, victim.level().dimension(), event.amount)
    }

    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val victim = event.entity
        if (victim.level().isClientSide || victim.type.category != MobCategory.MONSTER) {
            return
        }

        val killer = resolvePlayer(event.source)
        val server = killer?.server ?: victim.server ?: return
        RunRegistry.recordMonsterDeath(server, victim.level().dimension(), victim.blockPosition())
    }

    private fun resolvePlayer(source: DamageSource): ServerPlayer? {
        val sourceEntity = source.entity
        if (sourceEntity is ServerPlayer) {
            return sourceEntity
        }

        val direct = source.directEntity
        if (direct is Projectile) {
            return direct.owner as? ServerPlayer
        }

        return if (sourceEntity is Projectile) sourceEntity.owner as? ServerPlayer else null
    }
}
