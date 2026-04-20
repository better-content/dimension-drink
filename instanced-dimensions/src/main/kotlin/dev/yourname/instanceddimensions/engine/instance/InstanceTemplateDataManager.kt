package dev.yourname.instanceddimensions.engine.instance

import com.google.gson.GsonBuilder
import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.MOD_ID
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

object InstanceTemplateDataManager {
    private val logger = LogUtils.getLogger()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val configRoot: Path = FMLPaths.CONFIGDIR.get().resolve(MOD_ID)
    private val templatesDir: Path = configRoot.resolve("instance_templates")

    @Volatile private var loaded = false
    private var templates: Map<String, InstanceTemplate> = emptyMap()

    fun ensureLoaded() {
        if (loaded) return
        reload()
    }

    @Synchronized
    fun reload() {
        logger.info("Reloading instance templates from {}", templatesDir.toAbsolutePath())
        copyDefaultsIfMissing()
        templates = loadDirectory(templatesDir, InstanceTemplate::class.java)
            .mapNotNull(::normalizeTemplate)
            .associateBy { it.id }
        loaded = true
        logger.info("Loaded {} instance templates: {}", templates.size, templates.keys.sorted())
    }

    fun allTemplates(): Collection<InstanceTemplate> {
        ensureLoaded()
        return templates.values
    }

    fun templatesPath(): Path = templatesDir

    private fun normalizeTemplate(template: InstanceTemplate): InstanceTemplate? {
        val id = stringOrNull(template.id)
        if (id == null) {
            logger.warn("Ignoring instance template with missing id")
            return null
        }
        val stem = stringOrNull(template.stem)
        if (stem == null || ResourceLocation.tryParse(stem) == null) {
            logger.warn("Ignoring instance template {} with invalid stem {}", id, template.stem)
            return null
        }
        val requiredNamespace = stringOrNull(template.requiredNamespace)
        if (requiredNamespace != null && !namespaceAvailable(requiredNamespace)) {
            logger.info("Skipping instance template {} because required namespace {} is unavailable", id, requiredNamespace)
            return null
        }
        logger.info(
            "Accepted instance template {} stem={} requiredNamespace={} ephemeral={}",
            id,
            stem,
            requiredNamespace ?: "<none>",
            template.ephemeral
        )
        return template.copy(
            id = id,
            stem = stem,
            requiredNamespace = requiredNamespace,
            description = stringOrNull(template.description) ?: ""
        )
    }

    private fun stringOrNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun namespaceAvailable(namespace: String): Boolean {
        return namespace in ALWAYS_AVAILABLE_NAMESPACES || ModList.get().isLoaded(namespace)
    }

    private fun copyDefaultsIfMissing() {
        templatesDir.createDirectories()
        logger.info("Ensuring default instance templates exist in {}", templatesDir.toAbsolutePath())
        copyDefaultFolder("defaults/instance_templates", templatesDir)
    }

    private fun copyDefaultFolder(resourceFolder: String, targetDir: Path) {
        val listingStream = javaClass.classLoader.getResourceAsStream("$resourceFolder/.index") ?: return
        listingStream.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() }
                .forEach { fileName ->
                    val target = targetDir.resolve(fileName)
                    if (target.exists()) return@forEach
                    val resource = javaClass.classLoader.getResourceAsStream("$resourceFolder/$fileName") ?: return@forEach
                    resource.use { input ->
                        logger.info("Copying bundled instance template {} to {}", fileName, target.toAbsolutePath())
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
        }
    }

    private fun <T> loadDirectory(dir: Path, type: Class<T>): List<T> {
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { path -> path.name.endsWith(".json") }
                .sorted()
                .map { path ->
                    logger.info("Reading instance template file {}", path.toAbsolutePath())
                    path.inputStream().use { input ->
                        gson.fromJson(input.reader(), type)
                    }
                }
                .toList()
        }
    }

    private val ALWAYS_AVAILABLE_NAMESPACES = setOf("minecraft", "forge", MOD_ID)
}
