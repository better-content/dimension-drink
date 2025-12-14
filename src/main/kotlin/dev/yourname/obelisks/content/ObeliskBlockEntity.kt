package dev.yourname.obelisks.content

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.registry.ModBlockEntities
import dev.yourname.obelisks.jaunt.FERegenerationHandler
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import java.util.*
import kotlin.random.Random

class ObeliskBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.OBELISK.get(), pos, state) {

    // Modifiers: 5 modifiers per obelisk, each affecting one FE stat (0-50% bonus, repetition allowed)
    // Must be initialized first since other properties depend on it
    var modifiers: List<ObeliskModifier> = ObeliskModifier.generateModifiers()
        private set

    var feStored: Int = getModifiedMaxStorage()
        private set
    var targetDimensionId: String? = null // Now stores dimension ID directly (e.g., "minecraft:the_nether")
    var dimensionDisplayName: String? = null // Display name for the dimension (from config)

    // New fields for temporary dimension system
    var obeliskId: UUID = UUID.randomUUID()
        private set
    var activeRunId: Long? = null
    var cooldownEndTime: Long = 0 // Server time when cooldown ends (0 = no cooldown)

    init {
        // Register for FE regeneration tracking
        FERegenerationHandler.registerObelisk(this)

        // Debug logging for modifiers
    }

    fun isRunActive(): Boolean = activeRunId != null

    fun isOnCooldown(): Boolean {
        if (cooldownEndTime == 0L) return false
        val currentTime = System.currentTimeMillis()
        return currentTime < cooldownEndTime
    }

    fun getCooldownRemainingSeconds(): Int {
        if (!isOnCooldown()) return 0
        val remaining = cooldownEndTime - System.currentTimeMillis()
        return (remaining / 1000).toInt().coerceAtLeast(0)
    }

    fun startCooldown(durationTicks: Int) {
        cooldownEndTime = System.currentTimeMillis() + (durationTicks * 50) // 50ms per tick
        setChanged()
    }

    // ===== Modifier System =====

    /**
     * Gets the modified maximum FE storage based on MAX_STORAGE modifiers.
     */
    fun getModifiedMaxStorage(): Int {
        var max = ObelisksConstants.MAX_FE_STORAGE
        modifiers.filter { it.stat == FEStat.MAX_STORAGE }.forEach { modifier ->
            max = modifier.applyTo(max)
        }
        return max
    }

    /**
     * Gets the modified FE regeneration rate based on REGEN_RATE modifiers.
     * Calculates using Double to preserve decimal values, rounds up.
     */
    fun getModifiedRegenRate(): Int {
        var regen = ObelisksConstants.FE_REGEN_PER_TICK.toDouble()
        modifiers.filter { it.stat == FEStat.REGEN_RATE }.forEach { modifier ->
            regen = modifier.applyTo(regen)
        }
        return kotlin.math.ceil(regen).toInt().coerceAtLeast(1)
    }

    /**
     * Gets the modified base FE drain per tick based on BASE_DRAIN modifiers.
     * Calculates using Double to preserve decimal values, rounds up.
     */
    fun getModifiedBaseDrain(): Int {
        var drain = ObelisksConstants.BASE_FE_DRAIN_PER_TICK.toDouble()
        modifiers.filter { it.stat == FEStat.BASE_DRAIN }.forEach { modifier ->
            drain = modifier.applyTo(drain)
        }
        return kotlin.math.ceil(drain).toInt().coerceAtLeast(1)
    }

    /**
     * Gets the modified per-player FE drain based on PLAYER_DRAIN modifiers.
     * Calculates using Double to preserve decimal values, rounds up.
     */
    fun getModifiedPlayerDrain(): Int {
        var drain = ObelisksConstants.PER_PLAYER_FE_DRAIN.toDouble()
        modifiers.filter { it.stat == FEStat.PLAYER_DRAIN }.forEach { modifier ->
            drain = modifier.applyTo(drain)
        }
        return kotlin.math.ceil(drain).toInt().coerceAtLeast(2)
    }

    /**
     * Gets the modified exponential drain factor based on DRAIN_FACTOR modifiers.
     */
    fun getModifiedDrainFactor(): Double {
        var factor = ObelisksConstants.DRAIN_EXPONENTIAL_FACTOR
        modifiers.filter { it.stat == FEStat.DRAIN_FACTOR }.forEach { modifier ->
            factor = modifier.applyTo(factor)
        }
        return factor
    }

    // ===== Phase 3: FE Energy Storage =====

    private val energyStorage = object : IEnergyStorage {
        override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
            if (maxReceive <= 0) return 0
            val maxStorage = getModifiedMaxStorage()
            val energyReceived = minOf(maxStorage - feStored, maxReceive)
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

        override fun getMaxEnergyStored(): Int = getModifiedMaxStorage()

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
    fun getMaxEnergyStored(): Int = getModifiedMaxStorage()

    /**
     * Gets the FE as a percentage (0.0 to 1.0).
     */
    fun getEnergyPercent(): Double = feStored.toDouble() / getModifiedMaxStorage().toDouble()

    /**
     * Regenerates FE naturally when idle (no active run).
     * Returns the amount of FE actually regenerated.
     */
    fun regenerateEnergy(amount: Int): Int {
        val maxStorage = getModifiedMaxStorage()
        if (isRunActive() || feStored >= maxStorage) {
            return 0
        }
        val actualRegen = minOf(amount, maxStorage - feStored)
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

    override fun setRemoved() {
        super.setRemoved()
        // Unregister from FE regeneration tracking
        FERegenerationHandler.unregisterObelisk(this)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt("FeStored", feStored)
        targetDimensionId?.let {
            tag.putString("TargetDimension", it)
        }
        dimensionDisplayName?.let {
            tag.putString("DimensionDisplayName", it)
        }

        // Save new fields
        tag.putUUID("ObeliskId", obeliskId)
        activeRunId?.let { tag.putLong("ActiveRunId", it) }
        tag.putLong("CooldownEndTime", cooldownEndTime)

        // Save modifiers
        tag.putInt("ModifierCount", modifiers.size)
        modifiers.forEachIndexed { index, modifier ->
            val modTag = CompoundTag()
            modifier.saveToNBT(modTag)
            tag.put("Modifier$index", modTag)
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)

        // Load new fields
        if (tag.contains("ObeliskId")) {
            obeliskId = tag.getUUID("ObeliskId")
        }
        activeRunId = if (tag.contains("ActiveRunId")) {
            tag.getLong("ActiveRunId")
        } else null

        cooldownEndTime = if (tag.contains("CooldownEndTime")) {
            tag.getLong("CooldownEndTime")
        } else 0L

        // Load modifiers (must load before feStored since feStored initialization uses getModifiedMaxStorage)
        if (tag.contains("ModifierCount")) {
            val count = tag.getInt("ModifierCount")
            val loadedModifiers = mutableListOf<ObeliskModifier>()
            for (i in 0 until count) {
                if (tag.contains("Modifier$i")) {
                    val modTag = tag.getCompound("Modifier$i")
                    loadedModifiers.add(ObeliskModifier.loadFromNBT(modTag))
                }
            }
            modifiers = loadedModifiers
        } else {
            // If no modifiers saved, generate new ones (backwards compatibility)
            modifiers = ObeliskModifier.generateModifiers()
        }

        feStored = tag.getInt("FeStored")
        targetDimensionId =
            if (tag.contains("TargetDimension")) tag.getString("TargetDimension")
            else null
        dimensionDisplayName =
            if (tag.contains("DimensionDisplayName")) tag.getString("DimensionDisplayName")
            else {
                // Migration: backfill display name from config for old obelisks
                targetDimensionId?.let { dimId ->
                    dev.yourname.obelisks.config.ConfigManager.getDimensionConfig(dimId)?.dimensionName
                }
            }
    }

    // Client sync methods
    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        saveAdditional(tag)
        return tag
    }

    override fun getUpdatePacket(): net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this)
    }

    /**
     * Helper to mark changed and sync to clients.
     * Call this instead of setChanged() when you want clients to be notified.
     */
    fun syncToClients() {
        setChanged()
        if (level != null && !level!!.isClientSide) {
            level!!.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }
}
