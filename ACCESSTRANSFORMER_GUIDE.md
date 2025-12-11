# Access Transformers Setup - Current Status

## What I've Done

1. ✅ Created `src/main/resources/META-INF/accesstransformer.cfg`
2. ✅ Enabled Access Transformers in `build.gradle` (line 69)
3. ✅ Added transformations for MinecraftServer fields
4. ⚠️ Updated code to use reflected field access

## Current Issues

The Access Transformers are working (fields are being made public), but there are API complexity issues:

1. **storageSource** returns `LevelStorageSource`, but ServerLevel constructor needs `LevelStorageAccess`
2. The relationship between these types is complex in Forge 1.20.1

## Simpler Alternative: Pre-Created Dimensions

Instead of dynamic dimension creation (which is complex), use pre-created dimensions:

### Step 1: Create Fixed Dimension Files

Create these in `src/main/resources/data/obelisks/dimension/`:

```json
// nether_run_1.json
{
  "type": "obelisks:template_nether",
  "generator": {
    "type": "minecraft:noise",
    "biome_source": {
      "type": "minecraft:multi_noise",
      "preset": "minecraft:nether"
    },
    "settings": "minecraft:nether"
  }
}
```

Create 5-10 of these (nether_run_1, nether_run_2, end_run_1, end_run_2, etc.)

### Step 2: Simplify DimensionInstanceManager

Replace the `createDimensionInstance` method with:

```kotlin
private fun getPreCreatedDimension(
    server: MinecraftServer,
    dimensionKey: ResourceKey<Level>,
    baseType: DimensionBaseType,
    runId: Long
): ServerLevel {
    // Map to pre-created dimensions (round-robin)
    val dimNumber = (runId % 5) + 1  // Use 5 pre-created dimensions
    val dimName = "${baseType.name.lowercase()}_run_$dimNumber"
    val actualKey = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation("obelisks", dimName)
    )

    return server.getLevel(actualKey)
        ?: throw IllegalStateException("Pre-created dimension not found: $dimName")
}
```

### Benefits of This Approach

- ✅ Works immediately without complex APIs
- ✅ No Access Transformers needed
- ✅ Stable and reliable
- ✅ Still supports multiple concurrent runs (limited to 5-10)
- ✅ Dimensions persist between runs (might be desired)

### Drawbacks

- Limited number of concurrent runs (but 5-10 is usually enough)
- Dimensions aren't truly "temporary" (they persist)
- World folders don't auto-delete
- Can't have unique dimension per run

## Recommended Path Forward

**For testing and MVP**: Use the pre-created dimensions approach above.

**For full dynamic creation**: This requires deeper Forge/Minecraft internals knowledge:
- Understanding the LevelStorageAccess system
- Proper dimension lifecycle management
- Potentially using Forge events instead of direct construction

The pre-created approach gets you 90% of the functionality with 10% of the complexity.

## Current Access Transformer File

The AT file is correct and working:

```
# Access Transformers for Obelisk Mod
public net.minecraft.server.MinecraftServer f_129768_ # levels
public net.minecraft.server.MinecraftServer f_129818_ # executor
public net.minecraft.server.MinecraftServer f_129822_ # storageSource
public net.minecraft.server.MinecraftServer f_129829_ # progressListenerFactory
```

The fields are being exposed correctly - the issue is with the API usage, not the transformers themselves.

## Next Session Goals

1. Decide: Dynamic creation (complex) vs Pre-created (simple)
2. If pre-created: Create 5-10 dimension JSON files
3. If dynamic: Research Forge's dimension loading system more thoroughly
4. Test the chosen approach in-game

