// Shared memory with the Minecraft mod (layout: PROTOCOL.md)
#pragma once
#include <cstdint>
#include "game.hpp"

namespace bridge
{
	constexpr uint32_t MAX_W = 2560, MAX_H = 1440;
	constexpr uint32_t HOLES_W = 512, HOLES_H = 512, HOLES_BYTES = HOLES_W * HOLES_H * 4;

	enum HostFlags : uint32_t { IN_GAME = 1, PAUSED = 2, BUILD = 4, CONSOLE = 8, THIRD = 16, JUMP = 32 };
	// Minecraft -> Portal 2: a screen is open; Minecraft moves the player (and where it is valid for Portal 2);
	// hand the player back now; the camera is inside Portal 2's walls
	enum GuestFlags : uint32_t { G_CONNECTED = 1, G_SCREEN = 2, G_HIDE_VIEWMODEL = 4, G_DRIVE = 8, G_VALID = 16, G_RETURN = 32,
		G_CAM_OUTSIDE = 64, G_BUSY = 128, G_LOCKSTEP = 256, G_CAM_IN_WALL = 512 };
	// G_BUSY: Minecraft is resetting the map (don't show it); G_LOCKSTEP: it renders one frame per Portal 2 camera
	enum Event : int32_t { EV_KEY = 1, EV_BUTTON = 2, EV_WHEEL = 3, EV_MOVE = 4, EV_CHAR = 5, EV_CURSOR = 6 };

	struct FrameHeader
	{
		uint32_t magic, latest, width, height, serial, depth_mode;
		float near_plane, far_plane;
	};
	struct SlotHeader { uint32_t serial, camera_serial; float eye[3], angles[3], fov; };
	// Source units; velocity per second
	struct DriveState { float eye[3], feet[3], vel[3]; };

	bool open();
	void publish_camera(const game::Camera &cam, const game::Player &pl, uint32_t flags, const char *map);
	void heartbeat();
	void push_event(int32_t type, int32_t a, int32_t b = 0, int32_t c = 0);
	void set_reading_slot(uint32_t slot);
	// a level finished loading: kind 1 = fresh (new game, chapter, next map), 2 = a save game (death, quickload)
	enum LoadKind : uint32_t { LOAD_FRESH = 1, LOAD_SAVE = 2 };
	void publish_load(uint32_t serial, uint32_t kind);

	const FrameHeader *frame_header();
	const SlotHeader *slot_header(uint32_t i);
	const uint8_t *slot_data(uint32_t i); // colour, then depth, then overlay (each W*H*4)

	// guest -> host
	uint32_t guest_flags();
	bool guest_alive();
	// solid boxes: 6 ints each (min corner cell x, y, z and size in cells), at most max
	int solid_boxes(int32_t *boxes, int max, int &serial);
	bool drive_state(DriveState &out);
	// holes in Portal 2's walls: 128 x 128 x 64 cells from origin (Source cells), 4 z cells per BGRA texel
	uint32_t holes_serial();
	bool holes_copy(uint8_t *dst, int32_t origin[3], uint32_t &serial);
}
