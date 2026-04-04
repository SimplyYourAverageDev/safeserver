# Safeserver

A simple Fabric mod for Minecraft that adds mandatory password authentication to your server, enhancing security.

## Features

*   **Password Protection:** Players must set a password on their first join and log in on subsequent joins.
*   **Interaction Blocking:** Prevents unauthenticated players from breaking/placing blocks, using items/entities, or interacting with the world.
*   **Command Restriction:** Blocks all commands except `/login` and `/setpassword` until the player is authenticated.
*   **Secure Storage:** Passwords are securely hashed (SHA-256) and stored in a JSON file (`config/safeserver/passwords.json`).
*   **OP Safety:**
    *   Temporarily removes OP status from players upon joining until they authenticate.
    *   Removes OP status from players upon disconnecting as a safety measure.
    *   Restores OP status after successful authentication if the player was originally OP.
*   **Position Freeze & Safety:** Players are placed in Spectator mode and teleported to a safe, fixed location (0, calculated surface Y, 0) upon joining if authentication is needed. They are kept at this location until authenticated, preventing coordinate leakage. Their original position is restored upon successful login.

## Commands

*   `/setpassword <password> <password>`
    *   Sets your initial password upon first joining the server.
    *   Requires typing the password twice for confirmation.
    *   Only usable when required (first join).
*   `/login <password>`
    *   Logs you into the server with your existing password.
    *   Only usable when required (subsequent joins).
*   `/changepassword <oldPassword> <newPassword> <newPassword>`
    *   Allows an authenticated player to change their own password.
    *   Requires the old password and confirmation of the new password.
*   `/resetpassword <playerName>`
    *   **OP Only (Level 2+):** Resets the password for the specified player.
    *   Forces the target player to set a new password using `/setpassword` on their next join (or immediately if they are currently online).

## Installation

1.  Ensure you have Fabric Loader for Minecraft `26.1.1` installed.
2.  Download the `Safeserver` mod JAR file.
3.  Place the JAR file into your server's `mods` folder.
4.  Restart your server.

The mod will automatically generate the necessary configuration file upon first load. 

## Publishing to Modrinth

Use `scripts/publish_modrinth.sh` to build and publish a new version to Modrinth automatically.

1. Create a Modrinth PAT with `VERSION_CREATE` scope.
2. Export it in your shell:
   `export MODRINTH_TOKEN=your_token_here`
3. Run one of:
   - Publish current `mod_version` from `gradle.properties`:
     `scripts/publish_modrinth.sh --changelog-text "Bug fixes and improvements"`
   - Bump version and publish:
     `scripts/publish_modrinth.sh --version 2.1.0 --set-version --changelog-file CHANGELOG.md`

Useful flags:
- `--game-versions "26.1.1"` to override targeted MC versions
- `--loaders "fabric"` to set loaders
- `--sources-jar build/libs/safeserver-2.1.0-sources.jar` to override detected sources jar
- `--version-type release|beta|alpha`
- `--status listed|unlisted|draft|archived`
- `--featured true|false`
- `--skip-build` if you already built the jar

Defaults:
- Project: `safeserver`
- API: `https://api.modrinth.com/v2`
- Loader: `fabric`
- Game version: read from `gradle.properties` (`minecraft_version`)
- Uploads both main and sources jars in the same Modrinth version

### GitHub Actions Auto Publish

This repo now includes `.github/workflows/release-modrinth.yml`.

Setup:
1. Add repository secret `MODRINTH_TOKEN` (PAT with `VERSION_CREATE` scope).
2. Keep `gradle.properties` `mod_version` in sync with your release tag.

Release flow:
1. Commit your release changes (including `mod_version` bump).
2. Create and push a tag matching the version:
   `git tag v2.1.0`
   `git push origin v2.1.0`
3. Workflow builds and publishes to Modrinth automatically.
