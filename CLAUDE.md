# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Minecraft Fabric mod called "Safeserver" that adds mandatory password authentication to Minecraft servers. It's built using Java 21 and Fabric API, targeting Minecraft 1.21.11.

## Development Commands

### Build and Development
- `./gradlew build` - Build the mod JAR file
- `./gradlew publishToMavenLocal` - Publish to local Maven repository
- `./gradlew runServer` - Run the mod in a server environment for testing
- `./gradlew runClient` - Run the mod in a client environment for testing

### Project Structure
- `src/main/java/` - Main mod source code
- `src/client/java/` - Client-side specific code
- `src/main/resources/` - Resources including mod metadata and mixin configurations
- `build.gradle` - Main build configuration
- `gradle.properties` - Version information and dependencies
- `run/` - Server runtime directory with world data and configuration

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
