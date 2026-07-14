package dev.yourname.dimensiondrink.runtime.energy

import dev.yourname.dimensiondrink.content.ObeliskBlockEntity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.lang.ref.WeakReference

object FERegenerationHandler {
    private val trackedDimensionDrinks = linkedSetOf<WeakReference<ObeliskBlockEntity>>()

    fun registerObelisk(obelisk: ObeliskBlockEntity) {
        synchronized(trackedDimensionDrinks) {
            trackedDimensionDrinks.add(WeakReference(obelisk))
        }
    }

    fun unregisterObelisk(obelisk: ObeliskBlockEntity) {
        synchronized(trackedDimensionDrinks) {
            trackedDimensionDrinks.removeIf { it.get() == obelisk || it.get() == null }
        }
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val stale = mutableListOf<WeakReference<ObeliskBlockEntity>>()
        val snapshot = synchronized(trackedDimensionDrinks) { trackedDimensionDrinks.toList() }
        snapshot.forEach { ref ->
            val obelisk = ref.get()
            if (obelisk == null || obelisk.isRemoved) {
                stale += ref
            } else {
                obelisk.regenerateBlood(obelisk.getModifiedRegenRate())
            }
        }
        synchronized(trackedDimensionDrinks) {
            trackedDimensionDrinks.removeAll(stale.toSet())
        }
    }
}
