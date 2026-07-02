package dev.yourname.obelisks.content

import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.compat.StillBeatingHeartCompat
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.registry.ModBlockEntities
import dev.yourname.obelisks.registry.ModBlocks
import dev.yourname.obelisks.runtime.energy.FERegenerationHandler
import dev.yourname.obelisks.runtime.ObeliskRuntimeService
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemStackHandler
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.ForgeRegistries.BLOCKS
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random

class ObeliskBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.OBELISK.get(), pos, state) {
    companion object {
        private val BLOOD_MAGIC_LIFE_ESSENCE_FLUID_ID = ResourceLocation("bloodmagic", "life_essence_fluid")
        private const val BLOOD_MAGIC_LIFE_ESSENCE_DESCRIPTION_ID = "fluid.bloodmagic.life_essence_fluid"
    }

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

    private var generatedMaxBlood: Double? = null
    private var lifeEssenceStored: Int = ObeliskConstants.MAX_BLOOD_STORAGE.toInt()
    val bloodStored: Double
        get() = lifeEssenceStored.toDouble()

    private var fractionalRegenCarry: Double = 0.0
    private var fractionalDrainCarry: Double = 0.0

    var heartStack: ItemStack = ItemStack.EMPTY
        private set

    private var nextReadyAmbientGameTime: Long = 0L
    private var nextLowBloodAmbientGameTime: Long = 0L

    private val energyStorage = object : IEnergyStorage {
        override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
            if (maxReceive <= 0) return 0
            val accepted = minOf(getModifiedMaxStorage() - lifeEssenceStored, maxReceive)
            if (!simulate && accepted > 0) {
                fillLifeEssenceTank(accepted)
            }
            return accepted
        }

        override fun extractEnergy(maxExtract: Int, simulate: Boolean): Int {
            if (maxExtract <= 0) return 0
            val extracted = minOf(lifeEssenceStored, maxExtract)
            if (!simulate && extracted > 0) {
                drainLifeEssenceTank(extracted)
            }
            return extracted
        }

        override fun getEnergyStored(): Int = this@ObeliskBlockEntity.getEnergyStored()
        override fun getMaxEnergyStored(): Int = getModifiedMaxStorage()
        override fun canExtract(): Boolean = true
        override fun canReceive(): Boolean = true
    }

    private val fluidStorage = object : IFluidHandler {
        override fun getTanks(): Int = 1

        override fun getFluidInTank(tank: Int): FluidStack {
            if (tank != 0) return FluidStack.EMPTY
            val fluid = lifeEssenceFluid() ?: return FluidStack.EMPTY
            return FluidStack(fluid, lifeEssenceStored.coerceAtLeast(0))
        }

        override fun getTankCapacity(tank: Int): Int =
            if (tank == 0) getModifiedMaxStorage() else 0

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean =
            tank == 0 && isLifeEssence(stack)

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if (resource.isEmpty || !isLifeEssence(resource)) return 0
            val maxStorage = getModifiedMaxStorage()
            if (lifeEssenceStored >= maxStorage) return 0
            val accepted = minOf(resource.amount, maxStorage - lifeEssenceStored).coerceAtLeast(0)
            if (accepted > 0 && action.execute()) {
                fillLifeEssenceTank(accepted)
            }
            return accepted
        }

        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
            if (resource.isEmpty || !isLifeEssence(resource)) return FluidStack.EMPTY
            return drain(resource.amount, action)
        }

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
            val fluid = lifeEssenceFluid() ?: return FluidStack.EMPTY
            if (maxDrain <= 0 || lifeEssenceStored <= 0) return FluidStack.EMPTY
            val drained = minOf(maxDrain, lifeEssenceStored).coerceAtLeast(0)
            if (drained > 0 && action.execute()) {
                drainLifeEssenceTank(drained)
            }
            return if (drained > 0) FluidStack(fluid, drained) else FluidStack.EMPTY
        }
    }

    private val energyCapability: LazyOptional<IEnergyStorage> = LazyOptional.of { energyStorage }
    private val fluidCapability: LazyOptional<IFluidHandler> = LazyOptional.of { fluidStorage }
    private val itemCapability: LazyOptional<IItemHandler> = LazyOptional.of { limitedItemHandler }

    init {
        FERegenerationHandler.registerObelisk(this)
    }

    fun isRunActive(): Boolean = activeRunId != null

    fun isOnCooldown(): Boolean {
        return false
    }

    fun getCooldownRemainingTicks(): Long {
        return 0L
    }

    fun startCooldown() {
        cooldownUntilGameTime = 0L
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
        setLifeEssenceStored(amount)
    }

    fun setGeneratedMaxBlood(maxBlood: Double?) {
        generatedMaxBlood = maxBlood?.coerceAtLeast(getDefinitionMaxBlood())
        val previous = lifeEssenceStored
        setLifeEssenceStored(lifeEssenceStored)
        if (lifeEssenceStored == previous) {
            onBloodStorageChanged()
        }
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

    fun getMaxBlood(): Double = maxOf(getDefinitionMaxBlood(), generatedMaxBlood ?: 0.0)

    private fun getDefinitionMaxBlood(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.maxBlood ?: ObeliskConstants.MAX_BLOOD_STORAGE

    fun getBloodStartCost(): Double = 500.0

    fun getBloodJoinCost(): Double = 0.0

    fun getBaseBloodRegenPerTick(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.baseBloodPerTick ?: ObeliskConstants.BLOOD_REGEN_PER_TICK

    fun getRunBloodDrainPerTick(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.runBloodDrainPerTick ?: ObeliskConstants.BASE_BLOOD_DRAIN_PER_TICK

    fun getHeartBloodMultiplier(): Double =
        ObeliskDataManager.getObelisk(definitionId)?.heartBloodMultiplier ?: ObeliskConstants.HEART_BLOOD_MULTIPLIER

    fun getModifiedRegenRate(): Double {
        var rate = getBaseBloodRegenPerTick()
        modifiers.filter { it.stat == FEStat.REGEN_RATE }.forEach { rate = it.applyTo(rate) }
        rate += StillBeatingHeartCompat.lpPerTick(heartStack).toDouble()
        return rate.coerceAtLeast(0.0)
    }

    fun getOxidationRegenMultiplier(): Double = 1.0

    fun tick(tickLevel: Level, tickPos: BlockPos) {
        if (tickLevel.isClientSide) {
            clientAmbientTick(tickLevel, tickPos)
        }
    }

    fun scrapeAltarCopperOxidation(currentLevel: Level): Int {
        if (currentLevel.isClientSide) return 0
        var scraped = 0
        for (dx in -1..1) {
            for (dz in -1..1) {
                val pos = blockPos.offset(dx, -1, dz)
                val state = currentLevel.getBlockState(pos)
                val renewedBlock = previousCopperStage(state.block) ?: continue
                val renewedState = renewedBlock.withPropertiesOf(state)
                if (renewedState == state) continue
                currentLevel.setBlock(pos, renewedState, 3)
                scraped++
            }
        }
        return scraped
    }

    private fun previousCopperStage(block: Block): Block? {
        val id = BLOCKS.getKey(block) ?: return null
        val path = id.path
        val renewedPath = when {
            path.contains("oxidized") -> path.replace("oxidized_", "weathered_")
            path.contains("weathered") -> path.replace("weathered_", "exposed_")
            path.contains("exposed") -> path.replace("exposed_", "")
            else -> return null
        }
        return BLOCKS.getValue(ResourceLocation(id.namespace, renewedPath)).takeUnless { it == Blocks.AIR }
    }

    fun isCharging(): Boolean = !isRunActive() && lifeEssenceStored < getModifiedMaxStorage()

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
        return factor.coerceAtLeast(0.0)
    }

    fun getEnergyPercent(): Double = getBloodPercent()
    fun getBloodPercent(): Double = lifeEssenceStored.toDouble() / getModifiedMaxStorage().coerceAtLeast(1).toDouble()
    fun getEnergyStored(): Int = lifeEssenceStored
    fun getMaxEnergyStored(): Int = getModifiedMaxStorage()

    private fun lifeEssenceFluid() =
        ForgeRegistries.FLUIDS.getValue(BLOOD_MAGIC_LIFE_ESSENCE_FLUID_ID)
            ?.takeIf { fluid ->
                ForgeRegistries.FLUIDS.getKey(fluid) == BLOOD_MAGIC_LIFE_ESSENCE_FLUID_ID ||
                    fluid.fluidType.descriptionId == BLOOD_MAGIC_LIFE_ESSENCE_DESCRIPTION_ID
            }

    fun isLifeEssence(stack: FluidStack): Boolean {
        if (stack.isEmpty) return false
        val registeredName = ForgeRegistries.FLUIDS.getKey(stack.fluid)
        return registeredName == BLOOD_MAGIC_LIFE_ESSENCE_FLUID_ID ||
            stack.fluid.fluidType.descriptionId == BLOOD_MAGIC_LIFE_ESSENCE_DESCRIPTION_ID
    }

    fun getBeamColorFloats(): FloatArray = floatArrayOf(
        beamColorRed / 255.0f,
        beamColorGreen / 255.0f,
        beamColorBlue / 255.0f
    )

    fun drainEnergy(amount: Int): Boolean = drainBlood(amount.toDouble())

    fun drainBlood(amount: Double): Boolean {
        if (amount <= 0.0) return true
        val totalDrain = amount + fractionalDrainCarry
        val wholeDrain = floor(totalDrain).toInt()
        val nextCarry = totalDrain - wholeDrain
        if (wholeDrain <= 0) {
            fractionalDrainCarry = nextCarry
            return true
        }
        if (lifeEssenceStored < wholeDrain) return false
        drainLifeEssenceTank(wholeDrain)
        fractionalDrainCarry = nextCarry
        return true
    }

    fun regenerateEnergy(amount: Int): Int = regenerateBlood(amount.toDouble()).toInt()

    fun regenerateBlood(amount: Double): Double {
        if (amount <= 0.0 || isRunActive()) return 0.0
        val maxStorage = getModifiedMaxStorage()
        if (lifeEssenceStored >= maxStorage) {
            fractionalRegenCarry = 0.0
            return 0.0
        }
        val totalRegen = amount + fractionalRegenCarry
        val wholeRegen = floor(totalRegen).toInt()
        fractionalRegenCarry = totalRegen - wholeRegen
        if (wholeRegen <= 0) return 0.0
        return fillLifeEssenceTank(wholeRegen).toDouble()
    }

    fun restoreRunEnergy(amount: Int): Int = restoreRunBlood(amount.toDouble()).toInt()

    fun restoreRunBlood(amount: Double): Double {
        if (amount <= 0.0) return 0.0
        if (lifeEssenceStored >= getModifiedMaxStorage()) return 0.0
        return fillLifeEssenceTank(floor(amount).toInt()).toDouble()
    }

    private fun setLifeEssenceStored(amount: Int) {
        val clamped = amount.coerceIn(0, getModifiedMaxStorage())
        if (lifeEssenceStored == clamped) return
        lifeEssenceStored = clamped
        fractionalRegenCarry = 0.0
        fractionalDrainCarry = 0.0
        onBloodStorageChanged()
    }

    private fun fillLifeEssenceTank(amount: Int): Int {
        if (amount <= 0) return 0
        val accepted = minOf(amount, getModifiedMaxStorage() - lifeEssenceStored).coerceAtLeast(0)
        if (accepted <= 0) return 0
        lifeEssenceStored += accepted
        onBloodStorageChanged()
        return accepted
    }

    private fun drainLifeEssenceTank(amount: Int): Int {
        if (amount <= 0) return 0
        val drained = minOf(amount, lifeEssenceStored).coerceAtLeast(0)
        if (drained <= 0) return 0
        lifeEssenceStored -= drained
        onBloodStorageChanged()
        return drained
    }

    private fun onBloodStorageChanged() {
        setChanged()
        syncToClients()
    }

    fun getInternalItemHandler(): ItemStackHandler = itemHandler

    fun shouldShowBeam(): Boolean = beamVisible && (isRunActive() || lifeEssenceStored >= getModifiedMaxStorage())

    fun hasHeart(): Boolean = !heartStack.isEmpty

    fun isReadyToOpen(): Boolean = hasHeart() && !isRunActive()

    fun isLowBloodWarning(): Boolean = false

    fun clientAmbientTick(tickLevel: Level, tickPos: BlockPos) {
        if (!tickLevel.isClientSide) return
        val gameTime = tickLevel.gameTime
        if (isReadyToOpen() && gameTime >= nextReadyAmbientGameTime) {
            nextReadyAmbientGameTime = gameTime + 75L + tickLevel.random.nextInt(55)
            tickLevel.playLocalSound(
                tickPos.x + 0.5,
                tickPos.y + 0.5,
                tickPos.z + 0.5,
                SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS,
                0.35f,
                0.55f + tickLevel.random.nextFloat() * 0.2f,
                false
            )
        }
        if (isLowBloodWarning() && gameTime >= nextLowBloodAmbientGameTime) {
            nextLowBloodAmbientGameTime = gameTime + 95L + tickLevel.random.nextInt(75)
            tickLevel.playLocalSound(
                tickPos.x + 0.5,
                tickPos.y + 0.5,
                tickPos.z + 0.5,
                if (tickLevel.random.nextBoolean()) SoundEvents.SOUL_ESCAPE else SoundEvents.WARDEN_AMBIENT,
                SoundSource.BLOCKS,
                0.45f,
                0.65f + tickLevel.random.nextFloat() * 0.25f,
                false
            )
        }
    }

    fun canAcceptHeart(stack: ItemStack): Boolean = heartStack.isEmpty && StillBeatingHeartCompat.isFontHeart(stack)

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

    fun getHeartLevel(): Int = StillBeatingHeartCompat.getLevel(heartStack)

    fun syncToClients() {
        val currentLevel = level ?: return
        if (!currentLevel.isClientSide) {
            currentLevel.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt("life_essence_stored", lifeEssenceStored)
        tag.putDouble("blood_stored", bloodStored)
        tag.putDouble("fractional_regen_carry", fractionalRegenCarry)
        tag.putDouble("fractional_drain_carry", fractionalDrainCarry)
        generatedMaxBlood?.let { tag.putDouble("generated_max_blood", it) }
        tag.putUUID("obelisk_id", obeliskId)
        tag.putString("definition_id", definitionId)
        tag.putString("target_template_id", targetTemplateId)
        activeRunId?.let { tag.putUUID("active_run_id", it) }
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
        generatedMaxBlood = if (tag.contains("generated_max_blood", Tag.TAG_DOUBLE.toInt())) {
            tag.getDouble("generated_max_blood").coerceAtLeast(getDefinitionMaxBlood())
        } else {
            null
        }
        lifeEssenceStored = when {
            tag.contains("life_essence_stored", Tag.TAG_INT.toInt()) -> tag.getInt("life_essence_stored")
            tag.contains("blood_stored", Tag.TAG_DOUBLE.toInt()) -> floor(tag.getDouble("blood_stored")).toInt()
            tag.contains("fe_stored", Tag.TAG_INT.toInt()) -> tag.getInt("fe_stored")
            else -> getModifiedMaxStorage()
        }.coerceIn(0, getModifiedMaxStorage())
        fractionalRegenCarry = if (tag.contains("fractional_regen_carry", Tag.TAG_DOUBLE.toInt())) {
            tag.getDouble("fractional_regen_carry").coerceIn(0.0, 0.999_999)
        } else {
            0.0
        }
        fractionalDrainCarry = if (tag.contains("fractional_drain_carry", Tag.TAG_DOUBLE.toInt())) {
            tag.getDouble("fractional_drain_carry").coerceIn(0.0, 0.999_999)
        } else {
            0.0
        }
        activeRunId = if (tag.hasUUID("active_run_id")) tag.getUUID("active_run_id") else null
        cooldownUntilGameTime = 0L
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

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket =
        ClientboundBlockEntityDataPacket.create(this)

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ENERGY) return energyCapability.cast()
        if (cap == ForgeCapabilities.FLUID_HANDLER) return fluidCapability.cast()
        if (cap == ForgeCapabilities.ITEM_HANDLER && side != null) return itemCapability.cast()
        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        energyCapability.invalidate()
        fluidCapability.invalidate()
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
