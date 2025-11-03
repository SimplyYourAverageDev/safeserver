# Multi-Version Build System

This project now supports building for multiple Minecraft versions (1.21.8, 1.21.9, and 1.21.10) with a single build command.

## Project Structure

```
safeserver/
├── build.gradle              # Root build configuration
├── settings.gradle           # Multi-project configuration
├── gradle.properties         # Shared Gradle properties
├── src/                      # Shared source code
│   ├── main/java/           # Main mod code
│   ├── main/resources/      # Resources including fabric.mod.json
│   └── client/java/         # Client-side code
└── versions/                 # Version-specific builds
    ├── 1.21.8/
    │   ├── build.gradle     # Build config for 1.21.8
    │   └── gradle.properties # Version-specific properties
    ├── 1.21.9/
    │   ├── build.gradle     # Build config for 1.21.9
    │   └── gradle.properties # Version-specific properties
    └── 1.21.10/
        ├── build.gradle     # Build config for 1.21.10
        └── gradle.properties # Version-specific properties
```

## Building All Versions

To build JAR files for all three Minecraft versions at once:

```bash
./gradlew build
```

This single command will:
1. Build safeserver-1.21.8-2.0.3.jar
2. Build safeserver-1.21.9-2.0.3.jar
3. Build safeserver-1.21.10-2.0.3.jar

## Building Individual Versions

To build just one specific version:

```bash
# Build only 1.21.8
./gradlew :versions:1.21.8:build

# Build only 1.21.9
./gradlew :versions:1.21.9:build

# Build only 1.21.10
./gradlew :versions:1.21.10:build
```

## Artifact Locations

After building, the mod JAR files will be located at:
- `versions/1.21.8/build/libs/safeserver-1.21.8-2.0.3.jar`
- `versions/1.21.9/build/libs/safeserver-1.21.9-2.0.3.jar`
- `versions/1.21.10/build/libs/safeserver-1.21.10-2.0.3.jar`

## Listing All Artifacts

To see all generated artifacts:

```bash
./gradlew listArtifacts
```

## Version-Specific Configuration

Each version has its own `gradle.properties` file that specifies:
- Minecraft version
- Yarn mappings version
- Fabric Loader version
- Fabric API version
- Mod version
- Artifact base name

## Fabric Mod Version Compatibility

The fabric.mod.json is configured to support Minecraft versions `~1.21.8`, which means it's compatible with any version >= 1.21.8 and < 1.22. Each build produces a separate JAR optimized for its specific Minecraft version's API.

## Dependencies

| Component | 1.21.8 | 1.21.9 | 1.21.10 |
|-----------|--------|--------|---------|
| Minecraft | 1.21.8 | 1.21.9 | 1.21.10 |
| Yarn Mappings | 1.21.8+build.1 | 1.21.9+build.1 | 1.21.10+build.1 |
| Fabric Loader | 0.16.14 | 0.16.14 | 0.16.14 |
| Fabric API | 0.130.0+1.21.8 | 0.134.0+1.21.9 | 0.137.0+1.21.10 |
| Fabric Loom | 1.12.7 | 1.12.7 | 1.12.7 |

## Development

All source code is shared across versions. The version-specific builds simply compile the same source code against different Minecraft/Fabric API versions. This approach works well for 1.21.x versions which have similar APIs.

If you need version-specific code in the future, you can:
1. Create version-specific source directories in each version folder
2. Add conditional loading logic
3. Use Fabric's version checking APIs

## Cleaning

To clean all build outputs:

```bash
./gradlew clean
```

## Requirements

- Java 21
- Gradle 8.14+ (included via wrapper)
- Internet connection (for downloading dependencies)
