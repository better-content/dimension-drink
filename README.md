# Dimensional Fonts

Pack-owned obelisk and blood-font worldgen/runtime mod for Forge `1.20.1`.

## Common commands

```bash
./gradlew test
./gradlew headlessGameTest
./gradlew clean build reobfJar stageRuntimeJar
```

Selection-specific game test runs are also available:

- `./gradlew runGameTestServerRunLifecycle`
- `./gradlew runGameTestServerActivation`
- `./gradlew runGameTestServerRewards`
- `./gradlew runGameTestServerVoid`
- `./gradlew runGameTestServerData`
- `./gradlew runGameTestServerTemplate`
- `./gradlew runGameTestServerMultiplayer`
- `./gradlew runGameTestServerCommands`
- `./gradlew runGameTestServerRuntime`
- `./gradlew runGameTestServerTCon`

## Release artifact

Use the staged reobfuscated runtime jar for pack deployment:

- `build/libs/dimensionalfonts-<version>.jar`

`stageRuntimeJar` copies `build/reobfJar/output.jar` onto that canonical release path so pack deployment does not need a repo-specific rename rule.
