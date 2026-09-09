# MobSpawnEggs — Free Edition

**Version 3.0.2** · Hytale Server 0.6.x

MobSpawnEggs Free is a lightweight server-side mod that adds single-use spawn eggs for available vanilla Hytale animals, creatures, monsters and NPCs.

## Features

- 266 single-use spawn eggs.
- Left-click/direct spawning only. No throwable-egg mode.
- Server-authoritative target validation with a downward aim range from -20° to -90°.
- A valid target surface is required within 8 blocks.
- The egg is consumed only after Hytale confirms a successful NPC spawn.
- Optional world blacklist and direct-spawn diagnostics/logging.
- Eight bundled languages: English, Italian, German, Spanish, French, Brazilian Portuguese, Russian and Turkish.
- Automatic regional locale fallback for the bundled language families.
- Uses vanilla NPC/mob EntityIds and leaves vanilla AI/behavior rules untouched.
- No MobSpawnEggs permissions are registered in the Free edition.

## Installation

1. Place `MobSpawnEggs-3.0.2.jar` in the server `mods` folder.
2. Start or restart the server.
3. Open the Creative Library and look for the MobSpawnEggs categories.

## Configuration

On first start the mod creates `mods/MobSpawnEggs/config.json`. It supports world blacklisting, debug/console output, success/failure logging and log retention. The default retention is 15 days; `0` disables automatic retention cleanup. Older daily Free-edition log files are consolidated automatically.

## Building from source

The project targets Java 21 and expects a local `libs/HytaleServer.jar`. Run `gradle build` from the repository root.

The 266 egg definitions live in `src/main/egg-data/eggs.tsv`. Translation source is split into small, human-readable TSV catalogs under `src/main/locale-data`; Gradle emits the eight Hytale `server.lang` files and the regional fallback table during resource processing. PNG textures/icons and the Creative Library category art are kept as normal assets under `src/main/resources/Common`.

## Compatibility

- Hytale Server: `>=0.6.0 <0.7.0`
- Tested on Hytale Server 0.6.1
- Java: 21

## License

MIT. See `LICENSE`.
