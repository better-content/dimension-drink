package dev.yourname.obelisks.runtime.combat

import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.runtime.backend.SiteBounds
import dev.yourname.obelisks.runtime.run.RunRecord
import dev.yourname.obelisks.runtime.run.RunState
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.ForgeEventFactory

object RunMonsterSpawner {
    private const val TARGET_MONSTERS_PER_PLAYER = 20
    private const val RAMP_TO_FULL_CAP_TICKS = 10L * ObeliskConstants.TICKS_PER_SECOND.toLong()
    private const val SPAWN_INTERVAL_TICKS = 2L
    private const val MAX_SPAWNS_PER_TICK = 96
    private const val POSITION_ATTEMPTS_PER_MOB = 24
    private const val MIN_PLAYER_DISTANCE = 4
    private const val MAX_PLAYER_DISTANCE = 28
    private const val VERTICAL_SEARCH_ABOVE = 10
    private const val VERTICAL_SEARCH_BELOW = 18
    private val SPAWN_TYPE = MobSpawnType.SPAWNER

    fun tick(server: MinecraftServer, record: RunRecord, activePlayerCount: Int) {
        if (activePlayerCount <= 0 || record.state != RunState.ACTIVE) {
            return
        }

        val levelKey = record.backendLevelKey ?: return
        val level = server.getLevel(levelKey) ?: return
        if (level.difficulty == Difficulty.PEACEFUL) {
            return
        }

        val bounds = record.backendSiteBounds ?: return
        val players = record.activePlayers
            .mapNotNull(server.playerList::getPlayer)
            .filter { player -> player.serverLevel() == level && bounds.contains(player.blockPosition()) }
        if (players.isEmpty()) {
            return
        }

        if (record.ticksElapsed % SPAWN_INTERVAL_TICKS != 0L) {
            pulsePlayerAggro(level, players)
            return
        }

        val fullTargetCap = TARGET_MONSTERS_PER_PLAYER * activePlayerCount
        val rampProgress = ((record.ticksElapsed + 1L).coerceAtMost(RAMP_TO_FULL_CAP_TICKS)).toDouble() / RAMP_TO_FULL_CAP_TICKS.toDouble()
        val targetCap = Mth.ceil(fullTargetCap * rampProgress).coerceAtMost(fullTargetCap)
        val existing = countRunMonsters(level, bounds)
        val missing = (targetCap - existing).coerceAtMost(MAX_SPAWNS_PER_TICK)
        if (missing <= 0) {
            pulsePlayerAggro(level, players)
            return
        }

        var spawned = 0
        var attempts = 0
        val maxAttempts = missing * POSITION_ATTEMPTS_PER_MOB
        while (spawned < missing && attempts < maxAttempts) {
            attempts++
            val player = players[level.random.nextInt(players.size)]
            if (trySpawnNear(level, bounds, player)) {
                spawned++
            }
        }

        pulsePlayerAggro(level, players)
    }

    private fun trySpawnNear(level: ServerLevel, bounds: SiteBounds, player: ServerPlayer): Boolean {
        val random = level.random
        val dx = Mth.nextInt(random, -MAX_PLAYER_DISTANCE, MAX_PLAYER_DISTANCE)
        val dz = Mth.nextInt(random, -MAX_PLAYER_DISTANCE, MAX_PLAYER_DISTANCE)
        val horizontalDistanceSqr = dx * dx + dz * dz
        if (horizontalDistanceSqr < MIN_PLAYER_DISTANCE * MIN_PLAYER_DISTANCE ||
            horizontalDistanceSqr > MAX_PLAYER_DISTANCE * MAX_PLAYER_DISTANCE
        ) {
            return false
        }
        val x = player.blockX + dx
        val z = player.blockZ + dz
        val baseY = Mth.clamp(
            player.blockY + Mth.nextInt(random, -VERTICAL_SEARCH_BELOW / 2, VERTICAL_SEARCH_ABOVE / 2),
            level.minBuildHeight + 1,
            level.maxBuildHeight - 2
        )

        val seedPos = BlockPos(x, baseY, z)
        if (!bounds.contains(seedPos)) {
            return false
        }

        val baseSpawns = level.chunkSource.generator.getMobsAt(
            level.getBiome(seedPos),
            level.structureManager(),
            MobCategory.MONSTER,
            seedPos
        )
        val spawns = ForgeEventFactory.getPotentialSpawns(level, MobCategory.MONSTER, seedPos, baseSpawns)
        if (spawns.isEmpty) {
            return false
        }

        val data = spawns.getRandom(random).orElse(null) ?: return false
        if (data.type.category != MobCategory.MONSTER || !data.type.canSummon()) {
            return false
        }

        val spawnPos = findSpawnPos(level, bounds, data, seedPos) ?: return false
        val mob = data.type.create(level) as? Mob ?: return false
        mob.moveTo(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5, random.nextFloat() * 360.0f, 0.0f)
        if (!ForgeEventFactory.checkSpawnPosition(mob, level, SPAWN_TYPE)) {
            return false
        }

        ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(spawnPos), SPAWN_TYPE, null, null)
        level.addFreshEntityWithPassengers(mob)
        return true
    }

    private fun findSpawnPos(
        level: ServerLevel,
        bounds: SiteBounds,
        data: MobSpawnSettings.SpawnerData,
        seed: BlockPos
    ): BlockPos? {
        val minY = (seed.y - VERTICAL_SEARCH_BELOW).coerceAtLeast(level.minBuildHeight + 1)
        val maxY = (seed.y + VERTICAL_SEARCH_ABOVE).coerceAtMost(level.maxBuildHeight - 2)
        for (y in maxY downTo minY) {
            val pos = BlockPos(seed.x, y, seed.z)
            if (isValidSpawn(level, bounds, data, pos)) {
                return pos
            }
        }
        return null
    }

    private fun isValidSpawn(
        level: ServerLevel,
        bounds: SiteBounds,
        data: MobSpawnSettings.SpawnerData,
        pos: BlockPos
    ): Boolean {
        if (!bounds.contains(pos)) {
            return false
        }

        val placement = SpawnPlacements.getPlacementType(data.type)
        return NaturalSpawner.isSpawnPositionOk(placement, level, pos, data.type) &&
            level.noCollision(data.type.getAABB(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5))
    }

    private fun pulsePlayerAggro(level: ServerLevel, players: List<ServerPlayer>) {
        players.forEach { player ->
            val range = MobCategory.MONSTER.despawnDistance.toDouble().coerceAtMost(64.0)
            level.getEntitiesOfClass(Mob::class.java, player.boundingBox.inflate(range)) { mob ->
                mob.type.category == MobCategory.MONSTER &&
                    mob.isAlive &&
                    mob.target == null &&
                    mob.sensing.hasLineOfSight(player)
            }.forEach { mob ->
                mob.setLastHurtByPlayer(player)
            }
        }
    }

    private fun countRunMonsters(level: ServerLevel, bounds: SiteBounds): Int {
        return level.getEntitiesOfClass(Mob::class.java, bounds.toAabb()) { mob ->
            mob.type.category == MobCategory.MONSTER && mob.isAlive
        }.size
    }

    private fun SiteBounds.toAabb(): AABB = AABB(
        minX.toDouble(),
        minY.toDouble(),
        minZ.toDouble(),
        (maxX + 1).toDouble(),
        (maxY + 1).toDouble(),
        (maxZ + 1).toDouble()
    )
}
