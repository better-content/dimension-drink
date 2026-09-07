package com.bettercontent.dimensiondrink.trade

import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData

data class FontLocation(
    val level: ResourceKey<Level>,
    val pos: BlockPos,
    val definitionId: String
)

/** New-world discovery index. Queries never request or generate a chunk. */
class FontLocationSavedData private constructor(
    private val locations: LinkedHashMap<String, FontLocation> = linkedMapOf(),
    private val mapsSold: LinkedHashMap<String, Long> = linkedMapOf()
) : SavedData() {
    fun snapshot(): List<FontLocation> = locations.values.toList()

    fun salesSnapshot(): Map<String, Long> = mapsSold.toSortedMap()

    fun register(level: ServerLevel, pos: BlockPos, definitionId: String) {
        val location = FontLocation(level.dimension(), pos.immutable(), definitionId)
        if (locations.put(key(location.level, location.pos), location) != location) setDirty()
    }

    fun unregister(level: ResourceKey<Level>, pos: BlockPos): Boolean {
        val removed = locations.remove(key(level, pos)) != null
        if (removed) setDirty()
        return removed
    }

    fun recordMapSale(definitionId: String) {
        mapsSold[definitionId] = (mapsSold[definitionId] ?: 0L) + 1L
        setDirty()
    }

    fun nearest(
        level: ServerLevel,
        origin: BlockPos,
        eligibleDefinitionIds: Set<String>,
        excludedDefinitionIds: Set<String>
    ): FontLocation? {
        val staleKeys = mutableListOf<String>()
        val candidates = locations.asSequence()
            .filter { (_, location) -> location.level == level.dimension() }
            .filter { (_, location) -> location.definitionId in eligibleDefinitionIds }
            .filter { (_, location) -> location.definitionId !in excludedDefinitionIds }
            .filter { (entryKey, location) ->
                if (!level.hasChunkAt(location.pos)) return@filter true
                val obelisk = level.getBlockEntity(location.pos) as? ObeliskBlockEntity
                val valid = obelisk?.isNaturallyGenerated == true && obelisk.definitionId == location.definitionId
                if (!valid) staleKeys += entryKey
                valid
            }
            .map(Map.Entry<String, FontLocation>::value)
            .toList()
        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach(locations::remove)
            setDirty()
        }
        return selectNearest(candidates, origin, FontLocation::pos, FontLocation::definitionId)
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", SCHEMA_VERSION)
        val locationList = ListTag()
        locations.values.sortedWith(compareBy<FontLocation> { it.level.location().toString() }.thenBy { it.pos.asLong() })
            .forEach { location ->
                locationList.add(CompoundTag().apply {
                    putString("level", location.level.location().toString())
                    putLong("pos", location.pos.asLong())
                    putString("definition", location.definitionId)
                })
            }
        tag.put("locations", locationList)
        val sales = CompoundTag()
        mapsSold.toSortedMap().forEach { (definition, count) -> sales.putLong(definition, count) }
        tag.put("maps_sold", sales)
        return tag
    }

    companion object {
        private const val DATA_NAME = "dimension_drink_font_locations"
        const val SCHEMA_VERSION = 1

        fun get(server: MinecraftServer): FontLocationSavedData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::FontLocationSavedData, DATA_NAME)

        fun load(tag: CompoundTag): FontLocationSavedData {
            val schema = if (tag.contains("schema", Tag.TAG_INT.toInt())) tag.getInt("schema") else 0
            require(schema == SCHEMA_VERSION) {
                "Unsupported Dimension Drink font-location schema $schema; expected $SCHEMA_VERSION"
            }
            val locations = linkedMapOf<String, FontLocation>()
            val list = tag.getList("locations", Tag.TAG_COMPOUND.toInt())
            for (index in 0 until list.size) {
                val entry = list.getCompound(index)
                val levelId = ResourceLocation.tryParse(entry.getString("level")) ?: continue
                val definition = entry.getString("definition").takeIf(String::isNotBlank) ?: continue
                val level = ResourceKey.create(Registries.DIMENSION, levelId)
                val location = FontLocation(level, BlockPos.of(entry.getLong("pos")), definition)
                locations[key(level, location.pos)] = location
            }
            val sales = linkedMapOf<String, Long>()
            val salesTag = tag.getCompound("maps_sold")
            salesTag.allKeys.sorted().forEach { definition ->
                sales[definition] = salesTag.getLong(definition).coerceAtLeast(0L)
            }
            return FontLocationSavedData(locations, sales)
        }

        internal fun <T> selectNearest(
            candidates: Iterable<T>,
            origin: BlockPos,
            position: (T) -> BlockPos,
            definitionId: (T) -> String
        ): T? =
            candidates.minWithOrNull(
                compareBy<T> { position(it).distSqr(origin) }
                    .thenBy(definitionId)
                    .thenBy { position(it).asLong() }
            )

        private fun key(level: ResourceKey<Level>, pos: BlockPos): String = "${level.location()}@${pos.asLong()}"
    }
}
