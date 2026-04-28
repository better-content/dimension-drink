package dev.yourname.obelisks.registry

import dev.yourname.obelisks.MOD_ID
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModItems {
    val REGISTRY: DeferredRegister<Item> = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)

    val OBELISK: RegistryObject<Item> = REGISTRY.register("meteoric_rift_anchor") { BlockItem(ModBlocks.OBELISK.get(), Item.Properties()) }
    val RETURN_PAD: RegistryObject<Item> = REGISTRY.register("return_pad") { BlockItem(ModBlocks.RETURN_PAD.get(), Item.Properties()) }
}
