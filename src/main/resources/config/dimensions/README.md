# Dimension Obelisk Configuration

This folder contains JSON configuration files that define obelisk properties for each dimension.

## JSON Schema

Each dimension config file should follow this structure:

```json
{
  "dimensionId": "minecraft:the_nether",
  "dimensionName": "The Nether",
  "stemBlockType": "minecraft:netherrack",
  "feMultiplier": 1.0,
  "spawnY": 100,
  "platformBlock": "minecraft:netherrack",
  "glowBlock": "minecraft:glowstone",
  "enabled": true,
  "minFERequired": 0,
  "collapseSpeedMultiplier": 1.0,
  "customProperties": {}
}
```

## Property Descriptions

- **dimensionId**: The resource location of the dimension (e.g., "minecraft:the_nether")
- **dimensionName**: Human-readable name for display purposes
- **stemBlockType**: Block type to use for the obelisk pillar/stem
- **feMultiplier**: FE drain multiplier (1.0 = normal, 2.0 = double drain rate)
- **spawnY**: Default Y-coordinate for spawn platforms in this dimension
- **platformBlock**: Block type to use for spawn platforms
- **glowBlock**: Block type to use for platform lighting
- **enabled**: Whether this dimension is available for obelisk runs
- **minFERequired**: Minimum FE the obelisk must have to enter this dimension
- **collapseSpeedMultiplier**: Multiplier for dimension collapse speed (1.0 = normal)
- **customProperties**: Additional properties for future extensibility

## Adding New Dimensions

To add support for a new dimension:

1. Create a new JSON file in this directory (e.g., `twilight_forest.json`)
2. Fill in all required properties
3. Set `enabled: true` to activate it
4. Restart the server or use `/obelisk reload` (if implemented)

## Example: Twilight Forest

```json
{
  "dimensionId": "twilightforest:twilight_forest",
  "dimensionName": "Twilight Forest",
  "stemBlockType": "twilightforest:twilight_oak_log",
  "feMultiplier": 1.2,
  "spawnY": 80,
  "platformBlock": "twilightforest:mazestone_brick",
  "glowBlock": "twilightforest:firefly",
  "enabled": true,
  "minFERequired": 3000,
  "collapseSpeedMultiplier": 1.1,
  "customProperties": {}
}
```

## Notes

- Files are loaded automatically on server start
- Invalid or malformed JSON files will be skipped with a warning
- The mod will generate default configs if this directory is empty
