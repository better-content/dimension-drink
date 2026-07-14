# Dimension Drink Runtime Plan

Dimensional font runs are canonical-dimension expeditions started by drinking from a blood font. A run is anchored at a real coordinate in the configured target dimension. The backend does not create per-run dimensions and does not permanently scar terrain.

## Invariants

- The target dimension is the actual mod or vanilla dimension named by the font definition.
- The origin font position is part of the run identity.
- Destination x/z is derived from the origin font x/z and the configured coordinate scale.
- Spawn normalization may adjust y and perform a small local x/z search only to make entry safe.
- Players return to the exact stored origin font position.
- Drinking from the font starts or joins a run.
- Runs consume stored blood and close if the font runs dry.
- A still beating heart placed on the font accelerates blood generation by exact heart level.
- Death in a run follows normal death handling for other mods, including RPG Stats progression reset, then respawns the player outside the origin font.
- Font rewards are granted only to surviving run participants.
- The backend may create minimal temporary entry support, but does not clear terrain columns or leave permanent terrain marks.

## Definition Fields

Definitions may use the canonical fields below:

- `targetDimension`: dimension id such as `minecraft:the_end` or `blue_skies:everbright`.
- `coordinateScale`: x/z mapping scale from origin dimension to target dimension.
- `spawnSearchRadius`: local radius for deterministic safe-spawn normalization.
- `runRadius`: active run bounds around the normalized arrival.
- `maxBlood`: blood tank capacity.
- `bloodStartCost`: blood consumed when drinking to start a run.
- `bloodJoinCost`: blood consumed when drinking to join an active run.
- `baseBloodPerTick`: natural blood regeneration rate.
- `heartBloodMultiplier`: per-level multiplier applied when a heart is installed.
- `runBloodDrainPerTick`: active run drain before player scaling.

## Run Lifecycle

1. Validate that the target dimension exists and is loaded.
2. Drink from the font and consume the start or join blood cost.
3. Map origin font x/z into the target dimension.
4. Normalize to a spawnable y and, if necessary, nearby x/z.
5. Create or repair the minimal arrival support and return seal.
6. Teleport players to the real target coordinate.
7. Track active players, participants, survivors, and disqualified players.
8. Return surviving players on completion and grant each survivor rewards.
9. Let death follow normal death handling, then move the respawned player outside the origin font without rewards.
