package dev.yourname.obelisks.registry

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModBlockEntities {
    val REGISTRY: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID)

    val OBELISK: RegistryObject<BlockEntityType<ObeliskBlockEntity>> = REGISTRY.register("dimensional_font") {
        BlockEntityType.Builder.of(::ObeliskBlockEntity, ModBlocks.OBELISK.get()).build(null)
    }
}
