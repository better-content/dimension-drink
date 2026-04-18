package dev.yourname.instanceddimensions.engine.instance

import com.mojang.serialization.Dynamic
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.border.WorldBorder
import java.util.UUID

data class InstanceLevelState(
    val levelName: String,
    val spawnX: Int,
    val spawnY: Int,
    val spawnZ: Int,
    val spawnAngle: Float,
    val gameTime: Long,
    val dayTime: Long,
    val clearWeatherTime: Int,
    val rainTime: Int,
    val thunderTime: Int,
    val raining: Boolean,
    val thundering: Boolean,
    val gameType: GameType,
    val initialized: Boolean,
    val allowCommands: Boolean,
    val hardcore: Boolean,
    val difficulty: Difficulty,
    val difficultyLocked: Boolean,
    val worldBorder: CompoundTag,
    val wanderingTraderSpawnDelay: Int,
    val wanderingTraderSpawnChance: Int,
    val wanderingTraderId: UUID?,
    val gameRules: CompoundTag,
    val scheduledEvents: ListTag
) {

    fun deepCopy(): InstanceLevelState = copy(
        worldBorder = worldBorder.copy(),
        gameRules = gameRules.copy(),
        scheduledEvents = scheduledEvents.copy()
    )

    fun worldBorderSettings(): WorldBorder.Settings {
        return WorldBorder.Settings.read(Dynamic(NbtOps.INSTANCE, worldBorder.copy()), WorldBorder.DEFAULT_SETTINGS)
    }

    companion object {
        fun createDefault(server: MinecraftServer, templateId: String, levelKey: ResourceLocation): InstanceLevelState {
            val overworld = server.overworld()
            val levelData = overworld.levelData
            val spawn = BlockPos(levelData.xSpawn, levelData.ySpawn, levelData.zSpawn)
            val borderTag = CompoundTag().apply {
                overworld.worldBorder.createSettings().write(this)
            }
            val nameSuffix = levelKey.path.substringAfterLast('/').take(8)

            return InstanceLevelState(
                levelName = "Instanced Dimensions $templateId [$nameSuffix]",
                spawnX = spawn.x,
                spawnY = spawn.y,
                spawnZ = spawn.z,
                spawnAngle = levelData.spawnAngle,
                gameTime = levelData.gameTime,
                dayTime = levelData.dayTime,
                clearWeatherTime = 0,
                rainTime = 0,
                thunderTime = 0,
                raining = false,
                thundering = false,
                gameType = server.defaultGameType,
                initialized = true,
                allowCommands = server.worldData.allowCommands,
                hardcore = server.worldData.isHardcore,
                difficulty = server.worldData.difficulty,
                difficultyLocked = server.worldData.isDifficultyLocked,
                worldBorder = borderTag,
                wanderingTraderSpawnDelay = 0,
                wanderingTraderSpawnChance = 0,
                wanderingTraderId = null,
                gameRules = overworld.gameRules.createTag(),
                scheduledEvents = ListTag()
            )
        }

        fun createDefaultPlaceholder(levelKey: ResourceLocation): InstanceLevelState {
            val borderTag = CompoundTag().apply {
                WorldBorder.DEFAULT_SETTINGS.write(this)
            }

            return InstanceLevelState(
                levelName = "Instanced Dimensions placeholder [${levelKey.path.substringAfterLast('/').take(8)}]",
                spawnX = 0,
                spawnY = 64,
                spawnZ = 0,
                spawnAngle = 0.0F,
                gameTime = 0L,
                dayTime = 0L,
                clearWeatherTime = 0,
                rainTime = 0,
                thunderTime = 0,
                raining = false,
                thundering = false,
                gameType = GameType.SURVIVAL,
                initialized = false,
                allowCommands = false,
                hardcore = false,
                difficulty = Difficulty.NORMAL,
                difficultyLocked = false,
                worldBorder = borderTag,
                wanderingTraderSpawnDelay = 0,
                wanderingTraderSpawnChance = 0,
                wanderingTraderId = null,
                gameRules = CompoundTag(),
                scheduledEvents = ListTag()
            )
        }

        fun fromTag(tag: CompoundTag): InstanceLevelState {
            return InstanceLevelState(
                levelName = tag.getString("level_name"),
                spawnX = tag.getInt("spawn_x"),
                spawnY = tag.getInt("spawn_y"),
                spawnZ = tag.getInt("spawn_z"),
                spawnAngle = tag.getFloat("spawn_angle"),
                gameTime = tag.getLong("game_time"),
                dayTime = tag.getLong("day_time"),
                clearWeatherTime = tag.getInt("clear_weather_time"),
                rainTime = tag.getInt("rain_time"),
                thunderTime = tag.getInt("thunder_time"),
                raining = tag.getBoolean("raining"),
                thundering = tag.getBoolean("thundering"),
                gameType = GameType.byId(tag.getInt("game_type")),
                initialized = tag.getBoolean("initialized"),
                allowCommands = tag.getBoolean("allow_commands"),
                hardcore = tag.getBoolean("hardcore"),
                difficulty = Difficulty.byId(tag.getByte("difficulty").toInt()),
                difficultyLocked = tag.getBoolean("difficulty_locked"),
                worldBorder = tag.getCompound("world_border"),
                wanderingTraderSpawnDelay = tag.getInt("wandering_trader_spawn_delay"),
                wanderingTraderSpawnChance = tag.getInt("wandering_trader_spawn_chance"),
                wanderingTraderId = parseUuid(tag.getString("wandering_trader_id")),
                gameRules = tag.getCompound("game_rules"),
                scheduledEvents = tag.getList("scheduled_events", CompoundTag.TAG_COMPOUND.toInt())
            )
        }

        private fun parseUuid(raw: String): UUID? {
            if (raw.isBlank()) {
                return null
            }
            return runCatching { UUID.fromString(raw) }.getOrNull()
        }
    }

    fun toTag(): CompoundTag = CompoundTag().apply {
        putString("level_name", levelName)
        putInt("spawn_x", spawnX)
        putInt("spawn_y", spawnY)
        putInt("spawn_z", spawnZ)
        putFloat("spawn_angle", spawnAngle)
        putLong("game_time", gameTime)
        putLong("day_time", dayTime)
        putInt("clear_weather_time", clearWeatherTime)
        putInt("rain_time", rainTime)
        putInt("thunder_time", thunderTime)
        putBoolean("raining", raining)
        putBoolean("thundering", thundering)
        putInt("game_type", gameType.id)
        putBoolean("initialized", initialized)
        putBoolean("allow_commands", allowCommands)
        putBoolean("hardcore", hardcore)
        putByte("difficulty", difficulty.id.toByte())
        putBoolean("difficulty_locked", difficultyLocked)
        put("world_border", worldBorder.copy())
        putInt("wandering_trader_spawn_delay", wanderingTraderSpawnDelay)
        putInt("wandering_trader_spawn_chance", wanderingTraderSpawnChance)
        wanderingTraderId?.let { putString("wandering_trader_id", it.toString()) }
        put("game_rules", gameRules.copy())
        put("scheduled_events", scheduledEvents.copy())
    }
}
