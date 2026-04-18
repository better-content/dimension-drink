package dev.yourname.instanceddimensions.engine.levelsync

import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

data class RuntimeLevelKeysPacket(
    val additions: List<ResourceKey<Level>>,
    val removals: List<ResourceKey<Level>>
) {

    fun encode(buffer: FriendlyByteBuf) {
        buffer.writeLevelKeys(additions)
        buffer.writeLevelKeys(removals)
    }

    companion object {
        fun decode(buffer: FriendlyByteBuf): RuntimeLevelKeysPacket {
            return RuntimeLevelKeysPacket(
                additions = buffer.readLevelKeys(),
                removals = buffer.readLevelKeys()
            )
        }

        fun handle(packet: RuntimeLevelKeysPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        val connection = Minecraft.getInstance().connection ?: return@Runnable
                        connection.levels().removeAll(packet.removals.toSet())
                        connection.levels().addAll(packet.additions)
                    }
                }
            }
            context.packetHandled = true
        }

        private fun FriendlyByteBuf.readLevelKeys(): List<ResourceKey<Level>> {
            val size = readVarInt()
            return ArrayList<ResourceKey<Level>>(size).also { keys ->
                repeat(size) {
                    keys += readResourceKey(net.minecraft.core.registries.Registries.DIMENSION)
                }
            }
        }

        private fun FriendlyByteBuf.writeLevelKeys(keys: Collection<ResourceKey<Level>>) {
            writeVarInt(keys.size)
            keys.forEach { writeResourceKey(it) }
        }
    }
}
