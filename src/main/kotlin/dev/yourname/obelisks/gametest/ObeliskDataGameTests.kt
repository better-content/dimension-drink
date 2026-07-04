package dev.yourname.obelisks.gametest

import dev.yourname.obelisks.worldgen.pickCourtPotBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

@PrefixGameTestTemplate(false)
class ObeliskDataGameTests {
    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun default_font_index_matches_bundled_definitions(helper: GameTestHelper) {
        val indexed = indexedJsonNames("defaults/fonts")
        val bundled = bundledJsonNames("defaults/fonts")
        helper.assertTrue(
            indexed == bundled,
            "Expected defaults/fonts/.index to match bundled definitions; index=$indexed bundled=$bundled"
        )
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 400)
    fun reward_tables_follow_definition_with_shared_target_dimension(helper: GameTestHelper) {
        ObeliskGameTestSupport.rewardTablesFollowDefinitionWithSharedTemplate(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_command_refreshes_definition_data(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadCommandRefreshesDefinitionData(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data_worldgen", timeoutTicks = 200)
    fun worldgen_definitions_produce_font_altar_sites(helper: GameTestHelper) {
        ObeliskGameTestSupport.worldgenDefinitionsProduceFontAltarSites(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data_worldgen", timeoutTicks = 200)
    fun near_build_limit_worldgen_terrain_does_not_place_fonts(helper: GameTestHelper) {
        ObeliskGameTestSupport.nearBuildLimitWorldgenTerrainDoesNotPlaceFonts(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data_structure_piece", timeoutTicks = 500)
    fun structure_piece_worldgen_produces_complete_font_altar_sites(helper: GameTestHelper) {
        ObeliskGameTestSupport.structurePieceWorldgenProducesCompleteFontAltarSites(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data_overworld_presence", timeoutTicks = 1600)
    fun generated_overworld_chunks_produce_cultivation_centers(helper: GameTestHelper) {
        ObeliskGameTestSupport.generatedOverworldChunksProduceCultivationCenters(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun underwater_worldgen_does_not_place_fonts(helper: GameTestHelper) {
        ObeliskGameTestSupport.underwaterWorldgenDoesNotPlaceFonts(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_skips_definitions_with_missing_required_namespace(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadSkipsDefinitionsWithMissingRequiredNamespace(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_keeps_definitions_and_defers_target_validation(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadSkipsDefinitionsWithMissingInstanceTemplate(helper)
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 120)
    fun court_pots_use_modded_plants_outside_dry_biomes(helper: GameTestHelper) {
        val samples = listOf(BlockPos(1, 64, 1), BlockPos(4, 64, -3), BlockPos(-6, 70, 2))
        samples.forEach { pos ->
            val block = pickCourtPotBlock(false, pos)
            val id = BuiltInRegistries.BLOCK.getKey(block)
            helper.assertTrue(block != Blocks.POTTED_DEAD_BUSH, "Expected non-dry court pot at $pos not to resolve to dead bush")
            helper.assertTrue(id.namespace != "minecraft", "Expected non-dry court pot at $pos to use a modded plant, found $id")
            helper.assertTrue(id.path.startsWith("potted_"), "Expected non-dry court pot at $pos to resolve to a potted block, found $id")
        }
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 120)
    fun court_pots_use_dead_bushes_in_dry_biomes(helper: GameTestHelper) {
        val block = pickCourtPotBlock(true, BlockPos(8, 64, -5))
        helper.assertTrue(block == Blocks.POTTED_DEAD_BUSH, "Expected dry-biome court pot selection to use potted dead bushes")
        helper.succeed()
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
