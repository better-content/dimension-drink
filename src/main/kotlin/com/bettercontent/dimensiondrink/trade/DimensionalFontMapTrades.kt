package com.bettercontent.dimensiondrink.trade

import com.bettercontent.dimensiondrink.MOD_ID
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.data.ObeliskDefinition
import com.bettercontent.dimensiondrink.worldgen.structure.DimensionalFontStructurePiece
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.VillagerProfession
import net.minecraft.world.entity.npc.VillagerTrades
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.inventory.MerchantMenu
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
import net.minecraftforge.event.entity.player.TradeWithVillagerEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.registries.ForgeRegistries

object DimensionalFontMapTrades {
    private const val SOLD_TYPES_TAG = "dimension_drink:font_map_sold_types"
    private val noviceListing = DimensionalFontMapListing(2)

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onVillagerTrades(event: VillagerTradesEvent) {
        if (event.type == VillagerProfession.NONE || event.type == VillagerProfession.NITWIT) return
        event.trades[1].add(noviceListing)
    }

    @SubscribeEvent
    fun onTradeCompleted(event: TradeWithVillagerEvent) {
        val offer = event.merchantOffer
        val soldDefinitionId = offer.result.tag?.getString(DimensionalFontMapListing.DEFINITION_TAG)
            ?.takeIf(String::isNotBlank)
            ?: return
        val villager = event.abstractVillager as? Villager ?: return
        val level = villager.level() as? ServerLevel ?: return
        val eligibleTypes = DimensionalFontMapListing.enabledDefinitionIds()
        val soldTypes = advanceSoldTypes(readSoldTypes(villager.persistentData), soldDefinitionId, eligibleTypes)
        writeSoldTypes(villager.persistentData, soldTypes)

        val nextMap = noviceListing.nextMap(level, villager.blockPosition(), soldTypes) ?: return
        replaceOfferResult(offer, nextMap)

        val player = event.entity as? ServerPlayer ?: return
        val menu = player.containerMenu as? MerchantMenu ?: return
        player.connection.send(
            ClientboundMerchantOffersPacket(
                menu.containerId,
                villager.offers,
                villager.villagerData.level,
                villager.villagerXp,
                villager.showProgressBar(),
                villager.canRestock()
            )
        )
    }

    internal fun advanceSoldTypes(
        previous: Set<String>,
        soldDefinitionId: String,
        eligibleTypes: Set<String>
    ): Set<String> {
        if (eligibleTypes.isEmpty()) return emptySet()
        val updated = previous.filterTo(linkedSetOf()) { it in eligibleTypes }
        if (soldDefinitionId in eligibleTypes) updated += soldDefinitionId
        return if (updated.containsAll(eligibleTypes)) emptySet() else updated
    }

    internal fun replaceOfferResult(offer: MerchantOffer, nextMap: ItemStack) {
        offer.result.setTag(nextMap.tag?.copy())
        offer.result.count = nextMap.count
    }

    private fun readSoldTypes(tag: CompoundTag): Set<String> {
        val values = tag.getList(SOLD_TYPES_TAG, Tag.TAG_STRING.toInt())
        return (0 until values.size).mapTo(linkedSetOf(), values::getString)
    }

    private fun writeSoldTypes(tag: CompoundTag, soldTypes: Set<String>) {
        val values = ListTag()
        soldTypes.sorted().forEach { values.add(StringTag.valueOf(it)) }
        tag.put(SOLD_TYPES_TAG, values)
    }
}

class DimensionalFontMapListing(
    private val villagerXp: Int
) : VillagerTrades.ItemListing {
    override fun getOffer(trader: Entity, random: RandomSource): MerchantOffer? {
        val level = trader.level() as? ServerLevel ?: return null
        val map = nextMap(level, trader.blockPosition(), emptySet()) ?: return null
        return createOffer(map, villagerXp, currencyItem())
    }

    internal fun nextMap(level: ServerLevel, origin: BlockPos, excludedTypes: Set<String>): ItemStack? {
        val eligibleTypes = enabledDefinitionIds()
        if (eligibleTypes.isEmpty()) return null
        val attempts = maxOf(8, eligibleTypes.size * 8).coerceAtMost(32)
        val destination = selectCandidate(
            excludedTypes,
            attempts,
            { locateDestination(level, origin) },
            { piece -> ObeliskDataManager.getObelisk(piece.fontDefinitionId)?.id }
        ) ?: return null
        val definition = ObeliskDataManager.getObelisk(destination.fontDefinitionId) ?: return null
        return createMap(level, destination.fontCenter, definition)
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
            return createOffer(createMap(level, center, definition), villagerXp, currency)
        }

        internal fun createMap(
            level: ServerLevel,
            center: BlockPos,
            definition: ObeliskDefinition
        ): ItemStack {
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
            return map
        }

        internal fun createOffer(map: ItemStack, villagerXp: Int, currency: Item): MerchantOffer {
            return MerchantOffer(ItemStack(currency, COST), map, MAX_USES, villagerXp, 0.0f)
        }

        internal fun enabledDefinitionIds(): Set<String> = ObeliskDataManager.enabledDimensionDrinks()
            .filter { it.worldgenWeight > 0.0 }
            .mapTo(linkedSetOf(), ObeliskDefinition::id)

        internal fun <T> selectCandidate(
            excludedTypes: Set<String>,
            maxAttempts: Int,
            nextCandidate: () -> T?,
            definitionId: (T) -> String?
        ): T? {
            var fallback: T? = null
            repeat(maxAttempts) {
                val candidate = nextCandidate() ?: return fallback
                val candidateDefinitionId = definitionId(candidate) ?: return@repeat
                if (fallback == null) fallback = candidate
                if (candidateDefinitionId !in excludedTypes) return candidate
            }
            return fallback
        }

        internal fun currencyItem(): Item {
            val coin = ForgeRegistries.ITEMS.getValue(COPPER_COIN)
            return coin?.takeUnless { it == Items.AIR } ?: Items.EMERALD
        }
    }
}
