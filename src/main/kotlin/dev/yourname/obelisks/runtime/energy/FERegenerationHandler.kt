package dev.yourname.obelisks.runtime.energy

import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.lang.ref.WeakReference

object FERegenerationHandler {
    private val trackedObelisks = linkedSetOf<WeakReference<ObeliskBlockEntity>>()

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

        val stale = mutableListOf<WeakReference<ObeliskBlockEntity>>()
        val snapshot = synchronized(trackedObelisks) { trackedObelisks.toList() }
        snapshot.forEach { ref ->
            val obelisk = ref.get()
            if (obelisk == null || obelisk.isRemoved) {
                stale += ref
            } else {
                obelisk.regenerateBlood(obelisk.getModifiedRegenRate())
                obelisk.updateGraveSoilGlow()
            }
        }
        synchronized(trackedObelisks) {
            trackedObelisks.removeAll(stale.toSet())
        }
    }
}
