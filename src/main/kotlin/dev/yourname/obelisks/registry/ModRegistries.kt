package dev.yourname.obelisks.registry

import net.minecraftforge.eventbus.api.IEventBus

/**
 * Centralized registration for all DeferredRegisters used by the mod.
 */
object ModRegistries {
    fun registerAll(bus: IEventBus) {
        ModBlocks.REGISTRY.register(bus)
        ModItems.REGISTRY.register(bus)
        ModBlockEntities.REGISTRY.register(bus)
        ModFeatures.REGISTRY.register(bus)
    }
}
