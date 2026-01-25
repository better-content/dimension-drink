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
        // Cache for particle spawn positions to avoid recalculating every tick
        private val particleSpawnCache = mutableMapOf<BlockPos, List<BlockPos>>()

        /**
         * Client-side tick method for spawning ambient particles from nearby ground blocks.
         */
        fun clientTick(level: Level, pos: BlockPos, state: BlockState, blockEntity: ObeliskBlockEntity) {
            if (!level.isClientSide) return

            val energyPercent = blockEntity.getEnergyPercent()

            // Spawn particles from nearby ground blocks
            if (level.random.nextFloat() > 0.4f) return

            // Get or generate particle spawn positions
            val spawnPositions = particleSpawnCache.getOrPut(pos) {
                findNearbyGroundBlocks(level, pos)
            }

            if (spawnPositions.isEmpty()) return

            // Pick random ground block positions to spawn particles from
            val particleCount = if (energyPercent > 0.75) 3 else if (energyPercent > 0.25) 2 else 1

            for (i in 0 until particleCount) {
                val groundPos = spawnPositions.random()

                // Spawn particle from this ground block
                val x = groundPos.x + 0.3 + level.random.nextDouble() * 0.4
                val y = groundPos.y + 1.0 + level.random.nextDouble() * 0.2
                val z = groundPos.z + 0.3 + level.random.nextDouble() * 0.4

                // Velocity - particles drift upward
                val xSpeed = (level.random.nextDouble() - 0.5) * 0.015
                val ySpeed = level.random.nextDouble() * 0.06 + 0.03
                val zSpeed = (level.random.nextDouble() - 0.5) * 0.015

                // Choose particle type based on state
                val particleType = when {
                    blockEntity.isRunActive() -> ParticleTypes.PORTAL // Purple particles when run is active
                    energyPercent >= 1.0 && !blockEntity.isOnCooldown() -> ParticleTypes.WHITE_ASH // Dust when fully charged and ready
                    energyPercent > 0.5 -> ParticleTypes.END_ROD // White particles when charged
                    energyPercent > 0.25 -> ParticleTypes.ENCHANT // Enchantment particles
                    else -> ParticleTypes.SMOKE // Smoke when low energy
                }

                level.addParticle(particleType, x, y, z, xSpeed, ySpeed, zSpeed)
            }

            // Extra dust particles when fully charged and ready - more frequent and numerous
            if (energyPercent >= 1.0 && !blockEntity.isOnCooldown() && level.random.nextFloat() > 0.3f) {
                val extraDustCount = 2 + level.random.nextInt(2) // 2-3 extra dust particles
                for (i in 0 until extraDustCount) {
                    val groundPos = spawnPositions.random()
                    val x = groundPos.x + 0.2 + level.random.nextDouble() * 0.6
                    val y = groundPos.y + 1.0 + level.random.nextDouble() * 0.3
                    val z = groundPos.z + 0.2 + level.random.nextDouble() * 0.6

                    // Slower upward drift for dust
                    val xSpeed = (level.random.nextDouble() - 0.5) * 0.01
                    val ySpeed = level.random.nextDouble() * 0.04 + 0.02
                    val zSpeed = (level.random.nextDouble() - 0.5) * 0.01

                    level.addParticle(ParticleTypes.WHITE_ASH, x, y, z, xSpeed, ySpeed, zSpeed)
                }
            }

            // Extra effect when on cooldown - flame particles from random ground blocks
            if (blockEntity.isOnCooldown() && level.random.nextFloat() > 0.6f) {
                val groundPos = spawnPositions.random()
                val x = groundPos.x + 0.3 + level.random.nextDouble() * 0.4
                val y = groundPos.y + 1.0
                val z = groundPos.z + 0.3 + level.random.nextDouble() * 0.4
                level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.02, 0.0)
            }
        }

        /**
         * Finds solid ground blocks near the obelisk for particle spawning.
         * Looks in a radius around the obelisk and finds blocks that are solid with air above.
         */
        private fun findNearbyGroundBlocks(level: Level, obeliskPos: BlockPos): List<BlockPos> {
            val groundBlocks = mutableListOf<BlockPos>()
            val radius = 8
            val searchHeight = 10

            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    // Skip if too close to obelisk center
                    if (dx * dx + dz * dz < 4) continue

                    // Search down from obelisk level to find ground
                    for (dy in 2 downTo -searchHeight) {
                        val checkPos = obeliskPos.offset(dx, dy, dz)
                        val blockState = level.getBlockState(checkPos)
                        val aboveState = level.getBlockState(checkPos.above())

                        // Found a solid block with air above it
                        if (blockState.isSolidRender(level, checkPos) && 
                            aboveState.isAir && 
                            blockState.fluidState.isEmpty) {
                            groundBlocks.add(checkPos)
                            break
                        }
                    }
                }
            }

            // If we found no ground blocks, use the obelisk position as fallback
            if (groundBlocks.isEmpty()) {
                groundBlocks.add(obeliskPos.below(2))
            }

            return groundBlocks
        }
    }
}
