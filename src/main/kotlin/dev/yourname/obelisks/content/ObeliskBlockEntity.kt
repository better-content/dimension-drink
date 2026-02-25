package dev.yourname.obelisks.content

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.registry.ModBlockEntities
import dev.yourname.obelisks.jaunt.FERegenerationHandler
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemStackHandler
import net.minecraft.world.item.ItemStack
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
    var beamVisible: Boolean = true // Whether the beam is visible (player-controlled)

    // Beam color (RGB, each 0-255) - randomized per obelisk for unique visual identity
    var beamColorRed: Int = Random.nextInt(256)
        private set
    var beamColorGreen: Int = Random.nextInt(256)
        private set
    var beamColorBlue: Int = Random.nextInt(256)
        private set

    // Hidden sided output cap: random value between 8 and 32 items per extraction
    private val sidedOutputCap: Int = 8 + Random.nextInt(25) // 8-32

    // Internal item buffer for rewards (9 slots)
    private val itemHandler = object : ItemStackHandler(9) {
        override fun onContentsChanged(slot: Int) {
            setChanged()
        }
    }

    // Wrapped item handler that enforces extraction limit
    private val limitedItemHandler = object : IItemHandler {
        override fun getSlots(): Int = itemHandler.slots

        override fun getStackInSlot(slot: Int): ItemStack = itemHandler.getStackInSlot(slot)

        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
            return itemHandler.insertItem(slot, stack, simulate)
        }

        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
            // Cap extraction at the hidden affixed value
            val cappedAmount = minOf(amount, sidedOutputCap)
            return itemHandler.extractItem(slot, cappedAmount, simulate)
        }

        override fun getSlotLimit(slot: Int): Int = itemHandler.getSlotLimit(slot)

        override fun isItemValid(slot: Int, stack: ItemStack): Boolean = itemHandler.isItemValid(slot, stack)
    }

    private val itemCapability: LazyOptional<IItemHandler> = LazyOptional.of { limitedItemHandler }

    init {
        // Register for FE regeneration tracking
        FERegenerationHandler.registerObelisk(this)

        // Debug logging for modifiers
    }

    /**
     * Returns the beam color as a packed RGB integer (0xRRGGBB).
     */
    fun getBeamColor(): Int {
        return (beamColorRed shl 16) or (beamColorGreen shl 8) or beamColorBlue
    }

    /**
     * Returns the beam color components as a float array [r, g, b] in range 0.0-1.0.
     */
    fun getBeamColorFloats(): FloatArray {
        return floatArrayOf(
            beamColorRed / 255f,
            beamColorGreen / 255f,
            beamColorBlue / 255f
        )
    }

    fun isRunActive(): Boolean = activeRunId != null

    /**
     * Returns true if the beam should be rendered.
     * Beam shows when: beam is enabled AND (obelisk is fully charged OR has an active run).
     */
    fun shouldShowBeam(): Boolean {
        return beamVisible && (isRunActive() || (feStored >= getModifiedMaxStorage()))
    }

    /**
     * Toggles beam visibility and syncs to clients.
     */
    fun toggleBeamVisibility() {
        beamVisible = !beamVisible
        setChanged()
        syncToClients()
    }

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
        if (cap == ForgeCapabilities.ITEM_HANDLER && side != null) {
            // Only expose item handler on sides (not null = internal access)
            return itemCapability.cast()
        }
        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        energyCapability.invalidate()
        itemCapability.invalidate()
    }

    /**
     * Gets the internal item handler for reward insertion (not exposed via capability).
     */
    fun getInternalItemHandler(): ItemStackHandler = itemHandler

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
        tag.putBoolean("BeamVisible", beamVisible)
        tag.putInt("BeamColorRed", beamColorRed)
        tag.putInt("BeamColorGreen", beamColorGreen)
        tag.putInt("BeamColorBlue", beamColorBlue)
        tag.putInt("SidedOutputCap", sidedOutputCap)

        // Save item inventory
        tag.put("Inventory", itemHandler.serializeNBT())

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

        beamVisible = if (tag.contains("BeamVisible")) {
            tag.getBoolean("BeamVisible")
        } else true // Default to visible for backwards compatibility

        // Load beam colors (with random defaults for backwards compatibility)
        beamColorRed = if (tag.contains("BeamColorRed")) {
            tag.getInt("BeamColorRed")
        } else Random.nextInt(256)

        beamColorGreen = if (tag.contains("BeamColorGreen")) {
            tag.getInt("BeamColorGreen")
        } else Random.nextInt(256)

        beamColorBlue = if (tag.contains("BeamColorBlue")) {
            tag.getInt("BeamColorBlue")
        } else Random.nextInt(256)

        // Load sided output cap (migration: old obelisks keep their randomly generated value)
        // Note: Can't reassign private val sidedOutputCap, so loaded value is ignored

        // Load item inventory
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(tag.getCompound("Inventory"))
        }

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

    companion object {
        /**
         * Client-side tick method for spawning ambient particles and sounds.
         */
        fun clientTick(level: Level, pos: BlockPos, state: BlockState, blockEntity: ObeliskBlockEntity) {
            if (!level.isClientSide) return

            val energyPercent = blockEntity.getEnergyPercent()

            // Play ambient humming sound when fully charged
            playAmbientHum(level, pos, blockEntity, energyPercent)

            // Spawn interdimensional particles that get sucked into the obelisk
            spawnSuckingParticles(level, pos, blockEntity, energyPercent)
        }

        /**
         * Plays ambient humming sound when obelisk is fully charged and ready.
         */
        private fun playAmbientHum(level: Level, pos: BlockPos, blockEntity: ObeliskBlockEntity, energyPercent: Double) {
            // Only hum when fully charged and ready (not on cooldown, not in active run)
            if (energyPercent < 1.0 || blockEntity.isOnCooldown() || blockEntity.isRunActive()) return

            // Play sound occasionally (every ~3 seconds on average)
            if (level.random.nextFloat() > 0.016f) return

            // Play beacon ambient sound for energetic hum
            level.playLocalSound(
                pos.x + 0.5,
                pos.y + 0.5,
                pos.z + 0.5,
                net.minecraft.sounds.SoundEvents.BEACON_AMBIENT,
                net.minecraft.sounds.SoundSource.BLOCKS,
                0.25f, // Quiet volume
                0.8f + level.random.nextFloat() * 0.2f, // Lower pitch (0.8-1.0) for deeper hum
                false
            )
        }

        /**
         * Spawns spooky interdimensional particles that spiral and get sucked into the obelisk.
         */
        private fun spawnSuckingParticles(level: Level, pos: BlockPos, blockEntity: ObeliskBlockEntity, energyPercent: Double) {
            val centerX = pos.x + 0.5
            val centerY = pos.y + 0.5
            val centerZ = pos.z + 0.5

            // More particles when active or fully charged
            val particleCount = when {
                blockEntity.isRunActive() -> 6
                energyPercent >= 1.0 && !blockEntity.isOnCooldown() -> 4
                energyPercent > 0.5 -> 3
                energyPercent > 0.25 -> 2
                else -> 1
            }

            // Only spawn some of the time
            if (level.random.nextFloat() > 0.7f) return

            for (i in 0 until particleCount) {
                // Spawn particles in a sphere around the obelisk
                val radius = 2.0 + level.random.nextDouble() * 2.5
                val theta = level.random.nextDouble() * Math.PI * 2.0
                val phi = level.random.nextDouble() * Math.PI

                val offsetX = radius * Math.sin(phi) * Math.cos(theta)
                val offsetY = radius * Math.sin(phi) * Math.sin(theta)
                val offsetZ = radius * Math.cos(phi)

                val spawnX = centerX + offsetX
                val spawnY = centerY + offsetY
                val spawnZ = centerZ + offsetZ

                // Velocity towards the obelisk center (sucking effect)
                val pullStrength = 0.08 + level.random.nextDouble() * 0.04
                val velocityX = -offsetX * pullStrength / radius
                val velocityY = -offsetY * pullStrength / radius
                val velocityZ = -offsetZ * pullStrength / radius

                // Choose spooky particle types
                val particleType = when {
                    blockEntity.isRunActive() -> {
                        // Mix of portal and reverse portal when run is active
                        if (level.random.nextBoolean()) ParticleTypes.PORTAL else ParticleTypes.REVERSE_PORTAL
                    }
                    energyPercent >= 1.0 && !blockEntity.isOnCooldown() -> {
                        // Mix of enchant glyphs and soul when fully charged
                        if (level.random.nextFloat() < 0.7f) ParticleTypes.ENCHANT else ParticleTypes.SOUL
                    }
                    energyPercent > 0.5 -> {
                        // Warped spores and portal for interdimensional feel
                        if (level.random.nextFloat() < 0.6f) ParticleTypes.WARPED_SPORE else ParticleTypes.PORTAL
                    }
                    else -> {
                        // Smoke and ash when low energy
                        if (level.random.nextBoolean()) ParticleTypes.SMOKE else ParticleTypes.ASH
                    }
                }

                level.addParticle(particleType, spawnX, spawnY, spawnZ, velocityX, velocityY, velocityZ)
            }

            // Extra reverse portal particles when run is active for extra spookiness
            if (blockEntity.isRunActive() && level.random.nextFloat() > 0.5f) {
                val radius = 3.0
                val theta = level.random.nextDouble() * Math.PI * 2.0
                val phi = level.random.nextDouble() * Math.PI

                val offsetX = radius * Math.sin(phi) * Math.cos(theta)
                val offsetY = radius * Math.sin(phi) * Math.sin(theta)
                val offsetZ = radius * Math.cos(phi)

                val spawnX = centerX + offsetX
                val spawnY = centerY + offsetY
                val spawnZ = centerZ + offsetZ

                val velocityX = -offsetX * 0.1 / radius
                val velocityY = -offsetY * 0.1 / radius
                val velocityZ = -offsetZ * 0.1 / radius

                level.addParticle(ParticleTypes.REVERSE_PORTAL, spawnX, spawnY, spawnZ, velocityX, velocityY, velocityZ)
            }
        }
    }
}
