package dev.yourname.instanceddimensions.engine.instance

import com.mojang.serialization.Dynamic
import net.minecraft.CrashReportCategory
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.border.WorldBorder
import net.minecraft.world.level.storage.ServerLevelData
import net.minecraft.world.level.timers.TimerCallbacks
import net.minecraft.world.level.timers.TimerQueue
import java.util.UUID

class InstanceLevelData(
    initialState: InstanceLevelState
) : ServerLevelData {

    private var levelName: String = initialState.levelName
    private var spawnX: Int = initialState.spawnX
    private var spawnY: Int = initialState.spawnY
    private var spawnZ: Int = initialState.spawnZ
    private var spawnAngle: Float = initialState.spawnAngle
    private var gameTime: Long = initialState.gameTime
    private var dayTime: Long = initialState.dayTime
    private var clearWeatherTime: Int = initialState.clearWeatherTime
    private var rainTime: Int = initialState.rainTime
    private var thunderTime: Int = initialState.thunderTime
    private var raining: Boolean = initialState.raining
    private var thundering: Boolean = initialState.thundering
    private var gameType: GameType = initialState.gameType
    private var initialized: Boolean = initialState.initialized
    private var allowCommands: Boolean = initialState.allowCommands
    private var hardcore: Boolean = initialState.hardcore
    private var difficulty: Difficulty = initialState.difficulty
    private var difficultyLocked: Boolean = initialState.difficultyLocked
    private var worldBorder: WorldBorder.Settings = initialState.worldBorderSettings()
    private var wanderingTraderSpawnDelay: Int = initialState.wanderingTraderSpawnDelay
    private var wanderingTraderSpawnChance: Int = initialState.wanderingTraderSpawnChance
    private var wanderingTraderId: UUID? = initialState.wanderingTraderId
    private val gameRules: GameRules = GameRules(Dynamic(NbtOps.INSTANCE, initialState.gameRules.copy()))
    private val scheduledEvents: TimerQueue<MinecraftServer> = TimerQueue(
        TimerCallbacks.SERVER_CALLBACKS,
        initialState.scheduledEvents.copy().stream().map { Dynamic<Tag>(NbtOps.INSTANCE, it) }
    )

    override fun getXSpawn(): Int = spawnX
    override fun getYSpawn(): Int = spawnY
    override fun getZSpawn(): Int = spawnZ
    override fun getSpawnAngle(): Float = spawnAngle
    override fun getGameTime(): Long = gameTime
    override fun getDayTime(): Long = dayTime
    override fun isThundering(): Boolean = thundering
    override fun isRaining(): Boolean = raining
    override fun setRaining(p_78171_: Boolean) {
        raining = p_78171_
    }

    override fun isHardcore(): Boolean = hardcore
    override fun getGameRules(): GameRules = gameRules
    override fun getDifficulty(): Difficulty = difficulty
    override fun isDifficultyLocked(): Boolean = difficultyLocked
    override fun setXSpawn(p_78651_: Int) {
        spawnX = p_78651_
    }

    override fun setYSpawn(p_78652_: Int) {
        spawnY = p_78652_
    }

    override fun setZSpawn(p_78653_: Int) {
        spawnZ = p_78653_
    }

    override fun setSpawnAngle(p_78648_: Float) {
        spawnAngle = p_78648_
    }

    override fun getLevelName(): String = levelName
    override fun setThundering(p_78623_: Boolean) {
        thundering = p_78623_
    }

    override fun getRainTime(): Int = rainTime
    override fun setRainTime(p_78627_: Int) {
        rainTime = p_78627_
    }

    override fun setThunderTime(p_78626_: Int) {
        thunderTime = p_78626_
    }

    override fun getThunderTime(): Int = thunderTime
    override fun getClearWeatherTime(): Int = clearWeatherTime
    override fun setClearWeatherTime(p_78616_: Int) {
        clearWeatherTime = p_78616_
    }

    override fun getWanderingTraderSpawnDelay(): Int = wanderingTraderSpawnDelay
    override fun setWanderingTraderSpawnDelay(p_78628_: Int) {
        wanderingTraderSpawnDelay = p_78628_
    }

    override fun getWanderingTraderSpawnChance(): Int = wanderingTraderSpawnChance
    override fun setWanderingTraderSpawnChance(p_78629_: Int) {
        wanderingTraderSpawnChance = p_78629_
    }

    override fun getWanderingTraderId(): UUID? = wanderingTraderId
    override fun setWanderingTraderId(p_78620_: UUID) {
        wanderingTraderId = p_78620_
    }

    override fun getGameType(): GameType = gameType
    override fun setWorldBorder(p_78619_: WorldBorder.Settings) {
        worldBorder = p_78619_
    }

    override fun getWorldBorder(): WorldBorder.Settings = worldBorder
    override fun isInitialized(): Boolean = initialized
    override fun setInitialized(p_78625_: Boolean) {
        initialized = p_78625_
    }

    override fun getAllowCommands(): Boolean = allowCommands
    override fun setGameType(p_78618_: GameType) {
        gameType = p_78618_
    }

    override fun getScheduledEvents(): TimerQueue<MinecraftServer> = scheduledEvents
    override fun setGameTime(p_78617_: Long) {
        gameTime = p_78617_
    }

    override fun setDayTime(p_78624_: Long) {
        dayTime = p_78624_
    }

    override fun fillCrashReportCategory(p_164976_: CrashReportCategory, p_164977_: LevelHeightAccessor) {
        super.fillCrashReportCategory(p_164976_, p_164977_)
    }

    fun snapshot(worldBorderSettings: WorldBorder.Settings): InstanceLevelState {
        val borderTag = net.minecraft.nbt.CompoundTag().apply {
            worldBorderSettings.write(this)
        }

        return InstanceLevelState(
            levelName = levelName,
            spawnX = spawnX,
            spawnY = spawnY,
            spawnZ = spawnZ,
            spawnAngle = spawnAngle,
            gameTime = gameTime,
            dayTime = dayTime,
            clearWeatherTime = clearWeatherTime,
            rainTime = rainTime,
            thunderTime = thunderTime,
            raining = raining,
            thundering = thundering,
            gameType = gameType,
            initialized = initialized,
            allowCommands = allowCommands,
            hardcore = hardcore,
            difficulty = difficulty,
            difficultyLocked = difficultyLocked,
            worldBorder = borderTag,
            wanderingTraderSpawnDelay = wanderingTraderSpawnDelay,
            wanderingTraderSpawnChance = wanderingTraderSpawnChance,
            wanderingTraderId = wanderingTraderId,
            gameRules = gameRules.createTag(),
            scheduledEvents = scheduledEvents.store()
        )
    }
}
