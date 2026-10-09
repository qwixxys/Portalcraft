# Portalcraft

Real Minecraft inside Portal 2. Minecraft Java 26.3 runs alongside Portal 2, renders every frame from Portal 2's
camera, and is blended into the picture by depth: blocks stand on Portal 2's floors, hide behind its walls and are
solid to Chell.

## Features
- Build, mine and craft in Portal 2's test chambers; Minecraft blocks press floor buttons.
- Break Portal 2's walls and floors by hand or with TNT and dig into real Minecraft terrain behind them.
- Minecraft chat and commands, mobs, inventory, elytra.
- Starting a map again resets it; loading a save keeps your changes.

## Requirements
- Windows 10/11
- Portal 2 (Steam)
- Minecraft Java Edition 26.3, installed by the official launcher or Prism Launcher (the installer adds Fabric
  Loader 0.19.5 and Fabric API)

## Install
1. Download the latest `Portalcraft-<version>.zip` from [Releases](https://github.com/qwixxys/Portalcraft/releases) and unzip it.
2. Close the Minecraft Launcher and run `install.cmd`.

## Play
1. Run `Portalcraft.cmd` and choose how to start Minecraft:
   1. **With your account**: the official launcher opens (pick the **Portalcraft** installation and press Play),
      or Prism Launcher starts its own Portalcraft instance.
   2. **Offline**: no sign-in, any nickname. Starts the Minecraft 26.3 your launcher has already downloaded.
   3. **Another launcher**: Portal 2 only; start a Minecraft 26.3 + Fabric instance with the two jars from
      `%APPDATA%\.minecraft\portalcraft\mods` yourself.
2. Start any chapter in Portal 2.

| Key | Action |
|---|---|
| B | Minecraft build mode / portal gun |
| LMB / RMB | Break / place (build mode) |
| E | Inventory (build mode) |
| V | Grab Portal 2 objects |
| F5 | Third person |
| T, / | Chat, commands |
| Space in mid-air | Elytra |

`uninstall.cmd` removes everything except your Minecraft world.

## Notes
- Single player only. The mod enables `sv_cheats`, so Steam achievements don't unlock while it runs.
- Nether and End portals are disabled.
- Full guide in Russian: [README.ru.md](README.ru.md). Shared-memory protocol: [PROTOCOL.md](PROTOCOL.md).

## Building
- `mc/`: Fabric mod, `gradlew build` (Java 25).
- `addon/`: ReShade add-on, `build.cmd` (MSVC, x86).

## Credits
- [ReShade](https://reshade.me) by crosire (BSD-3-Clause), [Fabric](https://fabricmc.net) (Apache-2.0).
- Portal 2 by Valve, Minecraft by Mojang. No game files are included.
- Made by qwixxys; the code was written by Claude Code (Anthropic, Claude Opus 5.5).

## License
[MIT](LICENSE)
