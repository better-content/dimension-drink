package dev.yourname.obelisks.content

import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.registry.ModBlockEntities
import dev.yourname.obelisks.runtime.energy.FERegenerationHandler
import dev.yourname.obelisks.runtime.ObeliskRuntimeService
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemStackHandler
import net.minecraftforge.registries.ForgeRegistries
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

    var definitionId: String = ObeliskConstants.DEFAULT_TEMPLATES.first()
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

    var bloodStored: Double = getMaxBlood()
        private set

    var heartStack: ItemStack = ItemStack.EMPTY
        private set

    private val energyStorage = object : IEnergyStorage {
        override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
            if (maxReceive <= 0) return 0
            val accepted = minOf(getModifiedMaxStorage() - getEnergyStored(), maxReceive)
            if (!simulate && accepted > 0) {
                bloodStored = (bloodStored + accepted.toDouble()).coerceAtMost(getMaxBlood())
                setChanged()
                syncToClients()
            }
            return accepted
        }

        override fun extractEnergy(maxExtract: Int, simulate: Boolean): Int {
            if (maxExtract <= 0) return 0
            val extracted = minOf(getEnergyStored(), maxExtract)
            if (!simulate && extracted > 0) {
                bloodStored = (bloodStored - extracted.toDouble()).coerceAtLeast(0.0)
                setChanged()
                syncToClients()
            }
            return extracted
        }

        override fun getEnergyStored(): Int = this@ObeliskBlockEntity.getEnergyStored()
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
        bloodStored = amount.toDouble().coerceIn(0.0, getMaxBlood())
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
        var max = getMaxBlood().toInt()
        modifiers.filter { it.stat == FEStat.MAX_STORAGE }.forEach { max = it.applyTo(max) }
        return max
    }

    fun getMaxBlood(): Double = ObeliskDataManager.getObelisk(definitionId)?.maxBlood ?: ObeliskConstants.MAX_BLOOD_STORAGE

    fun getBloodStartCost(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.bloodStartCost ?: ObeliskConstants.BLOOD_START_COST

    fun getBloodJoinCost(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.bloodJoinCost ?: ObeliskConstants.BLOOD_JOIN_COST

    fun getBaseBloodRegenPerTick(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.baseBloodPerTick ?: ObeliskConstants.BLOOD_REGEN_PER_TICK

    fun getRunBloodDrainPerTick(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.runBloodDrainPerTick ?: ObeliskConstants.BASE_BLOOD_DRAIN_PER_TICK

    fun getHeartBloodMultiplier(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.heartBloodMultiplier ?: ObeliskConstants.HEART_BLOOD_MULTIPLIER

    fun getModifiedRegenRate(): Double {
        var rate = getBaseBloodRegenPerTick()
        modifiers.filter { it.stat == FEStat.REGEN_RATE }.forEach { rate = it.applyTo(rate) }
        val heartLevel = getHeartLevel()
        if (heartLevel > 0) {
            rate *= 1.0 + (heartLevel * getHeartBloodMultiplier())
        }
        return rate.coerceAtLeast(0.0)
    }

    fun getModifiedBaseDrain(): Double {
        var drain = getRunBloodDrainPerTick()
        modifiers.filter { it.stat == FEStat.BASE_DRAIN }.forEach { drain = it.applyTo(drain) }
        return drain.coerceAtLeast(0.0)
    }

    fun getModifiedPlayerDrain(): Double {
        var drain = ObeliskConstants.PER_PLAYER_BLOOD_DRAIN
        modifiers.filter { it.stat == FEStat.PLAYER_DRAIN }.forEach { drain = it.applyTo(drain) }
        return drain.coerceAtLeast(0.0)
    }

    fun getModifiedDrainFactor(): Double {
        var factor = ObeliskConstants.DRAIN_EXPONENTIAL_FACTOR
        modifiers.filter { it.stat == FEStat.DRAIN_FACTOR }.forEach { factor = it.applyTo(factor) }
        return factor
    }

    fun getEnergyPercent(): Double = getBloodPercent()
    fun getBloodPercent(): Double = bloodStored / getMaxBlood().coerceAtLeast(1.0)
    fun getEnergyStored(): Int = bloodStored.toInt()
    fun getMaxEnergyStored(): Int = getModifiedMaxStorage()
    fun getBeamColorFloats(): FloatArray = floatArrayOf(
        beamColorRed / 255.0f,
        beamColorGreen / 255.0f,
        beamColorBlue / 255.0f
    )

    fun drainEnergy(amount: Int): Boolean = drainBlood(amount.toDouble())

    fun drainBlood(amount: Double): Boolean {
        if (amount <= 0.0) return true
        if (bloodStored < amount) return false
        bloodStored -= amount
        setChanged()
        syncToClients()
        return true
    }

    fun regenerateEnergy(amount: Int): Int = regenerateBlood(amount.toDouble()).toInt()

    fun regenerateBlood(amount: Double): Double {
        if (amount <= 0.0 || isRunActive()) return 0.0
        val maxStorage = getModifiedMaxStorage().toDouble()
        if (bloodStored >= maxStorage) return 0.0
        val actual = minOf(amount, maxStorage - bloodStored)
        bloodStored += actual
        setChanged()
        syncToClients()
        return actual
    }

    fun restoreRunEnergy(amount: Int): Int = restoreRunBlood(amount.toDouble()).toInt()

    fun restoreRunBlood(amount: Double): Double {
        if (amount <= 0.0) return 0.0
        val maxStorage = getModifiedMaxStorage().toDouble()
        if (bloodStored >= maxStorage) return 0.0
        val actual = minOf(amount, maxStorage - bloodStored)
        bloodStored += actual
        setChanged()
        syncToClients()
        return actual
    }

    fun getInternalItemHandler(): ItemStackHandler = itemHandler

    fun shouldShowBeam(): Boolean = beamVisible && (isRunActive() || bloodStored >= getMaxBlood())

    fun hasHeart(): Boolean = !heartStack.isEmpty

    fun canAcceptHeart(stack: ItemStack): Boolean = heartStack.isEmpty && isStillBeatingHeart(stack)

    fun placeHeart(stack: ItemStack): Boolean {
        if (!canAcceptHeart(stack)) return false
        heartStack = stack.copyWithCount(1)
        stack.shrink(1)
        setChanged()
        syncToClients()
        return true
    }

    fun removeHeart(): ItemStack {
        if (heartStack.isEmpty) return ItemStack.EMPTY
        val removed = heartStack.copy()
        heartStack = ItemStack.EMPTY
        setChanged()
        syncToClients()
        return removed
    }

    fun getHeartLevel(): Int {
        val tag = heartStack.tag ?: return 0
        if (!tag.contains("StillBeatingHeartData", Tag.TAG_COMPOUND.toInt())) return 0
        val data = tag.getCompound("StillBeatingHeartData")
        return when {
            data.contains("level", Tag.TAG_INT.toInt()) -> data.getInt("level")
            data.contains("player", Tag.TAG_COMPOUND.toInt()) -> data.getCompound("player").getInt("experience_level")
            else -> 0
        }.coerceAtLeast(0)
    }

    fun isStillBeatingHeart(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return ForgeRegistries.ITEMS.getKey(stack.item) == ResourceLocation("rpgstats", "still_beating_heart")
    }

    fun syncToClients() {
        val currentLevel = level ?: return
        if (!currentLevel.isClientSide) {
            currentLevel.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putDouble("blood_stored", bloodStored)
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
        if (!heartStack.isEmpty) {
            tag.put("heart", heartStack.save(CompoundTag()))
        }
        tag.putInt("modifier_count", modifiers.size)
        modifiers.forEachIndexed { index, modifier ->
            val modifierTag = CompoundTag()
            modifier.save(modifierTag)
            tag.put("modifier_$index", modifierTag)
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        if (tag.hasUUID("obelisk_id")) obeliskId = tag.getUUID("obelisk_id")
        definitionId = when {
            tag.contains("definition_id") -> tag.getString("definition_id")
            tag.contains("target_template_id") -> tag.getString("target_template_id")
            else -> ObeliskConstants.DEFAULT_TEMPLATES.first()
        }
        bloodStored = when {
            tag.contains("blood_stored", Tag.TAG_DOUBLE.toInt()) -> tag.getDouble("blood_stored")
            tag.contains("fe_stored", Tag.TAG_INT.toInt()) -> tag.getInt("fe_stored").toDouble()
            else -> getMaxBlood()
        }.coerceIn(0.0, getMaxBlood())
        activeRunId = if (tag.hasUUID("active_run_id")) tag.getUUID("active_run_id") else null
        cooldownUntilGameTime = tag.getLong("cooldown_until_game_time")
        beamVisible = !tag.contains("beam_visible") || tag.getBoolean("beam_visible")
        beamColorRed = if (tag.contains("beam_color_red")) tag.getInt("beam_color_red") else Random.nextInt(256)
        beamColorGreen = if (tag.contains("beam_color_green")) tag.getInt("beam_color_green") else Random.nextInt(256)
        beamColorBlue = if (tag.contains("beam_color_blue")) tag.getInt("beam_color_blue") else Random.nextInt(256)
        if (tag.contains("inventory")) itemHandler.deserializeNBT(tag.getCompound("inventory"))
        heartStack = if (tag.contains("heart", Tag.TAG_COMPOUND.toInt())) {
            ItemStack.of(tag.getCompound("heart"))
        } else {
            ItemStack.EMPTY
        }
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
