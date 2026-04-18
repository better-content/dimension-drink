package dev.yourname.instanceddimensions.engine.instance

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

class InstanceSavedData private constructor(
    private val records: LinkedHashMap<java.util.UUID, InstanceRecord> = linkedMapOf()
) : SavedData() {

    fun replaceAll(updatedRecords: Collection<InstanceRecord>) {
        records.clear()
        updatedRecords.forEach { records[it.id] = it.deepCopy() }
        setDirty()
    }

    fun snapshot(): Collection<InstanceRecord> = records.values.map { it.deepCopy() }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        records.values
            .sortedBy { it.createdGameTime }
            .forEach { list.add(it.toTag()) }
        tag.put("instances", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "instanced_dimensions_instances"

        fun get(server: MinecraftServer): InstanceSavedData {
            return server.overworld().dataStorage.computeIfAbsent(::load, ::InstanceSavedData, DATA_NAME)
        }

        fun load(tag: CompoundTag): InstanceSavedData {
            val loaded = linkedMapOf<java.util.UUID, InstanceRecord>()
            val list = tag.getList("instances", CompoundTag.TAG_COMPOUND.toInt())
            for (index in 0 until list.size) {
                val record = InstanceRecord.fromTag(list.getCompound(index)) ?: continue
                loaded[record.id] = record
            }
            return InstanceSavedData(loaded)
        }
    }
}
