package dev.yourname.obelisks.runtime.run

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class RunSavedData private constructor(
    private val records: LinkedHashMap<UUID, RunRecord> = linkedMapOf()
) : SavedData() {

    fun replaceAll(updatedRecords: Collection<RunRecord>) {
        records.clear()
        updatedRecords.forEach { records[it.id] = it.deepCopy() }
        setDirty()
    }

    fun snapshot(): Collection<RunRecord> = records.values.map { it.deepCopy() }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        records.values
            .sortedBy { it.createdGameTime }
            .forEach { list.add(it.toTag()) }
        tag.put("runs", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "instanced_dimensions_runs"

        fun get(server: MinecraftServer): RunSavedData {
            return server.overworld().dataStorage.computeIfAbsent(::load, ::RunSavedData, DATA_NAME)
        }

        fun load(tag: CompoundTag): RunSavedData {
            val loaded = linkedMapOf<UUID, RunRecord>()
            val list = tag.getList("runs", CompoundTag.TAG_COMPOUND.toInt())
            for (index in 0 until list.size) {
                val record = RunRecord.fromTag(list.getCompound(index)) ?: continue
                loaded[record.id] = record
            }
            return RunSavedData(loaded)
        }
    }
}
