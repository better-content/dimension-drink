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
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
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
import java.util.UUID
import kotlin.random.Random

class ObeliskBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.OBELISK.get(), pos, state) {
    companion object {
        private const val GRAVE_SOIL_REGEN_PER_BLOCK = 0.01
        private const val GRAVE_SOIL_REGEN_MULTIPLIER_CAP = 3.0
        private const val MAX_STORED_GRAVE_SOIL_POSITIONS = 512
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

    var bloodStored: Double = getMaxBlood()
        private set

    private var generatedMaxBlood: Double? = null
    private var graveSoilPositions: List<BlockPos> = emptyList()
    private var lastGraveSoilGlowState: Boolean? = null
    private var nextGraveSoilGlowRefresh: Long = 0L

    var heartStack: ItemStack = ItemStack.EMPTY
        private set

    private var nextReadyAmbientGameTime: Long = 0L
    private var nextLowBloodAmbientGameTime: Long = 0L

    private val energyStorage = object : IEnergyStorage {
        override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
            if (maxReceive <= 0) return 0
            val accepted = minOf(getModifiedMaxStorage() - getEnergyStored(), maxReceive)
            if (!simulate && accepted > 0) {
                bloodStored = (bloodStored + accepted.toDouble()).coerceAtMost(getModifiedMaxStorage().toDouble())
                onBloodStorageChanged()
            }
            return accepted
        }

        override fun extractEnergy(maxExtract: Int, simulate: Boolean): Int {
            if (maxExtract <= 0) return 0
            val extracted = minOf(getEnergyStored(), maxExtract)
            if (!simulate && extracted > 0) {
                bloodStored = (bloodStored - extracted.toDouble()).coerceAtLeast(0.0)
                onBloodStorageChanged()
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
            return FluidStack(fluid, bloodStored.toInt().coerceAtLeast(0))
        }

        override fun getTankCapacity(tank: Int): Int =
            if (tank == 0) getModifiedMaxStorage() else 0

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean =
            tank == 0 && isLifeEssence(stack)

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if (resource.isEmpty || !isLifeEssence(resource)) return 0
            val maxStorage = getModifiedMaxStorage().toDouble()
            if (bloodStored >= maxStorage) return 0
            val accepted = minOf(resource.amount.toDouble(), maxStorage - bloodStored).toInt().coerceAtLeast(0)
            if (accepted > 0 && action.execute()) {
                bloodStored = (bloodStored + accepted.toDouble()).coerceAtMost(maxStorage)
                onBloodStorageChanged()
            }
            return accepted
        }

        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
            if (resource.isEmpty || !isLifeEssence(resource)) return FluidStack.EMPTY
            return drain(resource.amount, action)
        }

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
            val fluid = lifeEssenceFluid() ?: return FluidStack.EMPTY
            if (maxDrain <= 0 || bloodStored <= 0.0) return FluidStack.EMPTY
            val drained = minOf(maxDrain, bloodStored.toInt()).coerceAtLeast(0)
            if (drained > 0 && action.execute()) {
                bloodStored = (bloodStored - drained.toDouble()).coerceAtLeast(0.0)
                onBloodStorageChanged()
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
        bloodStored = amount.toDouble().coerceIn(0.0, getModifiedMaxStorage().toDouble())
        onBloodStorageChanged()
    }

    fun setGeneratedMaxBlood(maxBlood: Double?) {
        generatedMaxBlood = maxBlood?.coerceAtLeast(getDefinitionMaxBlood())
        bloodStored = bloodStored.coerceIn(0.0, getModifiedMaxStorage().toDouble())
        onBloodStorageChanged()
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
        updateGraveSoilGlow(force = true)
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

    fun getBloodStartCost(): Double = 0.0

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
        val heartLevel = getHeartLevel()
        if (heartLevel > 0) {
            rate *= StillBeatingHeartCompat.bloodMultiplier(heartLevel, getHeartBloodMultiplier())
        }
        rate *= getGraveSoilRegenMultiplier()
        return rate.coerceAtLeast(0.0)
    }

    fun setGraveSoilPositions(positions: Collection<BlockPos>) {
        graveSoilPositions = positions
            .distinctBy { it.asLong() }
            .filter { it.distSqr(blockPos) <= 96.0 * 96.0 }
            .take(MAX_STORED_GRAVE_SOIL_POSITIONS)
            .map { it.immutable() }
        updateGraveSoilGlow(force = true)
        setChanged()
        syncToClients()
    }

    fun getRecordedGraveSoilPositions(): List<BlockPos> = graveSoilPositions

    fun isCharging(): Boolean = !isRunActive() && bloodStored < getModifiedMaxStorage().toDouble()

    fun updateGraveSoilGlow(force: Boolean = false) {
        setGraveSoilGlow(isCharging(), force)
    }

    private fun setGraveSoilGlow(desired: Boolean, force: Boolean = false) {
        val currentLevel = level ?: return
        if (currentLevel.isClientSide) return
        val gameTime = currentLevel.gameTime
        if (!force && lastGraveSoilGlowState == desired && gameTime < nextGraveSoilGlowRefresh) return
        lastGraveSoilGlowState = desired
        nextGraveSoilGlowRefresh = gameTime + 20L
        graveSoilPositions.forEach { soilPos ->
            if (!currentLevel.isLoaded(soilPos)) return@forEach
            val state = currentLevel.getBlockState(soilPos)
            if (!state.`is`(ModBlocks.GRAVE_SOIL.get()) || !state.hasProperty(GraveSoilBlock.CHARGING)) return@forEach
            if (state.getValue(GraveSoilBlock.CHARGING) != desired) {
                currentLevel.setBlock(soilPos, state.setValue(GraveSoilBlock.CHARGING, desired), 3)
            }
        }
    }

    private fun getValidGraveSoilCount(): Int {
        val currentLevel = level ?: return 0
        return graveSoilPositions.count { pos ->
            currentLevel.isLoaded(pos) && currentLevel.getBlockState(pos).`is`(ModBlocks.GRAVE_SOIL.get())
        }
    }

    private fun getGraveSoilRegenMultiplier(): Double {
        val bonus = getValidGraveSoilCount() * GRAVE_SOIL_REGEN_PER_BLOCK
        return (1.0 + bonus).coerceAtMost(GRAVE_SOIL_REGEN_MULTIPLIER_CAP)
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
        return factor.coerceAtLeast(0.0)
    }

    fun getEnergyPercent(): Double = getBloodPercent()
    fun getBloodPercent(): Double = bloodStored / getMaxBlood().coerceAtLeast(1.0)
    fun getEnergyStored(): Int = bloodStored.toInt()
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
        if (bloodStored < amount) return false
        bloodStored -= amount
        onBloodStorageChanged()
        return true
    }

    fun regenerateEnergy(amount: Int): Int = regenerateBlood(amount.toDouble()).toInt()

    fun regenerateBlood(amount: Double): Double {
        if (amount <= 0.0 || isRunActive()) return 0.0
        val maxStorage = getModifiedMaxStorage().toDouble()
        if (bloodStored >= maxStorage) return 0.0
        val actual = minOf(amount, maxStorage - bloodStored)
        bloodStored += actual
        onBloodStorageChanged()
        return actual
    }

    fun restoreRunEnergy(amount: Int): Int = restoreRunBlood(amount.toDouble()).toInt()

    fun restoreRunBlood(amount: Double): Double {
        if (amount <= 0.0) return 0.0
        val maxStorage = getModifiedMaxStorage().toDouble()
        if (bloodStored >= maxStorage) return 0.0
        val actual = minOf(amount, maxStorage - bloodStored)
        bloodStored += actual
        onBloodStorageChanged()
        return actual
    }

    private fun onBloodStorageChanged() {
        updateGraveSoilGlow(force = true)
        setChanged()
        syncToClients()
    }

    fun getInternalItemHandler(): ItemStackHandler = itemHandler

    fun shouldShowBeam(): Boolean = beamVisible && (isRunActive() || bloodStored >= getMaxBlood())

    fun hasHeart(): Boolean = !heartStack.isEmpty

    fun isReadyToOpen(): Boolean = hasHeart() && !isRunActive()

    fun isLowBloodWarning(): Boolean = false

    fun clientAmbientTick(tickLevel: Level, tickPos: BlockPos) {
        if (!tickLevel.isClientSide) return
        val gameTime = tickLevel.gameTime
        clientGraveSoilChargeTick(tickLevel, gameTime)
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

    private fun clientGraveSoilChargeTick(tickLevel: Level, gameTime: Long) {
        if (!isCharging()) return
        graveSoilPositions.forEach { soilPos ->
            if (((gameTime + soilPos.asLong()) % 8L) != 0L) return@forEach
            if (!tickLevel.isLoaded(soilPos)) return@forEach
            if (!tickLevel.getBlockState(soilPos).`is`(ModBlocks.GRAVE_SOIL.get())) return@forEach
            val x = soilPos.x + 0.5 + (tickLevel.random.nextDouble() - 0.5) * 0.35
            val y = soilPos.y + 1.03
            val z = soilPos.z + 0.5 + (tickLevel.random.nextDouble() - 0.5) * 0.35
            tickLevel.addParticle(
                if (tickLevel.random.nextBoolean()) ParticleTypes.SOUL else ParticleTypes.SOUL_FIRE_FLAME,
                x,
                y,
                z,
                0.0,
                0.035 + tickLevel.random.nextDouble() * 0.035,
                0.0
            )
            if (tickLevel.random.nextFloat() < 0.35f) {
                tickLevel.addParticle(
                    ParticleTypes.END_ROD,
                    soilPos.x + 0.5,
                    y + 0.05,
                    soilPos.z + 0.5,
                    0.0,
                    0.015 + tickLevel.random.nextDouble() * 0.02,
                    0.0
                )
            }
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
        tag.putDouble("blood_stored", bloodStored)
        generatedMaxBlood?.let { tag.putDouble("generated_max_blood", it) }
        tag.putUUID("obelisk_id", obeliskId)
        tag.putString("definition_id", definitionId)
        tag.putString("target_template_id", targetTemplateId)
        activeRunId?.let { tag.putUUID("active_run_id", it) }
        tag.putBoolean("beam_visible", beamVisible)
        tag.putInt("beam_color_red", beamColorRed)
        tag.putInt("beam_color_green", beamColorGreen)
        tag.putInt("beam_color_blue", beamColorBlue)
        tag.putLongArray("grave_soil_positions", graveSoilPositions.map { it.asLong() })
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
        bloodStored = when {
            tag.contains("blood_stored", Tag.TAG_DOUBLE.toInt()) -> tag.getDouble("blood_stored")
            tag.contains("fe_stored", Tag.TAG_INT.toInt()) -> tag.getInt("fe_stored").toDouble()
            else -> getMaxBlood()
        }.coerceIn(0.0, getModifiedMaxStorage().toDouble())
        activeRunId = if (tag.hasUUID("active_run_id")) tag.getUUID("active_run_id") else null
        cooldownUntilGameTime = 0L
        beamVisible = !tag.contains("beam_visible") || tag.getBoolean("beam_visible")
        beamColorRed = if (tag.contains("beam_color_red")) tag.getInt("beam_color_red") else Random.nextInt(256)
        beamColorGreen = if (tag.contains("beam_color_green")) tag.getInt("beam_color_green") else Random.nextInt(256)
        beamColorBlue = if (tag.contains("beam_color_blue")) tag.getInt("beam_color_blue") else Random.nextInt(256)
        graveSoilPositions = if (tag.contains("grave_soil_positions", Tag.TAG_LONG_ARRAY.toInt())) {
            tag.getLongArray("grave_soil_positions")
                .asSequence()
                .distinct()
                .take(MAX_STORED_GRAVE_SOIL_POSITIONS)
                .map { BlockPos.of(it).immutable() }
                .toList()
        } else {
            emptyList()
        }
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
        setGraveSoilGlow(false, force = true)
        ObeliskRuntimeService.unregisterLoaded(this)
        super.setRemoved()
        FERegenerationHandler.unregisterObelisk(this)
    }
}
