package dev.yourname.obelisks.registry

import dev.yourname.obelisks.network.ModNetwork
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
        ModMenuTypes.MENU_TYPES.register(bus)

        // Register network packets
        ModNetwork.register()
    }
}
