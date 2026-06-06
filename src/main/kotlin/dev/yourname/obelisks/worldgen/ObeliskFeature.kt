package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration

class ObeliskFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    companion object {
        private const val FOOTPRINT_RADIUS = 1
        private const val GRAVEYARD_RADIUS = 4

        fun generateDefinitionSiteForTests(
            level: ServerLevel,
            center: BlockPos,
            definitionId: String,
            random: RandomSource
        ): Boolean {
            val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
            val placementCenter = findPlacementCenter(level, center) ?: return false
            buildSiteBlocks(level, level::setBlock, placementCenter, random)
            val fontPos = placementCenter.above()
            if (!level.setBlock(fontPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)) return false
            val font = level.getBlockEntity(fontPos) as? ObeliskBlockEntity ?: return false
            font.setDefinition(definition.id)
            font.fillToCapacity()
            font.syncToClients()
            return true
        }

        private fun findPlacementCenter(level: LevelAccessor, origin: BlockPos): BlockPos? {
            val top = level.maxBuildHeight - 2
            val bottom = level.minBuildHeight + 1
            for (y in top downTo bottom) {
                val center = BlockPos(origin.x, y, origin.z)
                if (canPlace(level, center)) return center
            }
            return null
        }

        private fun canPlace(level: LevelAccessor, center: BlockPos): Boolean {
            for (x in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
                for (z in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
                    val surface = center.offset(x, -1, z)
                    val above = center.offset(x, 0, z)
                    val state = level.getBlockState(surface)
                    val aboveState = level.getBlockState(above)
                    if (state.isAir || !state.fluidState.isEmpty || isLeafLike(state) || state.`is`(Blocks.ICE)) return false
                    if (!state.isFaceSturdy(level, surface, Direction.UP)) return false
                    if (!aboveState.fluidState.isEmpty || (!aboveState.canBeReplaced() && !isLeafLike(aboveState))) return false
                }
            }
            return true
        }

        private fun buildSiteBlocks(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            random: RandomSource
        ) {
            buildGraveyardBlocks(level, setBlock, center, random)
            buildAltarBlocks(setBlock, center)
        }

        private fun buildAltarBlocks(setBlock: (BlockPos, BlockState, Int) -> Boolean, center: BlockPos) {
            val baseY = center.y
            for (x in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
                for (z in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
                    val block = if (kotlin.math.abs(x) == FOOTPRINT_RADIUS && kotlin.math.abs(z) == FOOTPRINT_RADIUS) {
                        Blocks.COPPER_BLOCK
                    } else {
                        Blocks.POLISHED_ANDESITE
                    }
                    setBlock(BlockPos(center.x + x, baseY, center.z + z), block.defaultBlockState(), 3)
                }
            }
            for (y in 1..3) {
                setBlock(center.above(y), Blocks.AIR.defaultBlockState(), 3)
            }
        }

        private fun buildGraveyardBlocks(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            random: RandomSource
        ) {
            buildWeatheredFloor(level, setBlock, center, random)
            buildPerimeterWall(level, setBlock, center, random)
            buildEntranceArch(level, setBlock, center)
            buildCornerShrines(level, setBlock, center, random)

            val graveSlots = listOf(
                GraveSlot(-3, -2),
                GraveSlot(-3, 2),
                GraveSlot(3, -2),
                GraveSlot(3, 2),
                GraveSlot(-2, -3),
                GraveSlot(2, -3),
                GraveSlot(-2, 3),
                GraveSlot(2, 3)
            )
            graveSlots.shuffled(random).take(5 + random.nextInt(2)).forEach { slot ->
                buildGrave(level, setBlock, center.offset(slot.x, 0, slot.z), slot, random)
            }

            buildAtmosphereDetails(level, setBlock, center, random)
        }

        private fun buildWeatheredFloor(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            random: RandomSource
        ) {
            for (x in -GRAVEYARD_RADIUS..GRAVEYARD_RADIUS) {
                for (z in -GRAVEYARD_RADIUS..GRAVEYARD_RADIUS) {
                    if (kotlin.math.abs(x) <= FOOTPRINT_RADIUS && kotlin.math.abs(z) <= FOOTPRINT_RADIUS) continue
                    val pos = center.offset(x, 0, z)
                    if (!canPlaceDecoration(level, pos)) continue
                    val distance = kotlin.math.abs(x) + kotlin.math.abs(z)
                    val block = when {
                        x == 0 || z == 0 -> weightedBlock(
                            random,
                            Blocks.GRAVEL,
                            Blocks.COARSE_DIRT,
                            Blocks.MOSSY_COBBLESTONE,
                            Blocks.CRACKED_STONE_BRICKS
                        )
                        distance <= 5 && random.nextInt(3) == 0 -> weightedBlock(
                            random,
                            Blocks.MOSSY_COBBLESTONE,
                            Blocks.STONE_BRICKS,
                            Blocks.CRACKED_STONE_BRICKS,
                            Blocks.GRAVEL
                        )
                        random.nextInt(6) == 0 -> if (random.nextBoolean()) Blocks.COARSE_DIRT else Blocks.GRAVEL
                        else -> null
                    }
                    if (block != null) setBlock(pos, block.defaultBlockState(), 3)
                }
            }
        }

        private fun buildPerimeterWall(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            random: RandomSource
        ) {
            for (x in -GRAVEYARD_RADIUS..GRAVEYARD_RADIUS) {
                for (z in -GRAVEYARD_RADIUS..GRAVEYARD_RADIUS) {
                    if (kotlin.math.abs(x) != GRAVEYARD_RADIUS && kotlin.math.abs(z) != GRAVEYARD_RADIUS) continue
                    if (isGateGap(x, z)) continue
                    val pos = center.offset(x, 0, z)
                    if (!canPlaceStructureBase(level, pos)) continue
                    val wall = weightedBlock(
                        random,
                        Blocks.COBBLESTONE_WALL,
                        Blocks.MOSSY_COBBLESTONE_WALL,
                        Blocks.COBBLESTONE_WALL,
                        Blocks.MOSSY_COBBLESTONE_WALL
                    )
                    setBlock(pos, wall.defaultBlockState(), 3)
                    if ((kotlin.math.abs(x) == GRAVEYARD_RADIUS && kotlin.math.abs(z) == GRAVEYARD_RADIUS) || random.nextInt(7) == 0) {
                        placeIfClear(level, setBlock, pos.above(), if (random.nextBoolean()) Blocks.MOSSY_STONE_BRICKS else Blocks.CRACKED_STONE_BRICKS)
                    }
                }
            }
        }

        private fun buildGrave(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            slot: GraveSlot,
            random: RandomSource
        ) {
            val slab = if (random.nextBoolean()) Blocks.STONE_BRICKS else Blocks.MOSSY_COBBLESTONE
            val head = if (random.nextBoolean()) Blocks.CHISELED_STONE_BRICKS else Blocks.COBBLESTONE_WALL
            val dx = -slot.x.coerceIn(-1, 1)
            val dz = -slot.z.coerceIn(-1, 1)
            val foot = base.offset(dx, 0, dz)
            if (!canPlaceStructureBase(level, base) || !canPlaceStructureBase(level, foot) || !canReplaceDecoration(level, base.above())) return
            setBlock(base, weightedBlock(random, slab, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.CRACKED_STONE_BRICKS).defaultBlockState(), 3)
            setBlock(foot, weightedBlock(random, slab, Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.MOSSY_COBBLESTONE).defaultBlockState(), 3)
            setBlock(base.above(), head.defaultBlockState(), 3)
            placeIfClear(level, setBlock, foot.above(), if (random.nextInt(3) == 0) Blocks.WITHER_ROSE else Blocks.RED_CANDLE)
        }

        private fun buildEntranceArch(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, center: BlockPos) {
            val northLeft = center.offset(-2, 0, -GRAVEYARD_RADIUS)
            val northRight = center.offset(2, 0, -GRAVEYARD_RADIUS)
            val southLeft = center.offset(-2, 0, GRAVEYARD_RADIUS)
            val southRight = center.offset(2, 0, GRAVEYARD_RADIUS)
            listOf(northLeft, northRight, southLeft, southRight).forEach { base ->
                if (canPlaceStructureBase(level, base)) {
                    setBlock(base, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3)
                    placeIfClear(level, setBlock, base.above(), Blocks.CHISELED_STONE_BRICKS)
                    placeIfClear(level, setBlock, base.above(2), Blocks.COBBLESTONE_WALL)
                }
            }
            for (x in -1..1) {
                placeIfClear(level, setBlock, center.offset(x, 2, -GRAVEYARD_RADIUS), Blocks.MOSSY_COBBLESTONE_WALL)
                placeIfClear(level, setBlock, center.offset(x, 2, GRAVEYARD_RADIUS), Blocks.MOSSY_COBBLESTONE_WALL)
            }
            placeIfClear(level, setBlock, center.offset(0, 1, -GRAVEYARD_RADIUS + 1), Blocks.SOUL_LANTERN)
            placeIfClear(level, setBlock, center.offset(0, 1, GRAVEYARD_RADIUS - 1), Blocks.SOUL_LANTERN)
        }

        private fun buildCornerShrines(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            random: RandomSource
        ) {
            listOf(
                center.offset(-GRAVEYARD_RADIUS, 0, -GRAVEYARD_RADIUS),
                center.offset(-GRAVEYARD_RADIUS, 0, GRAVEYARD_RADIUS),
                center.offset(GRAVEYARD_RADIUS, 0, -GRAVEYARD_RADIUS),
                center.offset(GRAVEYARD_RADIUS, 0, GRAVEYARD_RADIUS)
            ).forEach { corner ->
                placeIfClear(level, setBlock, corner.above(), if (random.nextBoolean()) Blocks.CHISELED_STONE_BRICKS else Blocks.MOSSY_STONE_BRICKS)
                placeIfClear(level, setBlock, corner.above(2), if (random.nextBoolean()) Blocks.SOUL_LANTERN else Blocks.SKELETON_SKULL)
            }
        }

        private fun buildAtmosphereDetails(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            random: RandomSource
        ) {
            val accentSlots = listOf(
                center.offset(-2, 0, -1),
                center.offset(2, 0, -1),
                center.offset(-2, 0, 1),
                center.offset(2, 0, 1),
                center.offset(-1, 0, -2),
                center.offset(1, 0, -2),
                center.offset(-1, 0, 2),
                center.offset(1, 0, 2)
            )
            accentSlots.shuffled(random).take(4).forEach { pos ->
                val block = weightedBlock(random, Blocks.RED_CANDLE, Blocks.SOUL_LANTERN, Blocks.WITHER_ROSE, Blocks.DEAD_BUSH)
                placeIfClear(level, setBlock, pos.above(), block)
            }
        }

        private fun canPlaceDecoration(level: LevelAccessor, pos: BlockPos): Boolean {
            val support = pos.below()
            val supportState = level.getBlockState(support)
            if (supportState.isAir || !supportState.fluidState.isEmpty || isLeafLike(supportState) || supportState.`is`(Blocks.ICE)) return false
            if (!supportState.isFaceSturdy(level, support, Direction.UP)) return false
            return canReplaceDecoration(level, pos)
        }

        private fun canReplaceDecoration(level: LevelAccessor, pos: BlockPos): Boolean {
            val state = level.getBlockState(pos)
            return state.fluidState.isEmpty && state.canBeReplaced()
        }

        private fun canPlaceStructureBase(level: LevelAccessor, pos: BlockPos): Boolean {
            val support = pos.below()
            val supportState = level.getBlockState(support)
            val state = level.getBlockState(pos)
            if (supportState.isAir || !supportState.fluidState.isEmpty || isLeafLike(supportState) || supportState.`is`(Blocks.ICE)) return false
            if (!supportState.isFaceSturdy(level, support, Direction.UP)) return false
            return state.fluidState.isEmpty && (
                state.canBeReplaced() ||
                    state.`is`(Blocks.COBBLESTONE_WALL) ||
                    state.`is`(Blocks.MOSSY_COBBLESTONE_WALL) ||
                    state.`is`(Blocks.GRAVEL) ||
                    state.`is`(Blocks.COARSE_DIRT) ||
                    state.`is`(Blocks.MOSSY_COBBLESTONE) ||
                    state.`is`(Blocks.STONE_BRICKS) ||
                    state.`is`(Blocks.CRACKED_STONE_BRICKS)
                )
        }

        private fun placeIfClear(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos, block: net.minecraft.world.level.block.Block): Boolean {
            if (!canReplaceDecoration(level, pos)) return false
            return setBlock(pos, block.defaultBlockState(), 3)
        }

        private fun weightedBlock(random: RandomSource, vararg blocks: net.minecraft.world.level.block.Block): net.minecraft.world.level.block.Block =
            blocks[random.nextInt(blocks.size)]

        private fun isLeafLike(state: BlockState): Boolean =
            state.`is`(BlockTags.LEAVES) || state.block is LeavesBlock

        private fun isGateGap(x: Int, z: Int): Boolean =
            (kotlin.math.abs(x) == GRAVEYARD_RADIUS && kotlin.math.abs(z) <= 1) ||
                (kotlin.math.abs(z) == GRAVEYARD_RADIUS && kotlin.math.abs(x) <= 1)

        private fun <T> List<T>.shuffled(random: RandomSource): List<T> {
            val copy = toMutableList()
            for (i in copy.lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val value = copy[i]
                copy[i] = copy[j]
                copy[j] = value
            }
            return copy
        }

        private data class GraveSlot(val x: Int, val z: Int)
    }

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        if (level.level.dimension() != Level.OVERWORLD) return false

        val definition = ObeliskDataManager.pickRandomObelisk() ?: return false
        val origin = context.origin()
        val center = findPlacementCenter(level, origin) ?: return false

        buildSite(level, center, context.random())
        val fontPos = center.above()
        if (!level.setBlock(fontPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)) return false
        val font = level.getBlockEntity(fontPos) as? ObeliskBlockEntity ?: return false
        font.setDefinition(definition.id)
        font.fillToCapacity()
        font.syncToClients()
        return true
    }

    private fun buildSite(level: WorldGenLevel, center: BlockPos, random: RandomSource) {
        buildSiteBlocks(level, level::setBlock, center, random)
    }
}
