# Dimension Drink

Pack-owned obelisk and blood-font worldgen/runtime mod for Forge `1.20.1`.

## Common commands

```bash
./gradlew verifyFast
./gradlew verifyFull
```

`verifyFast` runs the JVM verification lane. `verifyFull` adds the headless Forge GameTest pass using the default all-suites selection.

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
