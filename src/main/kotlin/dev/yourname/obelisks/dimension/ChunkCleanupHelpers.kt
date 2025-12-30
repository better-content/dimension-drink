package dev.yourname.obelisks.dimension

import net.minecraft.server.level.ServerLevel
import java.io.File
import java.nio.file.Path

/**
 * Helper utilities for chunk and dimension cleanup operations.
 */
object ChunkCleanupHelpers {

    /**
     * Gets the region folder path for a dimension.
     */
    fun getRegionFolderPath(level: ServerLevel): Path? {
        val server = level.server
        val dimName = level.dimension().location().toString()

        // Build dimension region folder path
        val worldPath = server.serverDirectory.toPath().resolve("saves").resolve(server.worldData.levelName)
        val dimensionFolderName = when (dimName) {
            "minecraft:overworld" -> {
                println("[Obelisks] WARNING: Cannot delete overworld dimension!")
                return null
            }
            "minecraft:the_nether" -> "DIM-1"
            "minecraft:the_end" -> "DIM1"
            else -> "dimensions/${level.dimension().location().namespace}/${level.dimension().location().path}"
        }

        return worldPath.resolve(dimensionFolderName).resolve("region")
    }

    /**
     * Finds all region files (.mca) in a folder.
     */
    fun findRegionFiles(regionFolder: File): List<File> {
        if (!regionFolder.exists() || !regionFolder.isDirectory) {
            println("[Obelisks] Region folder doesn't exist or is not a directory: ${regionFolder.absolutePath}")
            return emptyList()
        }

        val regionFiles = regionFolder.listFiles { file ->
            file.isFile && file.name.endsWith(".mca")
        }

        return regionFiles?.toList() ?: emptyList()
    }

    /**
     * Truncates and deletes a region file.
     * Returns true if successful.
     */
    fun deleteRegionFile(file: File): Boolean {
        return try {
            // Truncate file to 0 bytes instead of deleting
            // This releases the data but keeps the file handle valid
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
            }

            // Now try to delete the empty file
            file.delete() || true // Even if delete fails, file is now empty (0 bytes)
        } catch (e: Exception) {
            println("[Obelisks] Failed to clear region file ${file.name}: ${e.message}")
            false
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     * Returns the number of files/folders deleted.
     */
    fun deleteDirectoryRecursive(directory: File): Int {
        var deletedCount = 0

        if (!directory.exists()) {
            return 0
        }

        if (directory.isDirectory) {
            // Delete all children first
            val children = directory.listFiles()
            if (children != null) {
                for (child in children) {
                    deletedCount += deleteDirectoryRecursive(child)
                }
            }
        }

        // Delete the file/directory itself
        if (directory.delete()) {
            deletedCount++
        } else {
            println("[Obelisks] Failed to delete: ${directory.absolutePath}")
        }

        return deletedCount
    }

    /**
     * Deletes entity and POI (Point of Interest) data folders.
     */
    fun cleanupDimensionData(dimensionFolder: File) {
        try {
            // Delete entities folder
            val entitiesFolder = dimensionFolder.resolve("entities")
            if (entitiesFolder.exists()) {
                val entitiesDeleted = deleteDirectoryRecursive(entitiesFolder)
                println("[Obelisks] Deleted entities folder ($entitiesDeleted files)")
            }

            // Delete POI folder
            val poiFolder = dimensionFolder.resolve("poi")
            if (poiFolder.exists()) {
                val poiDeleted = deleteDirectoryRecursive(poiFolder)
                println("[Obelisks] Deleted POI folder ($poiDeleted files)")
            }

            // Delete DIM folder if it exists (some dimensions use this structure)
            val dimFolder = dimensionFolder.resolve("DIM")
            if (dimFolder.exists()) {
                val dimDeleted = deleteDirectoryRecursive(dimFolder)
                println("[Obelisks] Deleted DIM folder ($dimDeleted files)")
            }
        } catch (e: Exception) {
            println("[Obelisks] Error deleting entity/POI data: ${e.message}")
        }
    }
}
