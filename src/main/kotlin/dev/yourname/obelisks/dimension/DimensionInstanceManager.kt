package dev.yourname.obelisks.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level

/**
 * Utility methods for dimension management.
 *
 * NOTE: This object previously managed dynamic dimension creation, but has been
 * simplified to use a slot-based system. All dynamic creation code has been removed.
 */
object DimensionInstanceManager {

    /**
     * Checks if a dimension is currently loaded.
     */
    fun isDimensionLoaded(server: MinecraftServer, dimensionKey: ResourceKey<Level>): Boolean {
        return server.getLevel(dimensionKey) != null
    }
}
