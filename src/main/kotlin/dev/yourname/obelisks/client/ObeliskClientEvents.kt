package dev.yourname.obelisks.client

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.registry.ModBlockEntities
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ObeliskClientEvents {
    @SubscribeEvent
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.OBELISK.get(), ::ObeliskBlockEntityRenderer)
    }
}
