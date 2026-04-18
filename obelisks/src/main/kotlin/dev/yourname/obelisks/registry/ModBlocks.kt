package dev.yourname.obelisks.registry

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.content.ObeliskBlock
import dev.yourname.obelisks.content.ReturnPadBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModBlocks {
    val REGISTRY: DeferredRegister<Block> = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)

    val OBELISK: RegistryObject<Block> = REGISTRY.register("obelisk") {
        ObeliskBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(50.0F, 1200.0F)
                .requiresCorrectToolForDrops()
                .lightLevel { 7 }
        )
    }

    val RETURN_PAD: RegistryObject<Block> = REGISTRY.register("return_pad") {
        ReturnPadBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(50.0F, 1200.0F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .lightLevel { 15 }
        )
    }
}
