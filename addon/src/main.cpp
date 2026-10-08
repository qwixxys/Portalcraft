// Portalcraft ReShade add-on (32-bit, for Portal 2's D3D9 renderer).
// Publishes Portal 2's camera and input to the Minecraft mod and composites Minecraft's frame by depth.
#include <reshade.hpp>
#include "game.hpp"
#include "bridge.hpp"
#include "render.hpp"
#include "glow.hpp"
#include "input.hpp"
#include "solids.hpp"
#include "console.hpp"
#include <cstdio>
#include <cstring>
#include <cmath>
#include <string>

extern "C" __declspec(dllexport) const char *NAME = "Portalcraft";
extern "C" __declspec(dllexport) const char *DESCRIPTION = "Draws real Minecraft inside Portal 2.";

using namespace reshade::api;

static HMODULE g_module = nullptr;
static bool g_hooked = false;
static DWORD g_last_status = 0;

static void log(const std::string &msg)
{
	reshade::log::message(reshade::log::level::info, msg.c_str());
}

// While Minecraft drives the player, Portal 2 renders from Minecraft's camera
static bool override_origin(float *origin)
{
	if (!input::drive) return false;
	bridge::DriveState ds;
	if (!bridge::drive_state(ds)) return false;
	memcpy(origin, ds.eye, 12);
	return true;
}

// While Minecraft drives: Portal 2's player follows it wherever Portal 2 could stand there (so portals, buttons,
// goo and level triggers still see her), and otherwise waits at the last such spot. Every 100 ms, or every 30 ms
// while Minecraft hands the player back.
static void follow_minecraft(const game::Player &pl)
{
	static DWORD s_last = 0;
	static bool s_driving = false;
	static float s_hold[3] = {};
	if (!input::drive || !pl.valid)
	{
		s_driving = false;
		return;
	}
	if (!s_driving)
	{
		s_driving = true;
		memcpy(s_hold, &pl.origin, 12); // where she stood when Minecraft took over
	}
	uint32_t gf = bridge::guest_flags();
	bridge::DriveState ds;
	if (!bridge::drive_state(ds)) return;
	DWORD now = GetTickCount();
	if (now - s_last < ((gf & bridge::G_RETURN) ? 30u : 100u)) return; // the command buffer takes only so much
	s_last = now;
	char cmd[256];
	if (gf & bridge::G_VALID)
	{
		memcpy(s_hold, ds.feet, 12);
		snprintf(cmd, sizeof(cmd), "script PC_Move(%.2f,%.2f,%.2f,%.1f,%.1f,%.1f)", ds.feet[0], ds.feet[1], ds.feet[2], ds.vel[0], ds.vel[1], ds.vel[2]);
	}
	else
		snprintf(cmd, sizeof(cmd), "script PC_Move(%.2f,%.2f,%.2f,0,0,0)", s_hold[0], s_hold[1], s_hold[2]);
	game::client_cmd(cmd);
}

// main thread, once per rendered frame
static void on_view(const game::Camera &cam)
{
	game::Player pl = game::local_player();
	uint32_t flags = 0;
	if (game::in_game()) flags |= bridge::IN_GAME;
	if (game::paused()) flags |= bridge::PAUSED;
	if (game::console_visible()) flags |= bridge::CONSOLE;
	if (input::build) flags |= bridge::BUILD;
	if (game::last_buttons & 2) flags |= bridge::JUMP; // IN_JUMP, before the add-on strips it
	if (input::drive)
	{
		if (input::third) flags |= bridge::THIRD;
	}
	else if (pl.valid)
	{
		float ex = pl.origin.x + pl.view_offset.x, ey = pl.origin.y + pl.view_offset.y, ez = pl.origin.z + pl.view_offset.z;
		float dx = cam.origin.x - ex, dy = cam.origin.y - ey, dz = cam.origin.z - ez;
		if (dx * dx + dy * dy + dz * dz > 30.0f * 30.0f) flags |= bridge::THIRD;
	}
	bridge::publish_camera(cam, pl, flags, game::level_name().c_str());
	render::state.znear = cam.znear;
	render::state.zfar = cam.zfar;
	// While Minecraft drives, the camera can be inside Portal 2's walls (falling through a hole in the floor): from a
	// solid leaf Portal 2's visibility draws garbage (white, black boxes), without it the room through the hole is right
	static bool s_novis = false;
	if (input::drive != s_novis)
	{
		s_novis = input::drive;
		game::client_cmd(s_novis ? "r_novis 1" : "r_novis 0");
	}
	solids::update();
	follow_minecraft(pl);
}


// A level finished loading (in game again after a loading screen): tell Minecraft whether it is a fresh visit
// (new game, chapter select, next map: the map's Minecraft side starts clean) or a save game (keeps it).
static void watch_loads()
{
	static bool s_in_game = false;
	static uint32_t s_serial = 0;
	bool in = game::in_game();
	if (in && !s_in_game)
	{
		uint32_t kind = console::take_save_load() ? bridge::LOAD_SAVE : bridge::LOAD_FRESH;
		bridge::publish_load(++s_serial, kind);
		solids::on_load();
		if (input::third) // a new player entity: hide Chell again, the camera setting is kept
			game::client_cmd("script PC_Third(true)");
		log(std::string("Portalcraft: level loaded (") + (kind == bridge::LOAD_SAVE ? "save game" : "fresh") + ")");
	}
	s_in_game = in;
}

// One Portal 2 frame per Minecraft frame needs Portal 2's view and present on one thread: synchronous rendering
// while Minecraft is connected (mat_queue_mode is not saved in the config, Portal 2 starts with its default again).
static void watch_render_mode()
{
	static int s_mode = -2; // what we last set
	bool want_sync = bridge::guest_alive() && game::in_game();
	int mode = want_sync ? 0 : -1;
	if (mode == s_mode || !game::in_game()) return; // console_cmd needs a map
	s_mode = mode;
	game::console_cmd(mode == 0 ? "mat_queue_mode 0" : "mat_queue_mode -1");
	log(std::string("Portalcraft: mat_queue_mode ") + std::to_string(mode));
}

static void on_present(command_queue *, swapchain *swap, const rect *, const rect *, uint32_t, const rect *)
{
	if (swap->get_device()->get_api() != device_api::d3d9)
		return;
	if (!g_hooked)
	{
		if (GetModuleHandleA("client.dll") && GetModuleHandleA("engine.dll"))
		{
			game::on_view = &on_view;
			game::override_origin = &override_origin;
			int sv = game::patch_player_size("server.dll"), cl = game::patch_player_size("client.dll");
			log("Portalcraft: Minecraft-sized player: server tables " + std::to_string(sv) + ", client tables " + std::to_string(cl));
			g_hooked = game::init();
			bool shm = bridge::open();
			log(std::string("Portalcraft: console listener ") + (console::attach() ? "on" : "NOT available"));
			log(std::string("Portalcraft: hooks ") + (g_hooked ? "installed" : "NOT installed") + ", shared memory " + (shm ? "open" : "FAILED"));
		}
		return;
	}
	input::attach(static_cast<HWND>(swap->get_hwnd()));
	watch_loads();
	watch_render_mode();
	bridge::heartbeat();
	input::poll();

	DWORD now = GetTickCount();
	if (now - g_last_status > 5000)
	{
		g_last_status = now;
		const bridge::FrameHeader *fh = bridge::frame_header();
		game::Camera c = game::camera();
		char line[256];
		snprintf(line, sizeof(line), "Portalcraft: map=%s cam=(%.0f %.0f %.0f) minecraft=%s frame=%ux%u #%u build=%d uniforms=%d textures=%d uploads=%u",
			game::level_name().c_str(), c.origin.x, c.origin.y, c.origin.z, bridge::guest_alive() ? "yes" : "no",
			fh ? fh->width : 0, fh ? fh->height : 0, fh ? fh->serial : 0, input::build ? 1 : 0,
			render::state.uniforms_found, render::state.textures_ok, render::state.uploads);
		log(line);
		log(std::string("Portalcraft: input ") + input::debug());
		static unsigned s_synced = 0, s_late = 0, s_wait = 0, s_upload = 0, s_uploads = 0;
		unsigned synced = render::state.synced - s_synced, late = render::state.late - s_late, wait = render::state.wait_us - s_wait;
		unsigned upload = render::state.upload_us - s_upload, uploads = render::state.uploads - s_uploads;
		s_synced = render::state.synced; s_late = render::state.late; s_wait = render::state.wait_us;
		s_upload = render::state.upload_us; s_uploads = render::state.uploads;
		static unsigned s_glow = 0;
		unsigned glows = glow::draws - s_glow;
		s_glow = glow::draws;
		snprintf(line, sizeof(line), "Portalcraft: in step with Minecraft %u frames, late %u, average wait %u us, upload %u us%s, glow draws %u",
			synced, late, synced + late ? wait / (synced + late) : 0, uploads ? upload / uploads : 0, render::state.staged ? "" : " (direct)", glows);
		log(line);
	}
}

// ReShade can't copy a multisampled depth buffer, and the composite needs Portal 2's depth: ask for no MSAA
static bool on_create_swapchain(device_api api, swapchain_desc &desc, void *)
{
	if (api != device_api::d3d9 || desc.back_buffer.texture.samples <= 1)
		return false;
	desc.back_buffer.texture.samples = 1;
	log("Portalcraft: turned off MSAA so Portal 2's depth can be read");
	return true;
}

static void on_begin_effects(effect_runtime *rt, command_list *, resource_view, resource_view)
{
	glow::begin_effects(rt);
	render::begin_effects(rt);
}

static void on_finish_effects(effect_runtime *, command_list *cmd, resource_view, resource_view)
{
	glow::finish_effects(cmd);
}

static void on_destroy_device(device *dev)
{
	if (dev->get_api() != device_api::d3d9)
		return;
	render::shutdown(dev);
	glow::shutdown(dev);
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID)
{
	switch (reason)
	{
	case DLL_PROCESS_ATTACH:
		g_module = module;
		if (!reshade::register_addon(module))
			return FALSE;
		reshade::register_event<reshade::addon_event::create_swapchain>(on_create_swapchain);
		reshade::register_event<reshade::addon_event::present>(on_present);
		reshade::register_event<reshade::addon_event::reshade_begin_effects>(on_begin_effects);
		reshade::register_event<reshade::addon_event::reshade_finish_effects>(on_finish_effects);
		reshade::register_event<reshade::addon_event::destroy_device>(on_destroy_device);
		glow::attach();
		log("Portalcraft: loaded");
		break;
	case DLL_PROCESS_DETACH:
		input::detach();
		reshade::unregister_addon(module);
		break;
	}
	return TRUE;
}
