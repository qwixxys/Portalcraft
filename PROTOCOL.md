# Portalcraft shared memory protocol

Three pagefile-backed mappings (`CreateFileMappingW(INVALID_HANDLE_VALUE, ...)`), little endian.
Whoever starts first creates them; both sides use the same sizes.

## `Local\PortalcraftHost` (64 KiB, Portal 2 writes, Minecraft reads)
| off | type | field |
|---|---|---|
| 0 | u32 | magic `0x31484350` ("PCH1") |
| 4 | u32 | seq: odd while writing (seqlock) |
| 8 | u32 | flags: 1 in game, 2 paused/menu, 4 build mode, 8 console open, 16 third person, 32 jump held |
| 12 | u32 | heartbeat (+1 per Portal 2 frame) |
| 16 | f32[3] | camera origin (Source units) |
| 28 | f32[3] | camera angles: pitch, yaw, roll (degrees, Source convention) |
| 40 | f32 | fov (Source: horizontal, 4:3-based, degrees) |
| 44 | i32 | back buffer width |
| 48 | i32 | back buffer height |
| 52 | f32[3] | player origin (feet) |
| 64 | f32[3] | player velocity |
| 76 | i32 | player health |
| 80 | f64 | host time (s) |
| 88 | char[64] | map name |
| 152 | u32 | frame slot Portal 2 is reading (0..2, or 0xFFFFFFFF) |
| 156 | u32 | camera serial (OverrideView count) |
| 160 | i32 | player flags (m_fFlags: 1 on ground, 2 ducking) |
| 256 | u32 | input events written (monotonic count) |
| 264 | {i32 type,a,b,c}[512] | input ring, index = count % 512 |
| 9000 | char[260] | Portal 2 game folder (`...\Portal 2\portal2`) |
| 9300 | u32 | session: this run of Portal 2 |
| 9304 | u32 | level loads so far (written last) |
| 9308 | u32 | last load: 1 fresh map (new game, chapter, next map), 2 a save game |

Input event types: 1 key (a = Windows VK, b = 1 down / 0 up), 2 mouse button (a = 0 left, 1 right, 2 middle, b = down),
3 wheel (a = notches, +up), 4 mouse move (a = dx, b = dy), 5 char (a = UTF-16 code unit),
6 cursor (a = x, b = y, client pixels).

## `Local\PortalcraftGuest` (2 MiB, Minecraft writes, Portal 2 reads)
| off | type | field |
|---|---|---|
| 0 | u32 | magic `0x31474350` ("PCG1") |
| 4 | u32 | seq (seqlock) |
| 8 | u32 | flags: 1 connected, 2 a screen is open (needs cursor), 4 hide Portal 2 view model, 8 Minecraft drives the player, 16 its feet are a valid Portal 2 spot, 32 hand the player back now, 64 camera inside Portal 2's walls, 128 busy (resetting the map: don't show Minecraft), 256 lockstep (one frame per Portal 2 camera: Portal 2 waits for the slot with its camera serial before presenting), 512 the eye is inside Portal 2's drawn walls (falling through a hole: Portal 2's depth is only good for what it sees through the hole) |
| 12 | u32 | heartbeat |
| 16 | i32 | solid box count N (max 2000; Minecraft sends at most 300, nearest first) |
| 20 | i32 | solid boxes serial (changes when the set changes) |
| 32 | i32[6][N] | solid boxes near the player in the Source grid (cell = 32 units): min corner cell x, y, z and size in cells x, y, z (neighbouring Minecraft blocks merged: Portal 2 has room for about 2000 entities in all) |
| 49152 | u32 | drive serial (odd while writing) |
| 49156 | f32[3] | drive: camera eye (Source units; Portal 2 renders from here while Minecraft drives) |
| 49168 | f32[3] | drive: feet |
| 49180 | f32[3] | drive: velocity (units/s) |
| 65536 | u32 | hole grid seq (odd while writing) |
| 65540 | u32 | hole grid serial |
| 65544 | i32[3] | hole grid origin (Source cells) |
| 69632 | u8[512*512*4] | hole grid: 128 x 128 x 64 cells, 255 = Portal 2's wall is broken there. Cell (x, y, z) relative to the origin is byte `((y + (z >> 4) * 128) * 512 + x + ((z >> 2) & 3) * 128) * 4 + (z & 3)` (uploaded as a 512 x 512 texture, bytes in R, G, B, A order) |

## `Local\PortalcraftFrame` (header 4 KiB + 3 slots, Minecraft writes, Portal 2 reads)
| off | type | field |
|---|---|---|
| 0 | u32 | magic `0x31464350` ("PCF1") |
| 4 | u32 | latest complete slot (0..2), 0xFFFFFFFF = none |
| 8 | u32 | width |
| 12 | u32 | height |
| 16 | u32 | frame serial |
| 20 | u32 | depth mode: 0 = reversed, GL -1..1; 1 = reversed, 0..1 |
| 24 | f32 | near (blocks) |
| 28 | f32 | far (blocks) |
| 64 + 64*i | slot header | u32 serial, u32 host camera serial, f32[3] eye, f32[3] angles, f32 fov |

Slot i starts at `4096 + i * W*H*12` with the layout colour RGBA8 (W*H*4), depth f32 (W*H*4), overlay RGBA8 (W*H*4).
Rows are bottom-up (OpenGL). Max size is 2560x1440.
