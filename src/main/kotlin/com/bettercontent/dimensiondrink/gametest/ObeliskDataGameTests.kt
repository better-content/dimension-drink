package com.bettercontent.dimensiondrink.gametest

import com.bettercontent.dimensiondrink.worldgen.pickCourtPotBlock
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.trade.DimensionalFontMapListing
import com.bettercontent.dimensiondrink.trade.DimensionalFontMapTrades
import com.bettercontent.dimensiondrink.trade.FontLocationSavedData
import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

@PrefixGameTestTemplate(false)
class ObeliskDataGameTests {
    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun naturally_generated_fonts_are_indexed_and_removed_with_the_block(helper: GameTestHelper) {
        val pos = helper.absolutePos(BlockPos(2, 2, 2))
        helper.level.setBlockAndUpdate(pos, ModBlocks.OBELISK.get().defaultBlockState())
        val obelisk = helper.level.getBlockEntity(pos) as ObeliskBlockEntity
        obelisk.initializeGeneratedFont("nether", 15_000.0)
        val data = FontLocationSavedData.get(helper.level.server)
        helper.assertTrue(data.snapshot().any { it.pos == pos && it.definitionId == "nether" }, "Expected generated Font in discovery index")

        helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
        helper.assertTrue(data.snapshot().none { it.pos == pos }, "Expected removed generated Font to leave discovery index")
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun player_or_debug_fonts_are_not_indexed(helper: GameTestHelper) {
        val pos = helper.absolutePos(BlockPos(4, 2, 4))
        helper.level.setBlockAndUpdate(pos, ModBlocks.OBELISK.get().defaultBlockState())
        val obelisk = helper.level.getBlockEntity(pos) as ObeliskBlockEntity
        obelisk.setDefinition("nether")
        helper.assertTrue(
            FontLocationSavedData.get(helper.level.server).snapshot().none { it.pos == pos },
            "Expected an ordinary placed/debug Font to remain outside the natural discovery index"
        )
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun font_map_listing_uses_an_indexed_destination(helper: GameTestHelper) {
        val pos = helper.absolutePos(BlockPos(6, 2, 6))
        helper.level.setBlockAndUpdate(pos, ModBlocks.OBELISK.get().defaultBlockState())
        (helper.level.getBlockEntity(pos) as ObeliskBlockEntity).initializeGeneratedFont("nether", 15_000.0)
        val map = DimensionalFontMapListing(0).nextMap(helper.level, pos.offset(16, 0, 16), emptySet())
        helper.assertTrue(map?.tag?.getString(DimensionalFontMapListing.DEFINITION_TAG) == "nether", "Expected indexed Nether Font map")
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun default_font_index_matches_bundled_definitions(helper: GameTestHelper) {
        val indexed = indexedJsonNames("defaults/fonts")
        val bundled = bundledJsonNames("defaults/fonts")
        helper.assertTrue(
            indexed == bundled,
            "Expected defaults/fonts/.index to match bundled definitions; index=$indexed bundled=$bundled"
        )
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 400)
    fun reward_tables_follow_definition_with_shared_target_dimension(helper: GameTestHelper) {
        ObeliskGameTestSupport.rewardTablesFollowDefinitionWithSharedTemplate(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_command_refreshes_definition_data(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadCommandRefreshesDefinitionData(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data_worldgen", timeoutTicks = 200)
    fun worldgen_definitions_produce_font_altar_sites(helper: GameTestHelper) {
        ObeliskGameTestSupport.worldgenDefinitionsProduceFontAltarSites(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data_structure_piece", timeoutTicks = 500)
    fun structure_piece_worldgen_produces_complete_font_altar_sites(helper: GameTestHelper) {
        ObeliskGameTestSupport.structurePieceWorldgenProducesCompleteFontAltarSites(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data_literal_font", timeoutTicks = 300)
    fun structure_piece_worldgen_places_literal_dimensional_font(helper: GameTestHelper) {
        ObeliskGameTestSupport.structurePieceWorldgenPlacesLiteralDimensionalFont(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data_overworld_presence", timeoutTicks = 1600)
    fun generated_overworld_chunks_produce_cultivation_centers(helper: GameTestHelper) {
        ObeliskGameTestSupport.generatedOverworldChunksProduceCultivationCenters(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun underwater_worldgen_does_not_place_fonts(helper: GameTestHelper) {
        ObeliskGameTestSupport.underwaterWorldgenDoesNotPlaceFonts(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_skips_definitions_with_missing_required_namespace(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadSkipsDefinitionsWithMissingRequiredNamespace(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun reload_keeps_definitions_and_defers_target_validation(helper: GameTestHelper) {
        ObeliskGameTestSupport.reloadSkipsDefinitionsWithMissingInstanceTemplate(helper)
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 120)
    fun court_pots_resolve_available_plants_or_safe_fallback(helper: GameTestHelper) {
        val samples = listOf(BlockPos(1, 64, 1), BlockPos(4, 64, -3), BlockPos(-6, 70, 2))
        samples.forEach { pos ->
            val block = pickCourtPotBlock(false, pos)
            val id = BuiltInRegistries.BLOCK.getKey(block)
            val configuredId = com.bettercontent.dimensiondrink.worldgen.pickCourtPotBlockId(false, pos)
            val available = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation(configuredId))
            if (available == Blocks.AIR) {
                helper.assertTrue(block == Blocks.POTTED_DEAD_BUSH, "Expected safe fallback when optional modded plants are absent")
            } else {
                helper.assertTrue(block == available && id.namespace != "minecraft", "Expected available modded pot $configuredId, found $id")
            }
            helper.assertTrue(id.path.startsWith("potted_"), "Expected non-dry court pot at $pos to resolve to a potted block, found $id")
        }
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 120)
    fun court_pots_use_dead_bushes_in_dry_biomes(helper: GameTestHelper) {
        val block = pickCourtPotBlock(true, BlockPos(8, 64, -5))
        helper.assertTrue(block == Blocks.POTTED_DEAD_BUSH, "Expected dry-biome court pot selection to use potted dead bushes")
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun font_maps_use_the_wandering_trader_listing(helper: GameTestHelper) {
        val listing = DimensionalFontMapTrades.wanderingTraderListing(0)
        helper.assertTrue(
            listing is DimensionalFontMapListing,
            "Expected the first-class wandering trader integration to expose a dimensional font map listing"
        )
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun font_map_offer_identifies_its_destination(helper: GameTestHelper) {
        val definition = ObeliskDataManager.getObelisk("nether")
            ?: error("Missing bundled nether dimensional font definition")
        val offer = DimensionalFontMapListing.createOffer(
            helper.level,
            helper.absolutePos(BlockPos(1, 2, 1)),
            definition,
            6,
            Items.EMERALD
        )
        val result = offer.result

        helper.assertTrue(offer.baseCostA.`is`(Items.EMERALD) && offer.baseCostA.count == 8, "Expected an eight-unit map price")
        helper.assertTrue(offer.costB.isEmpty, "Expected no secondary map cost")
        helper.assertTrue(offer.maxUses == 8 && offer.xp == 6, "Expected authored map uses and villager XP")
        helper.assertTrue(offer.priceMultiplier == 0.0f, "Expected authored pricing not to scale with demand")
        helper.assertTrue(result.`is`(Items.FILLED_MAP), "Expected a vanilla filled map result")
        helper.assertTrue(result.hoverName.string == "${definition.displayName} Map", "Expected the font type in the map name")
        helper.assertTrue(
            result.tag?.getString(DimensionalFontMapListing.DEFINITION_TAG) == definition.id,
            "Expected the map to retain its font definition id"
        )
        helper.assertTrue(
            result.getTagElement("display")?.getList("Lore", 8)?.getString(0)?.contains(definition.displayName) == true,
            "Expected the font type in the map tooltip"
        )
        helper.assertTrue(
            result.tag?.getList("Decorations", 10)?.isEmpty() == false,
            "Expected a target decoration on the dimensional font map"
        )

        val nextDefinition = ObeliskDataManager.getObelisk("end")
            ?: error("Missing bundled end dimensional font definition")
        val nextMap = DimensionalFontMapListing.createMap(
            helper.level,
            helper.absolutePos(BlockPos(3, 2, 3)),
            nextDefinition
        )
        offer.increaseUses()
        DimensionalFontMapTrades.replaceOfferResult(offer, nextMap)
        helper.assertTrue(offer.uses == 1, "Expected rotation to preserve offer usage state")
        helper.assertTrue(offer.baseCostA.`is`(Items.EMERALD) && offer.baseCostA.count == 8, "Expected rotation to preserve map price")
        helper.assertTrue(
            offer.result.tag?.getString(DimensionalFontMapListing.DEFINITION_TAG) == nextDefinition.id,
            "Expected rotation to replace the offered destination definition"
        )
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimension_drink", template = "bootstrap/empty", batch = "obelisk_data", timeoutTicks = 200)
    fun font_map_rotation_prioritizes_unsold_types_and_cycles(helper: GameTestHelper) {
        val eligible = setOf("nether", "bumblezone", "ratlantis")
        val first = DimensionalFontMapTrades.advanceSoldTypes(emptySet(), "nether", eligible)
        val second = DimensionalFontMapTrades.advanceSoldTypes(first, "bumblezone", eligible)
        val completed = DimensionalFontMapTrades.advanceSoldTypes(second, "ratlantis", eligible)
        helper.assertTrue(first == setOf("nether"), "Expected the first sold type to be retained")
        helper.assertTrue(second == setOf("nether", "bumblezone"), "Expected distinct sold types to accumulate")
        helper.assertTrue(completed.isEmpty(), "Expected type history to reset after a complete cycle")
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
