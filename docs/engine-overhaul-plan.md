# Dimensional Scar Expeditions Canonical Dimension Backend Plan

Meteoric rift anchor runs are canonical-dimension expeditions. A run is anchored at a real coordinate in the real target dimension configured by the anchor definition. The backend never creates per-run dimensions, never allocates distant hidden sites, and never translates gameplay through a fake local coordinate space.

## Invariants

- The target dimension is the actual mod or vanilla dimension named by the obelisk definition.
- The origin rift anchor position is part of the run identity.
- Destination x/z is derived from the origin rift anchor x/z and the configured coordinate scale.
- Spawn normalization may adjust y and perform a small local x/z search only to make entry safe.
- Players return to the exact stored origin rift anchor.
- Stability scarring is real block damage in the target dimension and persists forever.
- Normal scarring protects only the minimal spawn/return contract.
- Power depletion removes the return pad and its support columns.

## Definition Fields

Definitions may use the canonical fields below:

- `targetDimension`: dimension id such as `minecraft:the_end` or `blue_skies:everbright`.
- `coordinateScale`: x/z mapping scale from origin dimension to target dimension.
- `spawnSearchRadius`: local radius for deterministic safe-spawn normalization.
- `runRadius`: active run bounds around the normalized anchor.
- `scarRadius`: radius around active players considered for permanent column removal.
- `scarIntervalTicks`: cadence for stability scarring.
- `scarColumnsPerInterval`: number of columns removed per scar tick.
- `protectedSpawnRadius`: minimal protected footprint around the return pad.

`instanceTemplateId` remains as a deprecated compatibility alias. During load it is normalized to the resolved canonical target dimension id.

## Run Lifecycle

1. Validate that the target dimension exists and is loaded.
2. Map origin rift anchor x/z into the target dimension.
3. Normalize to a spawnable y and, if necessary, nearby x/z.
4. Create or repair the minimal spawn pillar, support floor, and return pad.
5. Teleport players to the real target coordinate.
6. Track active players and touched chunks.
7. Remove full vertical columns as stability scarring.
8. Return players to the exact origin anchor on pad use, void fall, logout cleanup, or run close.
9. Leave canonical dimension damage in place permanently.

## Scarring

Scarring removes entire x/z columns from min build height to max build height, including bedrock. The only normal exclusions are protected spawn-contract columns. On power depletion, those protected columns are removed too, including the return pad and its supports.

## Current Risks

- Nearby obelisks can intentionally overlap in the same canonical target dimension.
- Shared dimensions mean mobs, blocks, and player-made changes are part of the world state.
- Some mod dimensions may need custom spawn tuning.
- Existing configs using `instanceTemplateId` should migrate to `targetDimension`.
