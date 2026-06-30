package dev.yourname.obelisks.registry

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.content.GraveSoilBlock
import dev.yourname.obelisks.content.ObeliskBlock
import dev.yourname.obelisks.content.ReturnPadBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
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

    val RETURN_PAD: RegistryObject<Block> = REGISTRY.register("return_seal") {
        ReturnPadBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(50.0F, 1200.0F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .lightLevel { 15 }
        )
    }

    val GRAVE_SOIL: RegistryObject<Block> = REGISTRY.register("grave_soil") {
        GraveSoilBlock(
            BlockBehaviour.Properties.copy(Blocks.MUD)
                .lightLevel { state -> if (state.getValue(GraveSoilBlock.CHARGING)) 2 else 0 }
        )
    }
}
