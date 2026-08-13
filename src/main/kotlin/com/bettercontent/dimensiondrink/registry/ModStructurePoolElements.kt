package com.bettercontent.dimensiondrink.registry

import com.bettercontent.dimensiondrink.MOD_ID
import com.bettercontent.dimensiondrink.worldgen.village.ChanceLegacySinglePoolElement
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject

object ModStructurePoolElements {
    private val REGISTRY: DeferredRegister<StructurePoolElementType<*>> =
        DeferredRegister.create(Registries.STRUCTURE_POOL_ELEMENT, MOD_ID)

    val CHANCE_LEGACY_SINGLE: RegistryObject<StructurePoolElementType<ChanceLegacySinglePoolElement>> =
        REGISTRY.register("chance_legacy_single_pool_element") {
            StructurePoolElementType<ChanceLegacySinglePoolElement> { ChanceLegacySinglePoolElement.CODEC }
        }

    fun register(bus: IEventBus) {
        REGISTRY.register(bus)
    }
}
