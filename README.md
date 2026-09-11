# Dimension Drink

Pack-owned obelisk and charge-font worldgen/runtime mod for Forge `1.20.1`.

## Common commands

```bash
./gradlew verifyFast
./gradlew verifyFull
./gradlew verifySmoke
```

`verifyFast` runs the JVM verification lane. `verifyFull` adds **all production
GameTests** (48 at this revision). `verifySmoke` adds only the 11 lifecycle smoke
tests. `./gradlew headlessGameTest -PdimensionDrinkGameTestSelection=runtime` selects a focused
profile; supported selectors are `all`, `smoke`, `run`, `activation`, `rewards`,
`void`, `data`, `template`, `multiplayer`, `commands`, and `runtime`. Unknown selectors
and overrides conflicting with `verifyFull` or `verifySmoke` fail.

Each GameTest invocation retains its generated world, logs, and `execution.json` under
`build/gametest/<run-id>/`. The gate compares actual runtime registration and
successful completion against the reviewed IDs in `gametest/profiles/`, rejects
missing or incomplete evidence, and records the selected profile and run ID.
Inspect failed evidence before manually removing its fixture. A later run uses a
new fixture; it does not delete a previous failed world. Runtime charge scenarios
use deterministic modifier fixtures and separate batches; worldgen scenarios
prepare their terrain and worldgen heightmaps explicitly. `verifyFast` also runs
12 negative execution-evidence checks without starting Forge.

## Font generation and discovery

Dimension Drink bundles the Aether, Bumblezone, Nether, and Ratlantis Font definitions at equal default `worldgenWeight`. A weight controls deterministic selection at an eligible structure start; it does not guarantee equal visible counts in a finite explored area. Terrain rejection, exploration history, destroyed Fonts, and map sales are measured separately from configured probability.

Layout and definition selection use independent deterministic seed domains, so terrain opportunity cannot systematically favor a Font type. Custom JSON definitions remain supported when their weights are positive and finite. The effective normalized weights are logged on reload and available with the permission-level-2 `/font audit` command.

Naturally generated Fonts are added to a saved discovery index as their chunks load. Wandering-trader Font maps use only that index and never locate or generate remote structure chunks; maps are marker-only until players explore their terrain. Player-placed and debug Fonts are not indexed.

These contracts are new-world-only. Existing copied configuration, historical structures, and saves are not migrated or scanned; remove old Font configuration and create a new world when validating the new distribution.

A successful aggregate run completion posts `FontAggregateReturnEvent` once for each surviving
participant actually transported back to the origin. When Better Content Threads is present, the
optional bridge reuses the active `the_end_is_not_a_door` correlation token to emit
`font_route_completed=returned`; automatic failure, death, logout, and server-stop cleanup paths
do not emit it.

## Release artifact

Use the staged reobfuscated runtime jar for pack deployment:

- `build/libs/dimension-drink-<version>.jar`

`stageRuntimeJar` copies `build/reobfJar/output.jar` onto that canonical release path so pack deployment does not need a repo-specific rename rule.

## Community and support

For modpack and mod discussion, playtest feedback, and bug reports, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).

## Canonical identity

- Repository and Gradle project: `dimension-drink`
- Mod ID and resource namespace: `dimension_drink`
- Maven group: `com.bettercontent`
- Runtime artifact: `build/libs/dimension-drink-<version>.jar`

The canonical identity is a clean break. Legacy mod IDs, resource namespaces, configuration paths, commands, network channels, and saved-data keys are not migrated or aliased.
