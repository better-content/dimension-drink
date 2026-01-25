package dev.yourname.obelisks.client

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.registry.ModBlockEntities
import dev.yourname.obelisks.registry.ModMenuTypes
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent

/**
 * Client-side only setup for the Obelisks mod.
 * This class is only loaded on the client distribution.
 */
@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ClientSetup {

    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            // Register screen for the Obelisk menu
            MenuScreens.register(
                ModMenuTypes.OBELISK_MENU.get()
            ) { menu, inventory, title ->
                ObeliskScreen(menu, inventory, title)
            }

            // Register block entity renderer for beam rendering
            BlockEntityRenderers.register(
                ModBlockEntities.OBELISK.get()
            ) { context ->
                ObeliskBlockEntityRenderer(context)
            }
        }
    }
}
