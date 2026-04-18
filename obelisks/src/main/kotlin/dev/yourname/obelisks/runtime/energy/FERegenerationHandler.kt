package dev.yourname.obelisks.runtime.energy

import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.lang.ref.WeakReference

object FERegenerationHandler {
    private val trackedObelisks = linkedSetOf<WeakReference<ObeliskBlockEntity>>()
    private var tickCounter = 0

    fun registerObelisk(obelisk: ObeliskBlockEntity) {
        synchronized(trackedObelisks) {
            trackedObelisks.add(WeakReference(obelisk))
        }
    }

    fun unregisterObelisk(obelisk: ObeliskBlockEntity) {
        synchronized(trackedObelisks) {
            trackedObelisks.removeIf { it.get() == obelisk || it.get() == null }
        }
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        tickCounter++
        if (tickCounter < ObeliskConstants.TICKS_PER_SECOND) return
        tickCounter = 0

        val stale = mutableListOf<WeakReference<ObeliskBlockEntity>>()
        val snapshot = synchronized(trackedObelisks) { trackedObelisks.toList() }
        snapshot.forEach { ref ->
            val obelisk = ref.get()
            if (obelisk == null || obelisk.isRemoved) {
                stale += ref
            } else {
                obelisk.regenerateEnergy(obelisk.getModifiedRegenRate() * ObeliskConstants.TICKS_PER_SECOND)
            }
        }
        synchronized(trackedObelisks) {
            trackedObelisks.removeAll(stale.toSet())
        }
    }
}
