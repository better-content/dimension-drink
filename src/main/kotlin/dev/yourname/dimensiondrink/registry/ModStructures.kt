package dev.yourname.dimensiondrink.registry

import com.mojang.serialization.Codec
import dev.yourname.dimensiondrink.MOD_ID
import dev.yourname.dimensiondrink.worldgen.structure.DimensionalFontStructure
import dev.yourname.dimensiondrink.worldgen.structure.DimensionalFontStructurePiece
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject

object ModStructures {
    val STRUCTURE_TYPES: DeferredRegister<StructureType<*>> =
        DeferredRegister.create(Registries.STRUCTURE_TYPE, MOD_ID)
    val STRUCTURE_PIECES: DeferredRegister<StructurePieceType> =
        DeferredRegister.create(Registries.STRUCTURE_PIECE, MOD_ID)

    val DIMENSIONAL_FONT: RegistryObject<StructureType<DimensionalFontStructure>> =
        STRUCTURE_TYPES.register("dimensional_font") {
            object : StructureType<DimensionalFontStructure> {
                override fun codec(): Codec<DimensionalFontStructure> = DimensionalFontStructure.CODEC
            }
        }

    val DIMENSIONAL_FONT_PIECE: RegistryObject<StructurePieceType> =
        STRUCTURE_PIECES.register("dimensional_font") {
            StructurePieceType { context, tag -> DimensionalFontStructurePiece(context, tag) }
        }

    fun register(bus: net.minecraftforge.eventbus.api.IEventBus) {
        STRUCTURE_TYPES.register(bus)
        STRUCTURE_PIECES.register(bus)
    }
}
