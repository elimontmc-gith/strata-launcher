# Strata Launcher

Strata Launcher is a Java 21 desktop launcher for managing and launching independent Minecraft game environments.

## Requirements

- Java 21 JDK
- Internet access on the first build, so Gradle, dependencies, and a Java 21 JDK toolchain can be downloaded if one is not installed

The Gradle wrapper pins the build to Gradle 8.14.3. A system Gradle installation is not needed.
If no Java 21 JDK is installed, Gradle uses the Foojay toolchain resolver to provision one; a Java runtime alone is not sufficient to compile the launcher.

## Build and run

```sh
./gradlew test
./gradlew run
```

On Windows, use `gradlew.bat test` and `gradlew.bat run`.

## Current implementation

- JavaFX desktop window with the supplied Strata logo and a dark, centralized CSS theme
- Home, instance-library, and application-settings navigation
- Persistent instance creation, listing, search, rename, duplicate, favorite, folder open, and confirmed deletion
- Independent per-instance `game` directories for mods, resource packs, shader packs, saves, screenshots, and logs
- Per-instance memory allocation, window dimensions, fullscreen preference, and optional Java executable selection
- Per-instance mod JAR, resource-pack ZIP, and shader-pack ZIP import, inventory, removal, and folder access
- Atomic JSON configuration writes that preserve fields added by newer launcher versions
- Dynamic release/snapshot discovery from Mojang's version manifest, with a six-hour cache and offline stale-cache fallback
- Per-instance version selection from validated manifest entries
- Vanilla installation of version metadata, client JAR, platform libraries/natives, asset indexes, and assets
- SHA-1/size verification when official metadata provides it, atomic cache writes, isolated install staging, and visible download progress
- Installed Java runtime discovery, Minecraft Java compatibility checks, version metadata argument/rule handling, and separate game processes with stop/status controls
- Offline local profiles and Microsoft device-code authentication with Minecraft entitlement/profile checks
- Fabric and Quilt metadata/profile installation, plus NeoForge Maven version discovery and its official client installer
- Account, loader, launch, instance-settings, and content-management controls in the desktop UI
- Background execution for instance filesystem operations
- SLF4J/Logback application logging
- JUnit 5 tests for application settings, instance lifecycle/filesystem isolation, Java compatibility, and launch command construction

Application data is stored in the platform's normal user configuration location. On Linux, this is `$XDG_CONFIG_HOME/strata-launcher` or `~/.config/strata-launcher`.

New instances start without a Minecraft version. Select an official release or snapshot in the instance details, install the game files, then launch with a selected account and compatible detected Java runtime. Shared immutable libraries/assets are cached under the launcher data directory; mutable game data remains under each instance's `game` directory. Each game starts as its own OS process.

Use **Instance settings** to control the maximum heap size, game window dimensions, fullscreen launch, and an optional Java executable override (leave it blank for automatic detection). **Manage content** copies `.jar` files into that instance's `mods` directory and `.zip` files into its `resourcepacks` or `shaderpacks` directory. Removing content deletes only the selected file from that instance. Settings are preserved when duplicating an instance; game content is copied only when the duplicate option to copy game files is enabled.

Microsoft sign-in requires a Microsoft Entra public-client application configured for device-code flow. Set its application (client) ID in `STRATA_MICROSOFT_CLIENT_ID` before starting Strata Launcher. Microsoft access tokens are held in memory only and are not saved to the account profile file; online accounts must sign in again after the launcher exits. Offline profiles are local-only and cannot authenticate to online services.

Java runtimes are detected from `JAVA_HOME`, `PATH`, and common system locations; Strata Launcher does not download or manage JDK installations. NeoForge installation invokes its official installer JAR. Version metadata with unsupported or malformed structures fails explicitly; a version appearing in Mojang's catalog alone does not imply that every launcher feature supports it. Strataloader support remains future work.
