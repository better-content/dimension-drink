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

- `build/libs/dimensiondrink-<version>.jar`

`stageRuntimeJar` copies `build/reobfJar/output.jar` onto that canonical release path so pack deployment does not need a repo-specific rename rule.
