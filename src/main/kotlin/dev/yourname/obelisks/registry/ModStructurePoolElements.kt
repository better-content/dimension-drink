package dev.yourname.obelisks.registry

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.worldgen.village.ChanceLegacySinglePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType

object ModStructurePoolElements {
    val CHANCE_LEGACY_SINGLE: StructurePoolElementType<ChanceLegacySinglePoolElement> =
        StructurePoolElementType.register("$MOD_ID:chance_legacy_single_pool_element", ChanceLegacySinglePoolElement.CODEC)

    fun bootstrap() = Unit
}
