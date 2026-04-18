package dev.yourname.obelisks.content

import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.registry.ModBlockEntities
import dev.yourname.obelisks.runtime.energy.FERegenerationHandler
import dev.yourname.obelisks.runtime.ObeliskRuntimeService
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemStackHandler
import java.util.UUID
import kotlin.random.Random

class ObeliskBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.OBELISK.get(), pos, state) {

    var modifiers: List<ObeliskModifier> = ObeliskModifier.generateModifiers()
        private set

    var obeliskId: UUID = UUID.randomUUID()
        private set

    var definitionId: String = ObeliskConstants.DEFAULT_TEMPLATES.random()
        private set

    val targetTemplateId: String
        get() = ObeliskDataManager.getObelisk(definitionId)?.instanceTemplateId ?: definitionId

    var activeRunId: UUID? = null
    var cooldownUntilGameTime: Long = 0L
    var beamVisible: Boolean = true
    var beamColorRed: Int = Random.nextInt(256)
        private set
    var beamColorGreen: Int = Random.nextInt(256)
        private set
    var beamColorBlue: Int = Random.nextInt(256)
        private set

    private val sidedOutputCap: Int = 8 + Random.nextInt(25)
    private val itemHandler = object : ItemStackHandler(9) {
        override fun onContentsChanged(slot: Int) {
            setChanged()
            syncToClients()
        }
    }
    private val limitedItemHandler = object : IItemHandler {
        override fun getSlots(): Int = itemHandler.slots
        override fun getStackInSlot(slot: Int): ItemStack = itemHandler.getStackInSlot(slot)
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack = itemHandler.insertItem(slot, stack, simulate)
        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack = itemHandler.extractItem(slot, amount.coerceAtMost(sidedOutputCap), simulate)
        override fun getSlotLimit(slot: Int): Int = itemHandler.getSlotLimit(slot)
        override fun isItemValid(slot: Int, stack: ItemStack): Boolean = itemHandler.isItemValid(slot, stack)
    }

    var feStored: Int = getModifiedMaxStorage()
        private set

    private val energyStorage = object : IEnergyStorage {
        override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
            if (maxReceive <= 0) return 0
            val accepted = minOf(getModifiedMaxStorage() - feStored, maxReceive)
            if (!simulate && accepted > 0) {
                feStored += accepted
                setChanged()
                syncToClients()
            }
            return accepted
        }

        override fun extractEnergy(maxExtract: Int, simulate: Boolean): Int {
            if (maxExtract <= 0) return 0
            val extracted = minOf(feStored, maxExtract)
            if (!simulate && extracted > 0) {
                feStored -= extracted
                setChanged()
                syncToClients()
            }
            return extracted
        }

        override fun getEnergyStored(): Int = feStored
        override fun getMaxEnergyStored(): Int = getModifiedMaxStorage()
        override fun canExtract(): Boolean = true
        override fun canReceive(): Boolean = true
    }

    private val energyCapability: LazyOptional<IEnergyStorage> = LazyOptional.of { energyStorage }
    private val itemCapability: LazyOptional<IItemHandler> = LazyOptional.of { limitedItemHandler }

    init {
        FERegenerationHandler.registerObelisk(this)
    }

    fun isRunActive(): Boolean = activeRunId != null

    fun isOnCooldown(): Boolean {
        val currentLevel = level ?: return false
        return cooldownUntilGameTime > currentLevel.gameTime
    }

    fun getCooldownRemainingTicks(): Long {
        val currentLevel = level ?: return 0L
        return (cooldownUntilGameTime - currentLevel.gameTime).coerceAtLeast(0L)
    }

    fun startCooldown(durationTicks: Long = ObeliskConstants.COOLDOWN_TICKS) {
        val currentLevel = level ?: return
        cooldownUntilGameTime = currentLevel.gameTime + durationTicks
        setChanged()
        syncToClients()
    }

    fun setTargetTemplate(templateId: String) {
        definitionId = templateId
        setChanged()
        syncToClients()
    }

    fun setDefinition(definitionId: String) {
        this.definitionId = definitionId
        setChanged()
        syncToClients()
    }

    fun setEnergyStoredForDebug(amount: Int) {
        feStored = amount.coerceIn(0, getModifiedMaxStorage())
        setChanged()
        syncToClients()
    }

    fun fillToCapacity() {
        setEnergyStoredForDebug(getModifiedMaxStorage())
    }

    fun cycleTemplate() {
        val templates = ObeliskConstants.DEFAULT_TEMPLATES
        val currentIndex = templates.indexOf(definitionId).takeIf { it >= 0 } ?: 0
        definitionId = templates[(currentIndex + 1) % templates.size]
        setChanged()
        syncToClients()
    }

    fun setActiveRun(runId: UUID?) {
        activeRunId = runId
        setChanged()
        syncToClients()
    }

    fun getModifiedMaxStorage(): Int {
        var max = ObeliskConstants.MAX_FE_STORAGE
        modifiers.filter { it.stat == FEStat.MAX_STORAGE }.forEach { max = it.applyTo(max) }
        return max
    }

    fun getModifiedRegenRate(): Int {
        var rate = ObeliskConstants.FE_REGEN_PER_TICK.toDouble()
        modifiers.filter { it.stat == FEStat.REGEN_RATE }.forEach { rate = it.applyTo(rate) }
        return kotlin.math.ceil(rate).toInt().coerceAtLeast(1)
    }

    fun getModifiedBaseDrain(): Int {
        var drain = ObeliskConstants.BASE_FE_DRAIN_PER_TICK.toDouble()
        modifiers.filter { it.stat == FEStat.BASE_DRAIN }.forEach { drain = it.applyTo(drain) }
        return kotlin.math.ceil(drain).toInt().coerceAtLeast(1)
    }

    fun getModifiedPlayerDrain(): Int {
        var drain = ObeliskConstants.PER_PLAYER_FE_DRAIN.toDouble()
        modifiers.filter { it.stat == FEStat.PLAYER_DRAIN }.forEach { drain = it.applyTo(drain) }
        return kotlin.math.ceil(drain).toInt().coerceAtLeast(1)
    }

    fun getModifiedDrainFactor(): Double {
        var factor = ObeliskConstants.DRAIN_EXPONENTIAL_FACTOR
        modifiers.filter { it.stat == FEStat.DRAIN_FACTOR }.forEach { factor = it.applyTo(factor) }
        return factor
    }

    fun getEnergyPercent(): Double = feStored.toDouble() / getModifiedMaxStorage().toDouble()
    fun getEnergyStored(): Int = feStored
    fun getMaxEnergyStored(): Int = getModifiedMaxStorage()
    fun getBeamColorFloats(): FloatArray = floatArrayOf(
        beamColorRed / 255.0f,
        beamColorGreen / 255.0f,
        beamColorBlue / 255.0f
    )

    fun drainEnergy(amount: Int): Boolean {
        if (amount <= 0) return true
        if (feStored < amount) return false
        feStored -= amount
        setChanged()
        syncToClients()
        return true
    }

    fun regenerateEnergy(amount: Int): Int {
        if (amount <= 0 || isRunActive()) return 0
        val maxStorage = getModifiedMaxStorage()
        if (feStored >= maxStorage) return 0
        val actual = minOf(amount, maxStorage - feStored)
        feStored += actual
        setChanged()
        syncToClients()
        return actual
    }

    fun restoreRunEnergy(amount: Int): Int {
        if (amount <= 0) return 0
        val maxStorage = getModifiedMaxStorage()
        if (feStored >= maxStorage) return 0
        val actual = minOf(amount, maxStorage - feStored)
        feStored += actual
        setChanged()
        syncToClients()
        return actual
    }

    fun getInternalItemHandler(): ItemStackHandler = itemHandler

    fun shouldShowBeam(): Boolean = beamVisible && (isRunActive() || feStored >= getModifiedMaxStorage())

    fun syncToClients() {
        val currentLevel = level ?: return
        if (!currentLevel.isClientSide) {
            currentLevel.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt("fe_stored", feStored)
        tag.putUUID("obelisk_id", obeliskId)
        tag.putString("definition_id", definitionId)
        tag.putString("target_template_id", targetTemplateId)
        activeRunId?.let { tag.putUUID("active_run_id", it) }
        tag.putLong("cooldown_until_game_time", cooldownUntilGameTime)
        tag.putBoolean("beam_visible", beamVisible)
        tag.putInt("beam_color_red", beamColorRed)
        tag.putInt("beam_color_green", beamColorGreen)
        tag.putInt("beam_color_blue", beamColorBlue)
        tag.put("inventory", itemHandler.serializeNBT())
        tag.putInt("modifier_count", modifiers.size)
        modifiers.forEachIndexed { index, modifier ->
            val modifierTag = CompoundTag()
            modifier.save(modifierTag)
            tag.put("modifier_$index", modifierTag)
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        feStored = tag.getInt("fe_stored")
        if (tag.hasUUID("obelisk_id")) obeliskId = tag.getUUID("obelisk_id")
        definitionId = when {
            tag.contains("definition_id") -> tag.getString("definition_id")
            tag.contains("target_template_id") -> tag.getString("target_template_id")
            else -> ObeliskConstants.DEFAULT_TEMPLATES.random()
        }
        activeRunId = if (tag.hasUUID("active_run_id")) tag.getUUID("active_run_id") else null
        cooldownUntilGameTime = tag.getLong("cooldown_until_game_time")
        beamVisible = !tag.contains("beam_visible") || tag.getBoolean("beam_visible")
        beamColorRed = if (tag.contains("beam_color_red")) tag.getInt("beam_color_red") else Random.nextInt(256)
        beamColorGreen = if (tag.contains("beam_color_green")) tag.getInt("beam_color_green") else Random.nextInt(256)
        beamColorBlue = if (tag.contains("beam_color_blue")) tag.getInt("beam_color_blue") else Random.nextInt(256)
        if (tag.contains("inventory")) itemHandler.deserializeNBT(tag.getCompound("inventory"))
        modifiers = if (tag.contains("modifier_count")) {
            buildList {
                repeat(tag.getInt("modifier_count")) { index ->
                    if (tag.contains("modifier_$index")) add(ObeliskModifier.load(tag.getCompound("modifier_$index")))
                }
            }.ifEmpty { ObeliskModifier.generateModifiers() }
        } else {
            ObeliskModifier.generateModifiers()
        }
    }

    override fun getUpdateTag(): CompoundTag = super.getUpdateTag().also(::saveAdditional)

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ENERGY) return energyCapability.cast()
        if (cap == ForgeCapabilities.ITEM_HANDLER && side != null) return itemCapability.cast()
        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        energyCapability.invalidate()
        itemCapability.invalidate()
    }

    override fun onLoad() {
        super.onLoad()
        ObeliskRuntimeService.registerLoaded(this)
    }

    override fun setRemoved() {
        ObeliskRuntimeService.unregisterLoaded(this)
        super.setRemoved()
        FERegenerationHandler.unregisterObelisk(this)
    }
}
