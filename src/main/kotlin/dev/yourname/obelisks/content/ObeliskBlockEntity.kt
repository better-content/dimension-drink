package dev.yourname.obelisks.content

import dev.yourname.obelisks.config.ObelisksConfig
import dev.yourname.obelisks.dimension.DimensionBaseType
import dev.yourname.obelisks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import java.util.*

class ObeliskBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.OBELISK.get(), pos, state) {

    var feStored: Int = ObelisksConfig.MAX_FE_STORAGE
        private set
    var targetDimensionId: ResourceLocation? = null

    // New fields for temporary dimension system
    var obeliskId: UUID = UUID.randomUUID()
        private set
    var baseType: DimensionBaseType? = null
    var activeRunId: Long? = null

    fun isRunActive(): Boolean = activeRunId != null

    // ===== Phase 3: FE Energy Storage =====

    private val energyStorage = object : IEnergyStorage {
        override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
            if (maxReceive <= 0) return 0
            val energyReceived = minOf(ObelisksConfig.MAX_FE_STORAGE - feStored, maxReceive)
            if (!simulate && energyReceived > 0) {
                feStored += energyReceived
                setChanged()
            }
            return energyReceived
        }

        override fun extractEnergy(maxExtract: Int, simulate: Boolean): Int {
            if (maxExtract <= 0) return 0
            val energyExtracted = minOf(feStored, maxExtract)
            if (!simulate && energyExtracted > 0) {
                feStored -= energyExtracted
                setChanged()
            }
            return energyExtracted
        }

        override fun getEnergyStored(): Int = feStored

        override fun getMaxEnergyStored(): Int = ObelisksConfig.MAX_FE_STORAGE

        override fun canExtract(): Boolean = true

        override fun canReceive(): Boolean = true
    }

    private val energyCapability: LazyOptional<IEnergyStorage> = LazyOptional.of { energyStorage }

    /**
     * Drains FE from the obelisk. Returns true if successful, false if not enough energy.
     */
    fun drainEnergy(amount: Int): Boolean {
        return if (feStored >= amount) {
            feStored -= amount
            setChanged()
            true
        } else {
            false
        }
    }

    /**
     * Gets the current FE stored.
     */
    fun getEnergyStored(): Int = feStored

    /**
     * Gets the maximum FE capacity.
     */
    fun getMaxEnergyStored(): Int = ObelisksConfig.MAX_FE_STORAGE

    /**
     * Gets the FE as a percentage (0.0 to 1.0).
     */
    fun getEnergyPercent(): Double = feStored.toDouble() / ObelisksConfig.MAX_FE_STORAGE.toDouble()

    /**
     * Regenerates FE naturally when idle (no active run).
     * Returns the amount of FE actually regenerated.
     */
    fun regenerateEnergy(amount: Int): Int {
        if (isRunActive() || feStored >= ObelisksConfig.MAX_FE_STORAGE) {
            return 0
        }
        val actualRegen = minOf(amount, ObelisksConfig.MAX_FE_STORAGE - feStored)
        if (actualRegen > 0) {
            feStored += actualRegen
            setChanged()
        }
        return actualRegen
    }

    override fun <T> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ENERGY) {
            return energyCapability.cast()
        }
        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        energyCapability.invalidate()
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt("FeStored", feStored)
        targetDimensionId?.let {
            tag.putString("TargetDimension", it.toString())
        }

        // Save new fields
        tag.putUUID("ObeliskId", obeliskId)
        baseType?.let { tag.putString("BaseType", it.name) }
        activeRunId?.let { tag.putLong("ActiveRunId", it) }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        feStored = tag.getInt("FeStored")
        targetDimensionId =
            if (tag.contains("TargetDimension")) ResourceLocation(tag.getString("TargetDimension"))
            else null

        // Load new fields
        if (tag.contains("ObeliskId")) {
            obeliskId = tag.getUUID("ObeliskId")
        }
        baseType = if (tag.contains("BaseType")) {
            DimensionBaseType.valueOf(tag.getString("BaseType"))
        } else null
        activeRunId = if (tag.contains("ActiveRunId")) {
            tag.getLong("ActiveRunId")
        } else null
    }
}
