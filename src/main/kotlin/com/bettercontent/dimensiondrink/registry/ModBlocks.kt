package com.bettercontent.dimensiondrink.registry

import com.bettercontent.dimensiondrink.MOD_ID
import com.bettercontent.dimensiondrink.content.ObeliskBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModBlocks {
    val REGISTRY: DeferredRegister<Block> = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)

    val OBELISK: RegistryObject<Block> = REGISTRY.register("dimensional_font") {
        ObeliskBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .strength(12.0F, 1200.0F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .lightLevel { 6 }
        )
    }

    val RETURN_FONT: RegistryObject<Block> = REGISTRY.register("return_seal") {
        ObeliskBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(50.0F, 1200.0F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .lightLevel { 6 },
            returnOnly = true
        )
    }
    val RETURN_PAD: RegistryObject<Block> = RETURN_FONT

}
