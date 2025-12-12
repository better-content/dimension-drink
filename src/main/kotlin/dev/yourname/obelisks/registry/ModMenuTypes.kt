package dev.yourname.obelisks.registry

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskMenu
import net.minecraft.world.inventory.MenuType
import net.minecraftforge.common.extensions.IForgeMenuType
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModMenuTypes {
    val MENU_TYPES: DeferredRegister<MenuType<*>> = DeferredRegister.create(
        ForgeRegistries.MENU_TYPES,
        ObelisksConstants.MOD_ID
    )

    val OBELISK_MENU: RegistryObject<MenuType<ObeliskMenu>> = MENU_TYPES.register("obelisk_menu") {
        IForgeMenuType.create(ObeliskMenu::create)
    }
}
