package com.bettercontent.dimensiondrink.trade

import com.bettercontent.dimensiondrink.MOD_ID
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.data.ObeliskDefinition
import com.bettercontent.dimensiondrink.worldgen.structure.DimensionalFontStructurePiece
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.VillagerProfession
import net.minecraft.world.entity.npc.VillagerTrades
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraftforge.event.village.VillagerTradesEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.registries.ForgeRegistries

object DimensionalFontMapTrades {
    private val listingsByLevel = mapOf(
        2 to DimensionalFontMapListing(6),
        3 to DimensionalFontMapListing(10)
    )

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onVillagerTrades(event: VillagerTradesEvent) {
        if (event.type == VillagerProfession.NONE || event.type == VillagerProfession.NITWIT) return
        listingsByLevel.forEach { (level, listing) ->
            event.trades[level].add(listing)
        }
    }
}

class DimensionalFontMapListing(
    private val villagerXp: Int
) : VillagerTrades.ItemListing {
    override fun getOffer(trader: Entity, random: RandomSource): MerchantOffer? {
        val level = trader.level() as? ServerLevel ?: return null
        val destination = locateDestination(level, trader.blockPosition()) ?: return null
        val definition = ObeliskDataManager.getObelisk(destination.fontDefinitionId) ?: return null
        return createOffer(level, destination.fontCenter, definition, villagerXp, currencyItem())
    }

    private fun locateDestination(level: ServerLevel, origin: BlockPos): DimensionalFontStructurePiece? {
        val located = level.findNearestMapStructure(FONT_MAP_STRUCTURES, origin, SEARCH_RADIUS_CHUNKS, true)
            ?: return null
        val chunkPos = ChunkPos(located)
        val chunk = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS)
        val structure = level.registryAccess()
            .registryOrThrow(Registries.STRUCTURE)
            .get(DIMENSIONAL_FONT_STRUCTURE)
            ?: return null
        val start = level.structureManager().getStartForStructure(SectionPos.bottomOf(chunk), structure, chunk)
            ?: return null
        return start.pieces.filterIsInstance<DimensionalFontStructurePiece>().firstOrNull()
    }

    companion object {
        const val DEFINITION_TAG = "dimension_drink:font_definition_id"
        const val COST = 8
        const val MAX_USES = 8

        private const val SEARCH_RADIUS_CHUNKS = 100
        private val DIMENSIONAL_FONT_STRUCTURE = ResourceLocation(MOD_ID, "dimensional_font")
        private val COPPER_COIN = ResourceLocation("createdeco", "copper_coin")
        private val FONT_MAP_STRUCTURES: TagKey<Structure> = TagKey.create(
            Registries.STRUCTURE,
            ResourceLocation(MOD_ID, "on_dimensional_font_maps")
        )

        internal fun createOffer(
            level: ServerLevel,
            center: BlockPos,
            definition: ObeliskDefinition,
            villagerXp: Int,
            currency: Item
        ): MerchantOffer {
            val map = MapItem.create(level, center.x, center.z, 2.toByte(), true, true)
            MapItem.renderBiomePreviewMap(level, map)
            MapItemSavedData.addTargetDecoration(map, center, "+", MapDecoration.Type.TARGET_X)

            val displayName = Component.literal(definition.displayName)
            map.setHoverName(Component.translatable("item.dimension_drink.dimensional_font_map", displayName))
            val lore = ListTag()
            lore.add(
                StringTag.valueOf(
                    Component.Serializer.toJson(
                        Component.translatable("item.dimension_drink.dimensional_font_map.destination", displayName)
                    )
                )
            )
            map.getOrCreateTagElement("display").put("Lore", lore)
            map.orCreateTag.putString(DEFINITION_TAG, definition.id)

            return MerchantOffer(ItemStack(currency, COST), map, MAX_USES, villagerXp, 0.0f)
        }

        internal fun currencyItem(): Item {
            val coin = ForgeRegistries.ITEMS.getValue(COPPER_COIN)
            return coin?.takeUnless { it == Items.AIR } ?: Items.EMERALD
        }
    }
}
