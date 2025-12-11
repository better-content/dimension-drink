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

/**
 * Registers all block types provided by the Obelisks mod.
 */
object ModBlocks {
    val REGISTRY: DeferredRegister<Block> =
        DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)

    /**
     * The primary interactive obelisk block. Acts as the entry to run instances.
     */
    val OBELISK: RegistryObject<Block> = REGISTRY.register("obelisk") {
        ObeliskBlock(
            BlockBehaviour.Properties
                .of()
                .mapColor(MapColor.STONE)
                .strength(3.0f, 6.0f)
                .requiresCorrectToolForDrops()
        )
    }

    /**
     * The return pad block. Teleports players back to their origin obelisk when used.
     */
    val RETURN_PAD: RegistryObject<Block> = REGISTRY.register("return_pad") {
        ReturnPadBlock(
            BlockBehaviour.Properties
                .of()
                .mapColor(MapColor.METAL)
                .strength(3.0f, 6.0f)
                .requiresCorrectToolForDrops()
                .noOcclusion()
        )
    }
}
