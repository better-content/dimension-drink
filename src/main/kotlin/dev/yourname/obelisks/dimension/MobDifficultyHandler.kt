package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.config.ConfigManager
import dev.yourname.obelisks.config.DifficultySettings
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.NaturalSpawner
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.MobSpawnEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.random.Random

/**
 * Handles difficulty modifiers for mobs spawning in run dimensions.
 * Applies per-dimension difficulty settings from config.
 */
object MobDifficultyHandler {

    // Track when to trigger extra spawns per dimension
    private val dimensionSpawnTickers = mutableMapOf<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Int>()

    // Track spawn statistics for debugging
    private val totalSpawnsPerDimension = mutableMapOf<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Int>()
    private var lastLogTick = 0

    // Hard mob cap per dimension - we'll aggressively spawn to maintain this
    private const val DIMENSION_MOB_CAP = 20

    // Track flying mobs we've spawned per dimension (UUID -> spawn tick)
    private val spawnedFlyingMobs = mutableMapOf<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, MutableSet<java.util.UUID>>()

    /**
     * Dramatically increases spawn rates by forcing additional spawn attempts EVERY TICK.
     * The multiplier determines how many extra spawn attempts happen per tick per player.
     */
    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server
        val runManager = RunManager.get(server)

        // For each active run dimension
        for (runData in runManager.getAllRuns()) {
            val dimConfig = ConfigManager.getDimensionConfig(runData.dimensionId)
            if (dimConfig == null) {
                println("[Obelisks] WARNING: No config found for dimension ${runData.dimensionId}")
                continue
            }

            val runLevel = server.getLevel(runData.runDimensionKey)
            if (runLevel == null) {
                println("[Obelisks] WARNING: Could not get level for dimension ${runData.runDimensionKey}")
                continue
            }
            val dimKey = runData.runDimensionKey

            // Initialize ticker for this dimension
            if (!dimensionSpawnTickers.containsKey(dimKey)) {
                dimensionSpawnTickers[dimKey] = 0
                println("[Obelisks] Starting mob spawner for dimension ${runData.dimensionId}")
            }

            // Increment dimension-specific ticker
            dimensionSpawnTickers[dimKey] = dimensionSpawnTickers[dimKey]!! + 1

            val playersInDimension = runData.activePlayers.mapNotNull { server.playerList.getPlayer(it) }
            if (playersInDimension.isEmpty()) continue

            // Log every 100 ticks (5 seconds)
            val ticker = dimensionSpawnTickers[dimKey]!!

            // Clean up dead flying mobs from tracking
            val flyingMobSet = spawnedFlyingMobs.getOrPut(dimKey) { mutableSetOf() }
            val allEntities = runLevel.entities.getAll()
            flyingMobSet.removeIf { uuid ->
                allEntities.none { it.uuid == uuid && it.isAlive && !it.isRemoved }
            }

            // Process each player individually - each gets their own 20 mob cap
            for (player in playersInDimension) {
                val playerPos = player.blockPosition()

                // Count monsters near THIS player (within 128 blocks)
                val nearbyMobsList = runLevel.entities.getAll()
                    .filterIsInstance<Monster>()
                    .filter { it.isAlive && !it.isRemoved && it.blockPosition().closerThan(playerPos, 128.0) }

                // Count flying mobs we've spawned that are still alive
                val flyingMobsNearPlayer = flyingMobSet.size.coerceAtMost(4) // Cap at 4
                val groundMobsNearPlayer = nearbyMobsList.size - flyingMobsNearPlayer
                val totalMobsNearPlayer = groundMobsNearPlayer + flyingMobsNearPlayer

                // Calculate quotas: 80% ground mobs (16), 20% flying mobs (4)
                val groundMobCap = (DIMENSION_MOB_CAP * 0.8).toInt()
                val flyingMobCap = DIMENSION_MOB_CAP - groundMobCap

                val groundMobsNeeded = (groundMobCap - groundMobsNearPlayer).coerceAtLeast(0)
                val flyingMobsNeeded = (flyingMobCap - flyingMobsNearPlayer).coerceAtLeast(0)

                // Log for this player
                if (ticker % 100 == 0) {
                    val mobInfo = nearbyMobsList.take(5).joinToString("\n  ") { mob ->
                        val mobPos = mob.blockPosition()
                        val dist = mobPos.distSqr(playerPos)
                        val mobType = if (mob is net.minecraft.world.entity.FlyingMob) "[FLY]" else "[GND]"
                        "$mobType ${mob.type.description.string} @ ${Math.sqrt(dist).toInt()}m (${mobPos.x}, ${mobPos.y}, ${mobPos.z})"
                    }

                    println("[Obelisks] ${player.name.string}: ${totalMobsNearPlayer}/$DIMENSION_MOB_CAP total (${groundMobsNearPlayer}/${groundMobCap} ground, ${flyingMobsNearPlayer}/${flyingMobCap} flying)")
                    println("  Need: ${groundMobsNeeded} ground, ${flyingMobsNeeded} flying")
                    println("  Player at: (${playerPos.x}, ${playerPos.y}, ${playerPos.z})")
                    if (nearbyMobsList.isNotEmpty()) {
                        println("  Sample mobs:\n  $mobInfo")
                    } else {
                        println("  No mobs found nearby (within 128 blocks)")
                    }
                }

                // If we're at or above caps for both mob types, skip spawning entirely
                if (groundMobsNearPlayer >= groundMobCap && flyingMobsNearPlayer >= flyingMobCap) continue

                var successfulGroundSpawns = 0
                var successfulFlyingSpawns = 0

                // PHASE 1: Spawn ground mobs ONLY if needed
                if (groundMobsNeeded > 0) {
                    // Increase attempts significantly since many will be filtered out
                    val groundAttempts = (groundMobsNeeded * 50).coerceAtLeast(100).coerceAtMost(500)
                    for (attempt in 0 until groundAttempts) {
                        if (successfulGroundSpawns >= groundMobsNeeded) break

                    // Random offset from player (16-48 blocks away - visible but not too close)
                    val offsetX = (Random.nextInt(32) + 16) * if (Random.nextBoolean()) 1 else -1
                    val offsetZ = (Random.nextInt(32) + 16) * if (Random.nextBoolean()) 1 else -1
                    // Y offset: +/- 20 blocks from player's Y level (increased range)
                    val offsetY = Random.nextInt(41) - 20
                    val spawnPos = playerPos.offset(offsetX, offsetY, offsetZ)

                    // Try to spawn a monster from the biome
                    val biome = runLevel.getBiome(spawnPos)
                    val spawns = biome.value().getMobSettings().getMobs(MobCategory.MONSTER)
                    if (spawns.isEmpty()) continue

                    val spawnEntry = spawns.unwrap().random()
                    val mobType = spawnEntry.type
                    val mob = mobType.create(runLevel) ?: continue

                    // Skip if this is a flying mob - we want ground mobs only in this phase
                    val typeName = BuiltInRegistries.ENTITY_TYPE.getKey(mob.type).toString()
                    val canFly = typeName.contains("ghast") ||
                                 typeName.contains("phantom") ||
                                 typeName.contains("wither") ||
                                 mob is net.minecraft.world.entity.FlyingMob
                    if (canFly) continue

                    // Ground mobs: need solid block below and air above
                    // Check up to 10 blocks up/down from spawn pos for valid ground (increased range)
                    var foundPos: BlockPos? = null
                    for (yCheck in -10..10) {
                        val checkPos = spawnPos.offset(0, yCheck, 0)
                        val blockBelow = runLevel.getBlockState(checkPos.below())
                        val blockAt = runLevel.getBlockState(checkPos)
                        val blockAbove = runLevel.getBlockState(checkPos.above())

                        if (blockBelow.isSolidRender(runLevel, checkPos.below()) &&
                            blockAt.isAir &&
                            blockAbove.isAir) {
                            foundPos = checkPos
                            break
                        }
                    }
                    if (foundPos == null) continue // No valid ground found

                    // Verify mob can path to player from spawn position
                    // Relaxed check: allow more vertical distance
                    val yDiff = Math.abs(foundPos.y - playerPos.y)
                    if (yDiff > 30) continue // Increased from 15 to 30

                    // Removed line-of-sight check - too restrictive, mobs can navigate around walls

                        // Spawn the ground mob
                        mob.moveTo(foundPos.x + 0.5, foundPos.y.toDouble(), foundPos.z + 0.5, Random.nextFloat() * 360, 0f)
                        if (mob is Mob) {
                            mob.finalizeSpawn(runLevel, runLevel.getCurrentDifficultyAt(foundPos), net.minecraft.world.entity.MobSpawnType.NATURAL, null, null)
                        }
                        mob.isSilent = true
                        runLevel.addFreshEntity(mob)
                        mob.isSilent = false
                        successfulGroundSpawns++
                    }
                }

                // PHASE 2: Spawn flying mobs ONLY if:
                // 1. We have enough ground mobs (at least 80% of ground cap)
                // 2. We still need flying mobs
                // 3. We haven't hit our tracked flying mob limit
                if (groundMobsNearPlayer >= groundMobCap && flyingMobsNeeded > 0 && flyingMobSet.size < flyingMobCap) {
                    val flyingAttempts = (flyingMobsNeeded * 20).coerceAtLeast(10).coerceAtMost(100)
                    for (attempt in 0 until flyingAttempts) {
                        if (flyingMobSet.size >= flyingMobCap) break

                        // Random offset from player
                        val offsetX = (Random.nextInt(32) + 16) * if (Random.nextBoolean()) 1 else -1
                        val offsetZ = (Random.nextInt(32) + 16) * if (Random.nextBoolean()) 1 else -1
                        val offsetY = Random.nextInt(21) - 10
                        val spawnPos = playerPos.offset(offsetX, offsetY, offsetZ)

                        // Try to spawn a monster from the biome
                        val biome = runLevel.getBiome(spawnPos)
                        val spawns = biome.value().getMobSettings().getMobs(MobCategory.MONSTER)
                        if (spawns.isEmpty()) continue

                        val spawnEntry = spawns.unwrap().random()
                        val mobType = spawnEntry.type
                        val mob = mobType.create(runLevel) ?: continue

                        // Skip if this is NOT a flying mob
                        val typeName = BuiltInRegistries.ENTITY_TYPE.getKey(mob.type).toString()
                        val canFly = typeName.contains("ghast") ||
                                     typeName.contains("phantom") ||
                                     typeName.contains("wither") ||
                                     mob is net.minecraft.world.entity.FlyingMob
                        if (!canFly) continue

                        // Flying mobs: spawn in air at the calculated position if there's air
                        if (!runLevel.getBlockState(spawnPos).isAir) continue

                        // Spawn the flying mob
                        mob.moveTo(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5, Random.nextFloat() * 360, 0f)
                        if (mob is Mob) {
                            mob.finalizeSpawn(runLevel, runLevel.getCurrentDifficultyAt(spawnPos), net.minecraft.world.entity.MobSpawnType.NATURAL, null, null)
                        }
                        mob.isSilent = true
                        runLevel.addFreshEntity(mob)
                        mob.isSilent = false

                        // Track this flying mob
                        flyingMobSet.add(mob.uuid)
                        successfulFlyingSpawns++
                    }
                }

                // Track total spawns for this dimension (for the logging we do every 100 ticks above)
                val totalSuccessfulSpawns = successfulGroundSpawns + successfulFlyingSpawns
                if (totalSuccessfulSpawns > 0) {
                    totalSpawnsPerDimension[dimKey] = (totalSpawnsPerDimension[dimKey] ?: 0) + totalSuccessfulSpawns
                }
            }
        }
    }


    /**
     * Handles spawn rate modification by allowing or denying spawns based on spawnRateMultiplier.
     * For multipliers < 1.0, reduces spawn rate.
     */
    @SubscribeEvent
    fun onCheckSpawn(event: MobSpawnEvent.PositionCheck) {
        val entity = event.entity
        val level = event.level

        // Only process server-side and in run dimensions
        if (level.isClientSide || level !is ServerLevel) return
        if (entity !is Mob) return

        // Check if this is a run dimension
        val server = level.server
        val runManager = RunManager.get(server)
        val runData = runManager.getRunByDimension(level.dimension()) ?: return

        // Get dimension config and difficulty settings
        val dimConfig = ConfigManager.getDimensionConfig(runData.dimensionId) ?: return
        val spawnRateMultiplier = dimConfig.difficultySettings.spawnRateMultiplier

        // Only handle reduction here (< 1.0)
        if (spawnRateMultiplier >= 1.0) return

        // For multipliers < 1.0, randomly deny spawns to reduce spawn rate
        // e.g., 0.5 multiplier = 50% chance to allow spawn
        if (Random.nextDouble() >= spawnRateMultiplier) {
            event.result = Event.Result.DENY
        }
    }

    @SubscribeEvent
    fun onMobSpawn(event: MobSpawnEvent.FinalizeSpawn) {
        val entity = event.entity
        val level = event.level

        // Only process server-side and in run dimensions
        if (level.isClientSide || level !is ServerLevel) return
        if (entity !is Mob) return

        // Check if this is a run dimension
        val runManager = RunManager.get(level.server)
        val runData = runManager.getRunByDimension(level.dimension()) ?: return

        // Get dimension config and difficulty settings
        val dimConfig = ConfigManager.getDimensionConfig(runData.dimensionId) ?: return
        val difficulty = dimConfig.difficultySettings

        // Apply difficulty modifiers
        applyDifficultyModifiers(entity, difficulty, level)

        // Always apply Speed I to all monsters
        if (entity is Monster) {
            entity.addEffect(MobEffectInstance(
                net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED,
                Int.MAX_VALUE, // Permanent
                0, // Amplifier 0 = Speed I
                false,
                false
            ))
        }

        // Make zombie piglins spawn hostile
        if (entity is net.minecraft.world.entity.monster.ZombifiedPiglin) {
            entity.setRemainingPersistentAngerTime(Int.MAX_VALUE)
            entity.setPersistentAngerTarget(null) // Angry at everyone
        }

        // Make endermen spawn hostile
        if (entity is net.minecraft.world.entity.monster.EnderMan) {
            // Set enderman as angry
            entity.setTarget(level.getNearestPlayer(entity, 64.0))
            // Make them persistent so they don't calm down
            entity.isAggressive = true
        }
        if (entity is net.minecraft.world.entity.monster.piglin.Piglin) {
            entity.setTarget(level.getNearestPlayer(entity, 16.0))
        }
    }

    /**
     * Applies all difficulty modifiers to a spawned mob.
     */
    private fun applyDifficultyModifiers(mob: Mob, difficulty: DifficultySettings, level: ServerLevel) {
        // Health multiplier
        if (difficulty.healthMultiplier != 1.0) {
            val maxHealth = mob.getAttribute(Attributes.MAX_HEALTH)
            if (maxHealth != null) {
                val newMaxHealth = maxHealth.baseValue * difficulty.healthMultiplier
                maxHealth.baseValue = newMaxHealth
                mob.health = newMaxHealth.toFloat()
            }
        }

        // Damage multiplier
        if (difficulty.damageMultiplier != 1.0) {
            val attackDamage = mob.getAttribute(Attributes.ATTACK_DAMAGE)
            if (attackDamage != null) {
                attackDamage.baseValue *= difficulty.damageMultiplier
            }
        }

        // Speed multiplier
        if (difficulty.speedMultiplier != 1.0) {
            val movementSpeed = mob.getAttribute(Attributes.MOVEMENT_SPEED)
            if (movementSpeed != null) {
                movementSpeed.baseValue *= difficulty.speedMultiplier
            }
        }

        // Bonus armor points
        if (difficulty.bonusArmorPoints > 0.0) {
            val armor = mob.getAttribute(Attributes.ARMOR)
            if (armor != null) {
                armor.baseValue += difficulty.bonusArmorPoints
            }
        }

        // Can pickup loot
        if (difficulty.canPickupLoot) {
            mob.setCanPickUpLoot(true)
        }

        // Apply potion effects
        for (effectString in difficulty.mobEffects) {
            applyPotionEffect(mob, effectString)
        }

        // Equipment chances
        if (mob is Monster) {
            if (Random.nextDouble() < difficulty.armorChance) {
                giveRandomArmor(mob)
            }
            if (Random.nextDouble() < difficulty.weaponChance) {
                giveRandomWeapon(mob)
            }
        }

        // Baby chance (for applicable mobs)
        if (difficulty.babyChance > 0.0 && Random.nextDouble() < difficulty.babyChance) {
            if (mob.isBaby == false) { // Only if mob can be a baby
                mob.setBaby(true)
            }
        }
    }

    /**
     * Parses and applies a potion effect string (format: "effect_id:amplifier:duration_seconds").
     */
    private fun applyPotionEffect(mob: LivingEntity, effectString: String) {
        try {
            val parts = effectString.split(":")
            if (parts.size != 3) return

            val effectId = parts[0]
            val amplifier = parts[1].toIntOrNull() ?: 0
            val durationSeconds = parts[2].toIntOrNull() ?: 30

            val effect = BuiltInRegistries.MOB_EFFECT.get(ResourceLocation(effectId))
            if (effect != null) {
                mob.addEffect(MobEffectInstance(effect, durationSeconds * 20, amplifier))
            }
        } catch (e: Exception) {
            // Invalid effect string, skip
        }
    }

    /**
     * Gives random armor to a mob.
     */
    private fun giveRandomArmor(mob: Mob) {
        val armorTier = Random.nextInt(3) // 0=leather, 1=iron, 2=diamond

        val helmet = when (armorTier) {
            0 -> Items.LEATHER_HELMET
            1 -> Items.IRON_HELMET
            else -> Items.DIAMOND_HELMET
        }
        val chestplate = when (armorTier) {
            0 -> Items.LEATHER_CHESTPLATE
            1 -> Items.IRON_CHESTPLATE
            else -> Items.DIAMOND_CHESTPLATE
        }
        val leggings = when (armorTier) {
            0 -> Items.LEATHER_LEGGINGS
            1 -> Items.IRON_LEGGINGS
            else -> Items.DIAMOND_LEGGINGS
        }
        val boots = when (armorTier) {
            0 -> Items.LEATHER_BOOTS
            1 -> Items.IRON_BOOTS
            else -> Items.DIAMOND_BOOTS
        }

        mob.setItemSlot(EquipmentSlot.HEAD, ItemStack(helmet))
        mob.setItemSlot(EquipmentSlot.CHEST, ItemStack(chestplate))
        mob.setItemSlot(EquipmentSlot.LEGS, ItemStack(leggings))
        mob.setItemSlot(EquipmentSlot.FEET, ItemStack(boots))

        // Make armor not drop (prevents exploits)
        mob.setDropChance(EquipmentSlot.HEAD, 0.0f)
        mob.setDropChance(EquipmentSlot.CHEST, 0.0f)
        mob.setDropChance(EquipmentSlot.LEGS, 0.0f)
        mob.setDropChance(EquipmentSlot.FEET, 0.0f)
    }

    /**
     * Gives a random weapon to a mob.
     */
    private fun giveRandomWeapon(mob: Mob) {
        val weapons = listOf(
            Items.IRON_SWORD,
            Items.IRON_AXE,
            Items.DIAMOND_SWORD,
            Items.STONE_SWORD,
            Items.WOODEN_SWORD
        )

        val weapon = weapons.random()
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(weapon))
        mob.setDropChance(EquipmentSlot.MAINHAND, 0.0f) // Don't drop weapon
    }
}
