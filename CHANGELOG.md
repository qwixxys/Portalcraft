# Changelog

## 1.0.4 (2026-10-09)
- Fixed: since 1.0.3 laser beams were cut by every Minecraft block, even blocks behind them. 1.0.3 judged from
  Portal 2's device creation flags that render states can't be read back and skipped the glows' depth; the add-on
  now asks the device itself. Without a glow depth, glows now go on top of Minecraft rather than vanishing.
- The add-on checks every state it reads before drawing; if anything can't be read it leaves Portal 2 alone.

## 1.0.3 (2026-10-09)
- Fixed: since 1.0.1 parts of Portal 2 lost their light and showed black (frames, broken ceilings, decals under the
  projected sunlight). Capturing Portal 2's glows changed its render state behind its back; the add-on now saves and
  restores the D3D9 state exactly around its own draws, and leaves Portal 2's added light passes alone.
- `debug.txt` value 9 turns the glow layer off (for comparing).

## 1.0.2 (2026-10-09)
- Fixed: craters blown into ceilings (and other holes with no sky above) were black. Portal 2's rooms now light the
  first few blocks of every hole in their walls, fading with depth; tunnels dug farther keep Minecraft's own light.

## 1.0.1 (2026-10-09)
- Fixed: Minecraft blocks behind Portal 2's laser beams hid them, and so did holes under a beam. Portal 2's glowing
  effects (lasers, sprites, particles) are captured as a layer of their own, with their own depth, and go back on top
  of Minecraft wherever they are nearer than its blocks.
- Fixed: thin outlines of Portal 2 objects showed through Minecraft blocks standing in front of them.
- Updating replaces the older mod jar instead of leaving both in the mods folder.

## 1.0.0 (2026-10-08)
First release. Portal 2 (Steam, build 10090) + Minecraft Java 26.3 with Fabric Loader 0.19.5 and Fabric API 0.161.0.

- Real Minecraft drawn inside Portal 2 by depth: blocks, mobs, hand, HUD, inventory, crafting and sounds are Minecraft's.
- Build mode (B), first/third person (F5), V picks up Portal 2 objects, blocks press Portal 2's floor buttons.
- Chat and commands (T, /) in any mode; no Minecraft music.
- Portal 2's walls, floors and ceilings break (by hand or TNT); Minecraft's own underground (stone, deepslate,
  ores, caves, water, lava, grass at the top) is generated behind them; Minecraft's physics takes over near holes.
- Spawn eggs and /summon; nothing spawns by itself inside Portal 2's rooms.
- Elytra: jump again in mid-air.
- A fresh visit of a map starts its Minecraft side clean; loading a save keeps it.
- Minecraft renders each frame from Portal 2's camera and Portal 2 shows its frame only together with it (no lag
  when turning or moving); render distance capped at 10 chunks; undead mobs don't burn in daylight.
- Half-block floors and ceilings get slabs at their exact height; holes and tunnels get rock walls.
- Explosions break only flat solid walls and floors (glass, grates, slanted and curved pieces stay).
- Fixed: Minecraft crashed while quitting after Portal 2 closed; uploads of Minecraft's frame are 4x faster.
- Fixed: rock behind the walls showed through Portal 2's walls, floors and ceilings, farther off and in flashes while
  moving near a hole. Minecraft no longer draws the faces of wall rock and floor slabs that lie on Portal 2's
  surfaces, only those facing holes and tunnels. This works with Fabric API's Indigo renderer, which builds the
  world's blocks in place of vanilla's code. The edges of holes no longer show a thin line of sky.
- Falling through a hole in the floor: no more white flash and black boxes while the camera passes through Portal 2's
  floor. While Minecraft moves the player, Portal 2 draws without visibility culling (`r_novis`, not saved), so the
  room above stays right from inside its floor. Faces lying on a floor seen edge-on get a depth margin to match, and
  rock faces under partly solid cells (debris, trim, recesses) are left out like those toward the rooms.
- Fixed: Portal 2 quit with "ED_Alloc: no free edicts" when many Minecraft blocks stood near the player. Neighbouring
  blocks now share one solid box in Portal 2, at most 300 boxes, the nearest first.
- Nether and End portals no longer take the player away from Portal 2's map. A save with the player in another
  dimension brings them back to the overworld. Their terrain had been made solid in Portal 2's rooms.
- Fixed: breaking one block could set off a chain reaction. Wall rock could be gravel or sand, which fell out of
  ceilings, and each hole it left generated more terrain. Wall rock is now always solid stone, and new terrain stays
  put like freshly generated land: gravel doesn't fall and water doesn't flow until something next to it changes.
  Worlds from earlier builds are repaired when the map loads: the gravel becomes stone, the holes it left close, and
  fallen blocks leave the rooms.
- Fixed: Minecraft blocks stood inside Portal 2's invisible walls (blockers, clips). No terrain is generated there
  any more, and blocks already put there are removed.
- Minecraft's frame now matches Portal 2's aspect ratio exactly on screens wider than 2560 pixels.
- One-click launcher (Portalcraft.cmd), installer and uninstaller.
