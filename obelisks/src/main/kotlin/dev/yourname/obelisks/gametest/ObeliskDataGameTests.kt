package dev.yourname.obelisks.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

@PrefixGameTestTemplate(false)
class ObeliskDataGameTests {
    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun default_obelisk_index_matches_bundled_definitions(helper: GameTestHelper) {
        val indexed = indexedJsonNames("defaults/obelisks")
        val bundled = bundledJsonNames("defaults/obelisks")
        helper.assertTrue(
            indexed == bundled,
            "Expected defaults/obelisks/.index to match bundled definitions; index=$indexed bundled=$bundled"
        )
        helper.succeed()
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 400)
    fun reward_tables_follow_definition_with_shared_target_dimension(helper: GameTestHelper) {
        ObeliskGameTestSupport.rewardTablesFollowDefinitionWithSharedTemplate(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_command_refreshes_definition_data(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadCommandRefreshesDefinitionData(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun worldgen_definitions_produce_canonical_meteor_sites(helper: GameTestHelper) {
        ObeliskGameTestSupport.worldgenDefinitionsProduceCanonicalMeteorSites(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_skips_definitions_with_missing_required_namespace(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadSkipsDefinitionsWithMissingRequiredNamespace(helper)
    }

    @GameTest(templateNamespace = "obelisks", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_keeps_definitions_and_defers_target_validation(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadSkipsDefinitionsWithMissingInstanceTemplate(helper)
    }

    private fun indexedJsonNames(resourceFolder: String): List<String> {
        val indexStream = javaClass.classLoader.getResourceAsStream("$resourceFolder/.index")
            ?: error("Missing resource index for $resourceFolder")
        return indexStream.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() }
                .sorted()
                .toList()
        }
    }

    private fun bundledJsonNames(resourceFolder: String): List<String> {
        val resourceUri = requireNotNull(javaClass.classLoader.getResource(resourceFolder)) {
            "Missing resource folder $resourceFolder"
        }.toURI()
        return openResourceFolder(resourceUri, resourceFolder).use { resourcePath ->
            Files.list(resourcePath.path).use { stream ->
                stream.filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".json") }
                    .map { entry -> entry.fileName.toString() }
                    .sorted()
                    .toList()
            }
        }
    }

    private fun openResourceFolder(resourceUri: java.net.URI, resourceFolder: String): AutoCloseablePath {
        if (resourceUri.scheme == "jar") {
            val fileSystem = FileSystems.newFileSystem(resourceUri, emptyMap<String, Any>())
            return AutoCloseablePath(fileSystem.getPath("/$resourceFolder")) { fileSystem.close() }
        }
        return AutoCloseablePath(Path.of(resourceUri)) { }
    }

    private class AutoCloseablePath(val path: Path, private val closeAction: () -> Unit) : AutoCloseable {
        override fun close() = closeAction()
    }
}
