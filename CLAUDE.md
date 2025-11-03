# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Minecraft Fabric mod called "Safeserver" that adds mandatory password authentication to Minecraft servers. It's built using Java 21 and Fabric API, supporting Minecraft versions 1.21.8, 1.21.9, and 1.21.10.

## Multi-Version Build System

The project uses a Gradle multi-project structure to build artifacts for multiple Minecraft versions simultaneously:
- Version 1.21.8 - Fabric API 0.130.0+1.21.8
- Version 1.21.9 - Fabric API 0.134.0+1.21.9
- Version 1.21.10 - Fabric API 0.137.0+1.21.10

See `MULTI_VERSION_BUILD.md` for detailed documentation on the build system.

## Development Commands

### Build and Development
- `./gradlew build` - Build mod JARs for ALL three Minecraft versions at once
- `./gradlew :versions:1.21.8:build` - Build only for Minecraft 1.21.8
- `./gradlew :versions:1.21.9:build` - Build only for Minecraft 1.21.9
- `./gradlew :versions:1.21.10:build` - Build only for Minecraft 1.21.10
- `./gradlew listArtifacts` - List all generated JAR files
- `./gradlew publishToMavenLocal` - Publish to local Maven repository
- `./gradlew runServer` - Run the mod in a server environment for testing
- `./gradlew runClient` - Run the mod in a client environment for testing

### Project Structure
- `src/main/java/` - Main mod source code (shared across all versions)
- `src/client/java/` - Client-side specific code (shared across all versions)
- `src/main/resources/` - Resources including mod metadata and mixin configurations
- `build.gradle` - Root build configuration (parent project)
- `gradle.properties` - Shared Gradle properties
- `versions/` - Version-specific build configurations
  - `versions/1.21.8/` - Minecraft 1.21.8 build configuration
  - `versions/1.21.9/` - Minecraft 1.21.9 build configuration
  - `versions/1.21.10/` - Minecraft 1.21.10 build configuration
- `run/` - Server runtime directory with world data and configuration

### Generated Artifacts
After running `./gradlew build`, JAR files are generated at:
- `versions/1.21.8/build/libs/safeserver-1.21.8-2.0.3.jar`
- `versions/1.21.9/build/libs/safeserver-1.21.9-2.0.3.jar`
- `versions/1.21.10/build/libs/safeserver-1.21.10-2.0.3.jar`

## Code Architecture

### Core Components

1. **Main Mod Class (`Safeserver.java`)**
   - Entry point implementing `ModInitializer`
   - Manages player authentication state and password storage
   - Handles player join/disconnect events
   - Implements gameplay blocking for unauthenticated players
   - Uses SHA-256 for password hashing with JSON file persistence

2. **Authentication Commands (`AuthCommands.java`)**
   - `/setpassword <password> <password>` - First-time password setting
   - `/login <password>` - Player authentication
   - `/changepassword <old> <new> <new>` - Change existing password
   - `/resetpassword <player>` - Admin command to reset player passwords (OP level 2+)

3. **Player State Management**
   - Places authenticating players in Spectator mode at spawn with blindness effect
   - Blocks all interactions (block breaking/placing, item use, entity interaction)
   - Restricts commands to only authentication-related ones
   - Freezes player position until authenticated
   - Preserves original gamemode, position, and OP status for restoration

4. **Security Features**
   - Temporarily removes OP status during authentication
   - Prevents coordinate leakage by teleporting to safe spawn location
   - Stores passwords as SHA-256 hashes in `config/safeserver/passwords.json`
   - Validates password length (minimum 4 characters)

### Key Data Structures
- `authenticatingPlayers` - Set of UUIDs currently requiring authentication
- `playerPasswords` - Map of UUID strings to hashed passwords
- `originalGameModes` - Preserves player gamemode before authentication
- `originalPositionsBeforeAuth` - Stores player position before teleport to spawn
- `initialPositions` - Safe spawn position for position freezing
- `originalOpStatus` - Tracks original OP status for restoration

### Event Handling
- Uses Fabric API event callbacks for player lifecycle and interaction blocking
- Server tick events for position enforcement
- Command registration through Fabric command API

## Development Notes

- This is a security-focused mod for defensive purposes only
- Java 21 source/target compatibility
- Uses Fabric Loom for Minecraft mod development
- Mixin framework for runtime code injection (currently has example mixin only)
- Configuration stored in `run/config/safeserver/passwords.json`

## Testing

Run the server with `./gradlew runServer` to test authentication flow. The mod automatically creates necessary directories and configuration files on first run.