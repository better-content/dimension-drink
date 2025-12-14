package dev.yourname.obelisks.content

import dev.yourname.obelisks.registry.ModMenuTypes
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData

/**
 * Container menu for the Obelisk GUI.
 * Syncs FE data to the client.
 */
class ObeliskMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val obeliskPos: BlockPos,
    private val data: ContainerData
) : AbstractContainerMenu(ModMenuTypes.OBELISK_MENU.get(), syncId) {

    companion object {
        // Data slot indices
        const val DATA_FE_STORED = 0
        const val DATA_MAX_FE = 1
        const val DATA_HAS_ACTIVE_RUN = 2
        const val DATA_BASE_TYPE_ORDINAL = 3
        const val DATA_DRAIN_MULTIPLIER_INT = 4  // Multiplier * 100 as int (e.g., 150 = 1.5x)
        const val DATA_REGEN_RATE = 5  // Modified regen rate per tick
        const val DATA_BASE_DRAIN = 6  // Modified base drain per tick
        const val DATA_PLAYER_DRAIN = 7  // Modified per-player drain per tick
        const val DATA_COOLDOWN_REMAINING = 8  // Cooldown remaining in seconds

        const val DATA_COUNT = 9

        /**
         * Factory for creating menu from network packet (client-side)
         */
        fun create(syncId: Int, playerInventory: Inventory, buf: FriendlyByteBuf): ObeliskMenu {
            val pos = buf.readBlockPos()
            val data = SimpleContainerData(DATA_COUNT)
            return ObeliskMenu(syncId, playerInventory, pos, data)
        }
    }

    private val level = playerInventory.player.level()

    init {
        // Add data slots for syncing
        addDataSlots(data)
    }

    fun getObeliskBlockEntity(): ObeliskBlockEntity? {
        val be = level.getBlockEntity(obeliskPos)
        return be as? ObeliskBlockEntity
    }

    fun getObeliskPos(): BlockPos = obeliskPos

    fun getFEStored(): Int = data.get(DATA_FE_STORED)
    fun getMaxFE(): Int = data.get(DATA_MAX_FE)
    fun hasActiveRun(): Boolean = data.get(DATA_HAS_ACTIVE_RUN) == 1
    fun getBaseTypeOrdinal(): Int = data.get(DATA_BASE_TYPE_ORDINAL)
    fun getDrainMultiplier(): Double = data.get(DATA_DRAIN_MULTIPLIER_INT) / 100.0
    fun getRegenRate(): Int = data.get(DATA_REGEN_RATE)
    fun getBaseDrain(): Int = data.get(DATA_BASE_DRAIN)
    fun getPlayerDrain(): Int = data.get(DATA_PLAYER_DRAIN)
    fun getCooldownRemaining(): Int = data.get(DATA_COOLDOWN_REMAINING)
    fun isOnCooldown(): Boolean = getCooldownRemaining() > 0

    override fun quickMoveStack(player: Player, index: Int): net.minecraft.world.item.ItemStack {
        return net.minecraft.world.item.ItemStack.EMPTY
    }

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(
            obeliskPos.x.toDouble() + 0.5,
            obeliskPos.y.toDouble() + 0.5,
            obeliskPos.z.toDouble() + 0.5
        ) <= 64.0
    }

    /**
     * Called every tick on the server side to sync data to clients.
     */
    override fun broadcastChanges() {
        super.broadcastChanges()
        updateData()
    }

    /**
     * Updates the data slots from the block entity.
     * Called on server side.
     */
    fun updateData() {
        val be = getObeliskBlockEntity() ?: return
        data.set(DATA_FE_STORED, be.feStored)
        data.set(DATA_MAX_FE, be.getMaxEnergyStored())
        data.set(DATA_HAS_ACTIVE_RUN, if (be.isRunActive()) 1 else 0)
        data.set(DATA_BASE_TYPE_ORDINAL, -1) // Legacy field, no longer used

        // Sync modified FE stats
        val regenRate = be.getModifiedRegenRate()
        val baseDrain = be.getModifiedBaseDrain()
        val playerDrain = be.getModifiedPlayerDrain()

        data.set(DATA_REGEN_RATE, regenRate)
        data.set(DATA_BASE_DRAIN, baseDrain)
        data.set(DATA_PLAYER_DRAIN, playerDrain)
        data.set(DATA_COOLDOWN_REMAINING, be.getCooldownRemainingSeconds())

        // Debug logging
        if (!level.isClientSide && be.modifiers.isNotEmpty()) {
        }

        // Get drain multiplier from active run if available
        val multiplier = if (be.isRunActive() && !level.isClientSide) {
            val server = level.server
            if (server != null) {
                val runManager = RunManager.get(server)
                val runData = runManager.getRunByObelisk(be.obeliskId)
                runData?.drainMultiplier ?: 1.0
            } else {
                1.0
            }
        } else {
            1.0
        }
        data.set(DATA_DRAIN_MULTIPLIER_INT, (multiplier * 100).toInt())
    }
}
