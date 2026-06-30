package dev.yourname.obelisks.registry

import dev.yourname.obelisks.MOD_ID
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModItems {
    val REGISTRY: DeferredRegister<Item> = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)

    val OBELISK: RegistryObject<Item> = REGISTRY.register("dimensional_font") { BlockItem(ModBlocks.OBELISK.get(), Item.Properties()) }
    val RETURN_FONT: RegistryObject<Item> = REGISTRY.register("return_seal") { BlockItem(ModBlocks.RETURN_FONT.get(), Item.Properties()) }
    val RETURN_PAD: RegistryObject<Item> = RETURN_FONT
    val GRAVE_SOIL: RegistryObject<Item> = REGISTRY.register("grave_soil") { BlockItem(ModBlocks.GRAVE_SOIL.get(), Item.Properties()) }
}
