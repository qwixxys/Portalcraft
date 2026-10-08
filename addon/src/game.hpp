// Portal 2 (build 10090, 32-bit) engine access: interfaces, RTTI vtable lookup, client-mode hooks.
#pragma once
#include <windows.h>
#include <cstdint>
#include <string>

namespace game
{
	struct Vec3 { float x, y, z; };

	bool init();                    // finds interfaces and installs the client-mode hooks
	int patch_player_size(const char *module); // Minecraft-sized player hull; returns tables patched
	void client_cmd(const char *cmd); // runs a console command on the main thread (queued)
	// ClientCmd refuses commands without FCVAR_CLIENTCMD_CAN_EXECUTE (thirdperson, m_yaw...):
	// these go through VScript's SendToConsole instead (needs sv_cheats, which the add-on turns on)
	void console_cmd(const char *cmd);
	bool in_game();
	bool paused();
	bool console_visible();
	std::string level_name();

	// latest camera from ClientModeShared::OverrideView (render thread order is handled by the caller)
	struct Camera { Vec3 origin; Vec3 angles; float fov; float znear, zfar; int width, height; uint32_t serial; };
	Camera camera();
	// called on the game's main thread after every OverrideView (once per rendered frame)
	extern void (*on_view)(const Camera &);
	// called first, may move the view (origin = 3 floats, Source units): while Minecraft drives the player
	extern bool (*override_origin)(float *origin);

	// local player state through network variables
	struct Player { Vec3 origin, view_offset, velocity; int health; int flags; bool valid; };
	Player local_player();

	// CreateMove: lets the add-on strip buttons (e.g. +attack while building) and read them
	extern volatile uint32_t strip_buttons;
	extern volatile uint32_t last_buttons;
	extern volatile bool freeze_movement;
	extern volatile uint32_t force_buttons; // added after stripping (V = use, in every mode)

	// raw CViewSetup bytes, for finding the layout
	void dump_view_setup(std::string &out);
}
