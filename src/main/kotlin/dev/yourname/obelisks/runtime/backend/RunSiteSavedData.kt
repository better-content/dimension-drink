package dev.yourname.obelisks.runtime.backend

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class RunSiteSavedData private constructor(
    private val records: LinkedHashMap<UUID, RunSiteRecord> = linkedMapOf()
) : SavedData() {

    fun snapshot(): List<RunSiteRecord> = records.values.map { it.deepCopy() }

    fun get(siteId: UUID): RunSiteRecord? = records[siteId]

    fun values(): Collection<RunSiteRecord> = records.values

    fun find(predicate: (RunSiteRecord) -> Boolean): RunSiteRecord? = records.values.firstOrNull(predicate)

    fun upsert(record: RunSiteRecord) {
        records[record.siteId] = record.deepCopy()
        setDirty()
    }

    fun remove(siteId: UUID): Boolean {
        val removed = records.remove(siteId) != null
        if (removed) {
            setDirty()
        }
        return removed
    }

    fun replaceAll(updatedRecords: Collection<RunSiteRecord>) {
        records.clear()
        updatedRecords.forEach { records[it.siteId] = it.deepCopy() }
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        records.values
            .sortedWith(compareBy<RunSiteRecord> { it.backendLevelKey.location().toString() }.thenBy { it.siteIndex })
            .forEach { list.add(it.toTag()) }
        tag.put("sites", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "dimensionalfonts_run_sites"

        fun get(server: MinecraftServer): RunSiteSavedData {
            return server.overworld().dataStorage.computeIfAbsent(::load, ::RunSiteSavedData, DATA_NAME)
        }

        fun load(tag: CompoundTag): RunSiteSavedData {
            val loaded = linkedMapOf<UUID, RunSiteRecord>()
            val list = tag.getList("sites", CompoundTag.TAG_COMPOUND.toInt())
            for (index in 0 until list.size) {
                val record = RunSiteRecord.fromTag(list.getCompound(index)) ?: continue
                loaded[record.siteId] = record
            }
            return RunSiteSavedData(loaded)
        }
    }
}

data class RunSiteRecord(
    val siteId: UUID,
    val templateId: String,
    val backendLevelKey: ResourceKey<Level>,
    var siteCenter: BlockPos,
    var siteBounds: SiteBounds,
    val siteIndex: Long,
    var originLevelKey: ResourceKey<Level>?,
    var originObeliskPos: BlockPos?,
    var state: SiteState,
    var runId: UUID? = null,
    var ownerId: UUID? = null,
    var spawnPos: BlockPos? = null,
    var createdGameTime: Long = 0L,
    var updatedGameTime: Long = createdGameTime,
    var cooldownUntilGameTime: Long = 0L,
    val touchedChunks: MutableSet<ChunkPos> = linkedSetOf()
) {
    fun preparedHandle(): PreparedSiteHandle = PreparedSiteHandle(
        siteId = siteId,
        templateId = templateId,
        backendLevelKey = backendLevelKey,
        siteCenter = siteCenter,
        siteBounds = siteBounds
    )

    fun activeHandle(): ActiveSiteHandle? {
        val activeRunId = runId ?: return null
        return ActiveSiteHandle(
            siteId = siteId,
            runId = activeRunId,
            templateId = templateId,
            backendLevelKey = backendLevelKey,
            siteCenter = siteCenter,
            siteBounds = siteBounds
        )
    }

    fun deepCopy(): RunSiteRecord = copy(
        touchedChunks = LinkedHashSet(touchedChunks)
    )

    fun toTag(): CompoundTag = CompoundTag().apply {
        putString("site_id", siteId.toString())
        putString("template_id", templateId)
        putString("backend_level_key", backendLevelKey.location().toString())
        put("site_center", encodeBlockPos(siteCenter))
        put("site_bounds", encodeBounds(siteBounds))
        putLong("site_index", siteIndex)
        originLevelKey?.location()?.toString()?.let { putString("origin_level_key", it) }
        originObeliskPos?.let { put("origin_obelisk_pos", encodeBlockPos(it)) }
        putString("state", state.name)
        runId?.let { putString("run_id", it.toString()) }
        ownerId?.let { putString("owner_id", it.toString()) }
        spawnPos?.let { put("spawn_pos", encodeBlockPos(it)) }
        putLong("created_game_time", createdGameTime)
        putLong("updated_game_time", updatedGameTime)
        putLong("cooldown_until_game_time", cooldownUntilGameTime)
        put("touched_chunks", encodeChunks(touchedChunks))
    }

    companion object {
        fun fromTag(tag: CompoundTag): RunSiteRecord? {
            val siteId = parseUuid(tag.getString("site_id")) ?: return null
            val templateId = tag.getString("template_id").takeIf { it.isNotBlank() } ?: return null
            val backendLevelKey = parseLevelKey(tag.getString("backend_level_key")) ?: return null
            val siteCenter = if (tag.contains("site_center")) decodeBlockPos(tag.getCompound("site_center")) else return null
            val siteBounds = if (tag.contains("site_bounds")) decodeBounds(tag.getCompound("site_bounds")) else return null
            return RunSiteRecord(
                siteId = siteId,
                templateId = templateId,
                backendLevelKey = backendLevelKey,
                siteCenter = siteCenter,
                siteBounds = siteBounds,
                siteIndex = tag.getLong("site_index"),
                originLevelKey = parseLevelKey(tag.getString("origin_level_key")),
                originObeliskPos = if (tag.contains("origin_obelisk_pos")) decodeBlockPos(tag.getCompound("origin_obelisk_pos")) else null,
                state = runCatching { SiteState.valueOf(tag.getString("state")) }.getOrDefault(SiteState.SCARRED),
                runId = parseUuid(tag.getString("run_id")),
                ownerId = parseUuid(tag.getString("owner_id")),
                spawnPos = if (tag.contains("spawn_pos")) decodeBlockPos(tag.getCompound("spawn_pos")) else null,
                createdGameTime = tag.getLong("created_game_time"),
                updatedGameTime = tag.getLong("updated_game_time"),
                cooldownUntilGameTime = tag.getLong("cooldown_until_game_time"),
                touchedChunks = decodeChunks(tag.getList("touched_chunks", CompoundTag.TAG_COMPOUND.toInt()))
            )
        }

        private fun encodeBlockPos(pos: BlockPos): CompoundTag = CompoundTag().apply {
            putInt("x", pos.x)
            putInt("y", pos.y)
            putInt("z", pos.z)
        }

        private fun decodeBlockPos(tag: CompoundTag): BlockPos = BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"))

        private fun encodeBounds(bounds: SiteBounds): CompoundTag = CompoundTag().apply {
            putInt("min_x", bounds.minX)
            putInt("min_y", bounds.minY)
            putInt("min_z", bounds.minZ)
            putInt("max_x", bounds.maxX)
            putInt("max_y", bounds.maxY)
            putInt("max_z", bounds.maxZ)
        }

        private fun decodeBounds(tag: CompoundTag): SiteBounds = SiteBounds(
            minX = tag.getInt("min_x"),
            minY = tag.getInt("min_y"),
            minZ = tag.getInt("min_z"),
            maxX = tag.getInt("max_x"),
            maxY = tag.getInt("max_y"),
            maxZ = tag.getInt("max_z")
        )

        private fun encodeChunks(chunks: Set<ChunkPos>): ListTag = ListTag().also { list ->
            chunks.forEach { chunk ->
                list.add(CompoundTag().apply {
                    putInt("x", chunk.x)
                    putInt("z", chunk.z)
                })
            }
        }

        private fun decodeChunks(list: ListTag): LinkedHashSet<ChunkPos> {
            val chunks = linkedSetOf<ChunkPos>()
            for (index in 0 until list.size) {
                val tag = list.getCompound(index)
                chunks += ChunkPos(tag.getInt("x"), tag.getInt("z"))
            }
            return chunks
        }

        private fun parseLevelKey(raw: String): ResourceKey<Level>? {
            if (raw.isBlank()) return null
            val location = runCatching { ResourceLocation(raw) }.getOrNull() ?: return null
            return ResourceKey.create(Registries.DIMENSION, location)
        }

        private fun parseUuid(raw: String): UUID? {
            if (raw.isBlank()) return null
            return runCatching { UUID.fromString(raw) }.getOrNull()
        }
    }
}

enum class SiteState {
    PREPARING,
    PREPARED,
    ACTIVE,
    SCARRED
}
