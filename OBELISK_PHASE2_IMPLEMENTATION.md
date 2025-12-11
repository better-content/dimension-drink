# Obelisk Phase 2: Temporary Dimension System

## Overview

This implementation provides a complete temporary dimension system where each obelisk creates instance dimensions based on NETHER or END templates. When a run ends, the dimension is safely unloaded and its world folder is deleted.

## Architecture

### Core Components

#### 1. Data Models (`/dimension`, `/run`, `/player`)

- **DimensionBaseType**: Enum for NETHER/END base types
- **RunData**: Tracks active run state (obeliskId, runId, baseType, spawn position, active players)
- **PlayerRunInfo**: Stores player's current run participation (origin location, run info)
- **RunManager**: SavedData that persists all active runs across server restarts

#### 2. Dimension Management (`/dimension`)

- **DimensionInstanceManager**: Dynamically creates ServerLevel instances
  - Uses reflection to register dimensions with the server
  - Clones template LevelStems (dimension type + chunk generator)
  - Generates unique dimension keys: `obelisks:nether_run_{obeliskId}_{runId}`

- **SpawnPlatformGenerator**: Creates safe 7x7 spawn platforms
  - Nether: Netherrack platform at Y=64
  - End: End stone platform at surface height
  - Includes glowstone lighting and decorative borders

- **DimensionTeardownHandler**: Safe cleanup of empty dimensions
  - 5-second grace period before cleanup
  - Saves dimension data before unloading
  - Deletes world folders after unload
  - Cleans up orphaned folders on server start

#### 3. Player Management (`/player`)

- **PlayerRunCapability**: Forge capability for tracking player run state
  - Persists across login/logout
  - Stores origin position for return teleport

- **PlayerReturnHandler**: Return mechanics
  - Void fall detection (Y < -64)
  - Manual return command
  - Fallback to overworld spawn on errors

- **RunEventHandlers**: Login/logout handling
  - On logout: removes player from run tracking
  - On login: returns player to origin (MVP approach, no run resumption)

### Data Flow

#### Activation Flow
1. Player right-clicks obelisk
2. If baseType not set → randomly assign NETHER or END
3. Get or create RunData via RunManager
4. Create/load dimension via DimensionInstanceManager
5. Generate spawn platform if needed
6. Store player's origin info in capability
7. Teleport player via `changeDimension()`

#### Return Flow
1. Player falls below Y=-64 (or uses command)
2. PlayerReturnHandler retrieves origin from capability
3. Remove player from RunManager tracking
4. Teleport back to origin obelisk position
5. Clear player's run info
6. Dimension becomes empty → triggers cleanup after delay

#### Cleanup Flow
1. DimensionTeardownHandler detects empty run dimension
2. Wait 100 ticks (5 seconds) grace period
3. Save dimension data
4. Unload ServerLevel from server
5. Remove from level map via reflection
6. Delete world folder
7. RunManager removes RunData

## Template Dimensions

Located in `data/obelisks/`:

### Dimension Types
- `dimension_type/template_nether.json` - Nether-like environment settings
- `dimension_type/template_end.json` - End-like environment settings

### Dimension Definitions (LevelStems)
- `dimension/template_nether.json` - Uses vanilla nether generation
- `dimension/template_end.json` - Uses vanilla end generation

## Commands

All commands require permission level 2 (op):

- `/obelisk debug_spawn` - Spawns an obelisk at your position
- `/obelisk list_runs` - Lists all active runs
- `/obelisk return` - Manually return from a run
- `/obelisk cleanup_run <runId>` - Force cleanup a specific run
- `/obelisk info` - Shows your current run status

## Configuration

See `config/ObelisksConfig.kt` for settings:

- `VOID_FALL_Y_THRESHOLD`: -64.0
- `RUN_CLEANUP_DELAY_TICKS`: 100 (5 seconds)
- `MAX_CONCURRENT_RUNS_PER_OBELISK`: 1
- `ALLOW_BREAK_WHILE_ACTIVE`: false
- `BASE_TYPE_SELECTION_MODE`: "random"
- `RESUME_RUNS_ON_LOGIN`: false (Phase 2 MVP)

## Known Limitations & Future Work

### Phase 2 MVP Limitations
1. **No run resumption on login**: Players are returned to origin instead of resuming their run
2. **No obelisk breaking protection**: Currently breakable even during active runs (config flag exists)
3. **Reflection-based dimension registration**: May break with Minecraft updates
4. **No access transformer**: Using reflection fallbacks for field access

### Phase 3 Enhancements
1. Add FE-based run duration system
2. Implement run resumption on player login
3. Add return pad blocks as alternative exit method
4. Improve dimension registration using Forge APIs or ATs
5. Add more base types (apocalypse variants, custom generators)
6. Implement obelisk breaking protection
7. Add config GUI integration

## Technical Challenges

### Dynamic Dimension Creation
**Challenge**: Minecraft/Forge doesn't provide a public API for creating dimensions at runtime.

**Solution**:
- Clone template LevelStem from registry
- Manually construct ServerLevel with copied settings
- Use reflection to register in server's internal level map
- Fallback to obfuscated field names if needed

### Safe Cleanup
**Challenge**: Deleting world folders while dimension is loaded causes corruption.

**Solution**:
- Explicit save before unload
- Grace period to ensure no pending chunks
- Orphan cleanup on next server start
- Never delete if dimension still registered

### Player State Persistence
**Challenge**: Tracking player run info across login/logout.

**Solution**:
- Forge Capability system for per-player NBT data
- SavedData for global run tracking
- Capability cloning on respawn/dimension change

## File Structure

```
src/main/kotlin/dev/yourname/obelisks/
├── dimension/
│   ├── DimensionBaseType.kt (enum)
│   ├── DimensionInstanceManager.kt (creation)
│   ├── SpawnPlatformGenerator.kt (platforms)
│   └── DimensionTeardownHandler.kt (cleanup)
├── run/
│   ├── RunData.kt (data model)
│   ├── RunManager.kt (SavedData)
│   └── RunEventHandlers.kt (login/logout)
├── player/
│   ├── PlayerRunInfo.kt (data model)
│   ├── PlayerRunCapability.kt (capability)
│   └── PlayerReturnHandler.kt (return mechanics)
├── config/
│   └── ObelisksConfig.kt (settings)
├── content/
│   ├── ObeliskBlock.kt (updated with activation)
│   └── ObeliskBlockEntity.kt (updated with new fields)
└── ObelisksMod.kt (event registration & commands)

src/main/resources/data/obelisks/
├── dimension_type/
│   ├── template_nether.json
│   └── template_end.json
└── dimension/
    ├── template_nether.json
    └── template_end.json
```

## Testing Checklist

- [ ] Spawn obelisk with command
- [ ] Right-click activates and assigns random base type
- [ ] Player teleports to run dimension
- [ ] Spawn platform generates correctly
- [ ] Void fall returns player to origin
- [ ] `/obelisk return` command works
- [ ] Multiple players can join same run
- [ ] Dimension cleans up when empty
- [ ] World folder is deleted after cleanup
- [ ] Player logout/login returns to origin
- [ ] Commands show correct run info
- [ ] Orphaned folders cleaned on server restart

## Dependencies

- Minecraft: 1.20.1
- Forge: 47.4.10
- Kotlin for Forge: 4.11.0 (curse.maven:kotlin-for-forge-351264:4578885)

## Notes for Developers

### Reflection Warnings
The `DimensionInstanceManager` and `DimensionTeardownHandler` use reflection to access internal Minecraft/Forge fields. This is necessary because:
1. No public API exists for runtime dimension creation
2. Access transformers would be better but require additional setup
3. Obfuscated field name fallbacks provide compatibility

### Performance Considerations
- Dimension creation is expensive (~1-2 seconds)
- Platform generation is cheap (7x7 blocks)
- Cleanup is delayed to batch operations
- SavedData writes are automatic on dirty flag

### Multiplayer Safety
- All operations are server-side only
- Capability data syncs automatically
- RunManager is global (overworld SavedData)
- Thread safety: Minecraft main thread only
