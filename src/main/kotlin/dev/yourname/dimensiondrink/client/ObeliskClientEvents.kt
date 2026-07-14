package dev.yourname.dimensiondrink.client

import dev.yourname.dimensiondrink.MOD_ID
import dev.yourname.dimensiondrink.registry.ModBlockEntities
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ObeliskClientEvents {
    @SubscribeEvent
    @JvmStatic
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.OBELISK.get(), ::ObeliskBlockEntityRenderer)
    }
}
