package dev.yourname.obelisks.registry

import net.minecraftforge.eventbus.api.IEventBus

object ModRegistries {
    fun registerAll(bus: IEventBus) {
        ModStructurePoolElements.register(bus)
        ModBlocks.REGISTRY.register(bus)
        ModItems.REGISTRY.register(bus)
        ModBlockEntities.REGISTRY.register(bus)
        ModFeatures.REGISTRY.register(bus)
    }
}
