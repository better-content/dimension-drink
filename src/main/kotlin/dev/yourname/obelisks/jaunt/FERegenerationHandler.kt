package dev.yourname.obelisks.jaunt

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.lang.ref.WeakReference

/**
 * Handles natural FE regeneration for idle obelisks.
 * Obelisks regenerate FE slowly when they have no active run.
 *
 * Strategy: Track all obelisk block entities via registration, then iterate through them.
 * Uses weak references to allow garbage collection of unloaded block entities.
 */
object FERegenerationHandler {

    private var tickCounter = 0
    private val trackedObelisks = mutableSetOf<WeakReference<ObeliskBlockEntity>>()

    /**
     * Register an obelisk block entity for FE regeneration tracking.
     * Called by ObeliskBlockEntity when it's created or loaded.
     */
    fun registerObelisk(obelisk: ObeliskBlockEntity) {
        synchronized(trackedObelisks) {
            trackedObelisks.add(WeakReference(obelisk))
        }
    }

    /**
     * Unregister an obelisk block entity from FE regeneration tracking.
     * Called by ObeliskBlockEntity when it's removed.
     */
    fun unregisterObelisk(obelisk: ObeliskBlockEntity) {
        synchronized(trackedObelisks) {
            trackedObelisks.removeIf { it.get() == obelisk }
        }
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        // Only process every TICKS_PER_SECOND ticks (once per second) to reduce overhead
        tickCounter++
        if (tickCounter < ObelisksConstants.TICKS_PER_SECOND) return
        tickCounter = 0

        // Clean up dead weak references and regenerate FE for active obelisks
        // Use toList() to avoid ConcurrentModificationException
        val toRemove = mutableListOf<WeakReference<ObeliskBlockEntity>>()
        val snapshot = synchronized(trackedObelisks) {
            trackedObelisks.toList()
        }

        for (weakRef in snapshot) {
            val obelisk = weakRef.get()
            if (obelisk == null || obelisk.isRemoved) {
                // Block entity was garbage collected or removed - mark for cleanup
                toRemove.add(weakRef)
            } else {
                // Regenerate FE if idle (method handles checks: not active, not full)
                // Multiply by TICKS_PER_SECOND since we only check once per second
                val regenAmount = ObelisksConstants.FE_REGEN_PER_TICK * ObelisksConstants.TICKS_PER_SECOND
                obelisk.regenerateEnergy(regenAmount)
            }
        }

        // Remove dead references in a separate pass
        synchronized(trackedObelisks) {
            trackedObelisks.removeAll(toRemove)
        }
    }
}
