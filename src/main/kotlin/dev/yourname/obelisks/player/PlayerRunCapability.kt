package dev.yourname.obelisks.player

import dev.yourname.obelisks.MOD_ID
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraftforge.common.capabilities.*
import net.minecraftforge.common.util.INBTSerializable
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.event.AttachCapabilitiesEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Capability for tracking player run state.
 */
class PlayerRunCapabilityProvider : ICapabilitySerializable<CompoundTag> {

    private val runInfo = PlayerRunInfo()
    private val optional = LazyOptional.of { runInfo }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        return if (cap == PLAYER_RUN_CAPABILITY) {
            optional.cast()
        } else {
            LazyOptional.empty()
        }
    }

    override fun serializeNBT(): CompoundTag {
        return runInfo.toNbt()
    }

    override fun deserializeNBT(nbt: CompoundTag) {
        val loaded = PlayerRunInfo.fromNbt(nbt)
        runInfo.originObeliskId = loaded.originObeliskId
        runInfo.originPos = loaded.originPos
        runInfo.originDimension = loaded.originDimension
        runInfo.runId = loaded.runId
        runInfo.runDimensionKey = loaded.runDimensionKey
    }

    companion object {
        val PLAYER_RUN_CAPABILITY: Capability<PlayerRunInfo> = CapabilityManager.get(object : CapabilityToken<PlayerRunInfo>() {})

        private val CAPABILITY_ID = ResourceLocation(MOD_ID, "player_run_info")

        @SubscribeEvent
        fun onAttachCapabilities(event: AttachCapabilitiesEvent<Entity>) {
            if (event.`object` is Player) {
                event.addCapability(CAPABILITY_ID, PlayerRunCapabilityProvider())
            }
        }

        @SubscribeEvent
        fun onPlayerClone(event: PlayerEvent.Clone) {
            // Copy capability data from old player to new player (on respawn/return from End)
            if (!event.isWasDeath) {
                event.original.getCapability(PLAYER_RUN_CAPABILITY).ifPresent { oldData ->
                    event.entity.getCapability(PLAYER_RUN_CAPABILITY).ifPresent { newData ->
                        newData.originObeliskId = oldData.originObeliskId
                        newData.originPos = oldData.originPos
                        newData.originDimension = oldData.originDimension
                        newData.runId = oldData.runId
                        newData.runDimensionKey = oldData.runDimensionKey
                    }
                }
            }
        }

        /**
         * Helper method to get PlayerRunInfo from a player.
         */
        fun getRunInfo(player: Player): PlayerRunInfo? {
            return player.getCapability(PLAYER_RUN_CAPABILITY).resolve().orElse(null)
        }
    }
}

/**
 * Extension function for easy access to player run info.
 */
fun Player.getRunInfo(): PlayerRunInfo? {
    return this.getCapability(PlayerRunCapabilityProvider.PLAYER_RUN_CAPABILITY)
        .resolve()
        .orElse(null)
}
